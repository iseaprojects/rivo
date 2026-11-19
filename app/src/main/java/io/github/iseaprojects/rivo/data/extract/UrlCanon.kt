package io.github.iseaprojects.rivo.data.extract

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * YouTube URL normalisation shared by both backends, the deep-link router and the
 * download archive.
 *
 * yt-x accepts whatever the clipboard holds: `youtu.be/ID`, `watch?v=ID&list=...`,
 * `/shorts/ID`, `/live/ID`, `/embed/ID`, a bare 11-character id, or a `music.youtube.com`
 * link. Everything funnels through here so the extractor always receives a canonical
 * watch URL and the archive always hashes the same string for the same video.
 */
object UrlCanon {

    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val WATCH_KEYS = setOf("v", "video_id")
    private val CHANNEL_TABS = listOf(
        "videos", "shorts", "streams", "playlists", "featured", "podcasts", "releases", "live",
    )

    fun isYoutubeHost(host: String?): Boolean {
        val h = host?.lowercase()?.removePrefix("www.") ?: return false
        return h == "youtube.com" ||
            h == "m.youtube.com" ||
            h == "music.youtube.com" ||
            h == "youtube-nocookie.com" ||
            h == "youtu.be" ||
            h.endsWith(".youtube.com")
    }

    /** Canonical watch URL for a video id. */
    fun watchUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"

    /**
     * Extracts a video id from anything that plausibly contains one.
     * Returns null for channel/playlist URLs and for arbitrary text.
     */
    fun videoId(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        if (VIDEO_ID.matches(text)) return text

        val uri = runCatching { URI(text) }.getOrNull()
        if (uri?.host == null) {
            // Not a URL: look for a watch-style query fragment or a /shorts/ path anyway.
            return videoIdFromQuery(text) ?: pathVideoId(text)
        }
        val host = uri.host.lowercase().removePrefix("www.")
        if (!isYoutubeHost(host)) return null

        if (host == "youtu.be") {
            return uri.path.trim('/').substringBefore('/').takeIf { VIDEO_ID.matches(it) }
        }
        uri.rawQuery?.let { query -> videoIdFromQuery(query)?.let { return it } }
        return pathVideoId(uri.path.orEmpty())
    }

    private fun videoIdFromQuery(query: String): String? {
        val value = query.split('&')
            .mapNotNull { part ->
                val name = part.substringBefore('=', "")
                val raw = part.substringAfter('=', "")
                if (name in WATCH_KEYS && raw.isNotEmpty()) {
                    runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
                } else {
                    null
                }
            }
            .firstOrNull()
        return value?.takeIf { VIDEO_ID.matches(it) }
    }

    private fun pathVideoId(path: String): String? {
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        val candidate = when (segments.first()) {
            "shorts", "live", "embed", "v", "e" -> segments.getOrNull(1)
            else -> return null
        }
        return candidate?.takeIf { VIDEO_ID.matches(it) }
    }

    fun playlistId(input: String): String? {
        val text = input.trim()
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        if (!isYoutubeHost(uri.host)) return null
        val query = uri.rawQuery ?: return null
        return query.split('&').firstOrNull { it.startsWith("list=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }
    }

    /** Channel id (`UC…`), handle (`@name`) or legacy user path, normalised to a URL. */
    fun channelUrl(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        if (text.startsWith("@")) return "https://www.youtube.com/$text"
        if (Regex("^UC[A-Za-z0-9_-]{22}$").matches(text)) {
            return "https://www.youtube.com/channel/$text"
        }
        val uri = runCatching { URI(text) }.getOrNull()
        if (uri?.host == null || !isYoutubeHost(uri.host)) return null
        val path = uri.path.orEmpty().trimEnd('/')
        if (path.isEmpty() || path == "/") return null
        // A tab URL still names the channel: keep only the identity part.
        val trimmed = CHANNEL_TABS.fold(path) { acc, tab ->
            acc.removeSuffix("/$tab")
        }
        return "https://www.youtube.com$trimmed"
    }

    /** Canonical form used as cache key and archive hash input. */
    fun canonical(input: String): String {
        videoId(input)?.let { return watchUrl(it) }
        playlistId(input)?.let { return "https://www.youtube.com/playlist?list=$it" }
        channelUrl(input)?.let { return it }
        return input.trim()
    }

    /** `hqdefault` is 480x360; `maxresdefault` does not exist for every video. */
    fun thumbnailUrl(videoId: String, quality: String = "hqdefault"): String =
        "https://i.ytimg.com/vi/$videoId/$quality.jpg"

    fun searchUrl(query: String, sp: String? = null): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return if (sp.isNullOrBlank()) {
            "https://www.youtube.com/results?search_query=$encoded"
        } else {
            "https://www.youtube.com/results?search_query=$encoded&sp=$sp"
        }
    }

    /**
     * The channel-scoped search URL: YouTube's own Search tab on the channel page.
     * yt-dlp extracts it like any other tab (`YoutubeTabIE`), so this is the whole
     * implementation of channel search for that backend.
     */
    fun channelSearchUrl(url: String, query: String): String {
        val base = channelUrl(url) ?: url.trim().trimEnd('/')
        return "$base/search?query=" + URLEncoder.encode(query, "UTF-8")
    }

    /** True for URLs that name a channel tab rather than a single video. */
    fun isChannelTab(url: String): Boolean {
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        return path.trimEnd('/').substringAfterLast('/') in CHANNEL_TABS
    }
}
