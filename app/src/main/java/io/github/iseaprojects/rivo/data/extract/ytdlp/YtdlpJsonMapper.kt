package io.github.iseaprojects.rivo.data.extract.ytdlp

import io.github.iseaprojects.rivo.core.model.Availability
import io.github.iseaprojects.rivo.core.model.ChannelSummary
import io.github.iseaprojects.rivo.core.model.MediaProvider
import io.github.iseaprojects.rivo.core.model.MediaSummary
import io.github.iseaprojects.rivo.core.model.PlaylistSummary
import io.github.iseaprojects.rivo.data.extract.Chapter
import io.github.iseaprojects.rivo.data.extract.ChannelDetails
import io.github.iseaprojects.rivo.data.extract.ChannelResult
import io.github.iseaprojects.rivo.data.extract.ChannelTab
import io.github.iseaprojects.rivo.data.extract.ChannelTabKind
import io.github.iseaprojects.rivo.data.extract.DeliveryKind
import io.github.iseaprojects.rivo.data.extract.ExtractorException
import io.github.iseaprojects.rivo.data.extract.FailureKind
import io.github.iseaprojects.rivo.data.extract.HeatmapPoint
import io.github.iseaprojects.rivo.data.extract.MediaPage
import io.github.iseaprojects.rivo.data.extract.PlaylistDetails
import io.github.iseaprojects.rivo.data.extract.PlaylistResult
import io.github.iseaprojects.rivo.data.extract.StreamFormat
import io.github.iseaprojects.rivo.data.extract.StreamKind
import io.github.iseaprojects.rivo.data.extract.SubtitleTrack
import io.github.iseaprojects.rivo.data.extract.UrlCanon
import io.github.iseaprojects.rivo.data.extract.VideoDetails
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Pure mapping from yt-dlp's `-J` info JSON to the app's models.
 *
 * Kept apart from [YtDlpBackend] on purpose: parsing is where most yt-dlp regressions
 * show up (new format fields, renamed keys), and this way the mapping is covered by JVM
 * tests with recorded JSON instead of requiring the 90 MB Python runtime.
 */
object YtdlpJsonMapper {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseRoot(payload: String): JsonObject =
        runCatching { json.parseToJsonElement(payload) as? JsonObject }
            .getOrNull()
            ?: throw ExtractorException(FailureKind.EXTRACTOR_BROKEN, "yt-dlp returned invalid JSON")

    fun video(payload: String): VideoDetails = video(parseRoot(payload))

    fun video(root: JsonObject): VideoDetails {
        val id = root.str("id")
            ?: throw ExtractorException(FailureKind.EXTRACTOR_BROKEN, "yt-dlp JSON has no id")
        val webpage = root.str("webpage_url") ?: UrlCanon.watchUrl(id)
        val liveStatus = root.str("live_status")
        val availability = availabilityOf(root.str("availability"))

        val summary = MediaSummary(
            id = id,
            url = webpage,
            title = root.str("title") ?: root.str("fulltitle").orEmpty(),
            provider = providerOf(webpage),
            channelId = root.str("channel_id") ?: root.str("uploader_id"),
            channelName = root.str("channel") ?: root.str("uploader"),
            channelUrl = root.str("channel_url") ?: root.str("uploader_url"),
            description = root.str("description"),
            durationSeconds = root.dbl("duration")?.toLong()?.takeIf { it > 0 },
            viewCount = root.long("view_count"),
            publishedAtEpochMs = publishedAt(root),
            publishedText = root.str("upload_date"),
            thumbnailUrl = bestThumbnail(root),
            isLive = root.bool("is_live") == true || liveStatus == "is_live",
            isShort = webpage.contains("/shorts/"),
            isUpcoming = liveStatus == "is_upcoming" ||
                availability == Availability.UPCOMING,
            availability = availability,
        )

        val formats = mutableListOf<StreamFormat>()
        root.array("formats")?.forEach { element ->
            val format = element as? JsonObject ?: return@forEach
            format.toStreamFormat()?.let { formats += it }
        }
        // A merged request repeats the chosen pair here; present both so a quality switch
        // does not need a second extraction.
        root.array("requested_formats")?.forEach { element ->
            val format = element as? JsonObject ?: return@forEach
            format.toStreamFormat()?.let { candidate ->
                if (formats.none { it.formatId == candidate.formatId && it.kind == candidate.kind }) {
                    formats += candidate
                }
            }
        }

        val subtitles = buildList {
            root.obj("subtitles")?.forEach { (lang, value) ->
                subtitle(lang, value, auto = false)?.let { add(it) }
            }
            root.obj("automatic_captions")?.forEach { (lang, value) ->
                subtitle(lang, value, auto = true)?.let { add(it) }
            }
        }

        val chapters = root.array("chapters").orEmpty().mapNotNull { element ->
            val chapter = element as? JsonObject ?: return@mapNotNull null
            val start = chapter.dbl("start_time") ?: return@mapNotNull null
            Chapter(
                startMs = (start * 1000).toLong(),
                endMs = chapter.dbl("end_time")?.let { (it * 1000).toLong() },
                title = chapter.str("title").orEmpty(),
            )
        }

        val heatmap = root.array("heatmap").orEmpty().mapNotNull { element ->
            val point = element as? JsonObject ?: return@mapNotNull null
            val start = point.dbl("start_time") ?: return@mapNotNull null
            val end = point.dbl("end_time") ?: start
            HeatmapPoint(
                startMs = (start * 1000).toLong(),
                endMs = (end * 1000).toLong(),
                intensity = point.dbl("value")?.toFloat() ?: 0f,
            )
        }

        val storyboard = root.array("storyboards")?.firstOrNull()
            ?.let { it as? JsonObject }
            ?.str("url")

        return VideoDetails(
            summary = summary,
            formats = formats,
            subtitles = subtitles,
            chapters = chapters,
            heatmap = heatmap,
            hlsManifestUrl = root.str("manifest_url")?.takeIf { it.contains(".m3u8") },
            dashManifestUrl = root.str("manifest_url")?.takeIf { it.contains(".mpd") },
            storyboardUrl = storyboard,
            startAtSeconds = root.dbl("start_time")?.toLong() ?: 0,
            extractor = "ytdlp",
        )
    }

    /** Flat list: search results, kiosk feeds, channel tabs, playlist contents. */
    fun list(payload: String): MediaPage = list(parseRoot(payload))

    fun list(root: JsonObject): MediaPage {
        val entries = root.array("entries").orEmpty()
        val videos = mutableListOf<MediaSummary>()
        val playlists = mutableListOf<PlaylistSummary>()
        val channels = mutableListOf<ChannelSummary>()

        entries.forEach { element ->
            val entry = element as? JsonObject ?: return@forEach
            when {
                entry.isPlaylistEntry() -> playlists += entry.toPlaylistSummary()
                entry.isChannelEntry() -> channels += entry.toChannelSummary()
                else -> videos += entry.toMediaSummary()
            }
        }

        return MediaPage(
            items = videos,
            playlists = playlists,
            channels = channels,
            nextToken = null,
            totalCount = root.long("playlist_count"),
        )
    }

    fun channel(payload: String, requestedUrl: String): ChannelResult {
        val root = parseRoot(payload)
        val page = list(root)
        val channelUrl = root.str("channel_url")
            ?: root.str("uploader_url")
            ?: requestedUrl.substringBefore("?")
        val base = channelUrl.trimEnd('/')
        val tabs = channelTabs(base)

        val summary = ChannelSummary(
            id = root.str("channel_id") ?: root.str("id") ?: channelUrl,
            url = channelUrl,
            name = root.str("channel") ?: root.str("uploader") ?: root.str("title").orEmpty(),
            avatarUrl = bestThumbnail(root),
            subscriberCount = root.long("channel_follower_count"),
            description = root.str("description"),
        )
        return ChannelResult(
            details = ChannelDetails(summary = summary, tabs = tabs),
            page = page,
        )
    }

    fun playlist(payload: String, requestedUrl: String): PlaylistResult {
        val root = parseRoot(payload)
        val page = list(root)
        val id = root.str("id") ?: UrlCanon.playlistId(requestedUrl)
        val url = root.str("webpage_url") ?: requestedUrl
        val summary = PlaylistSummary(
            id = id ?: url,
            url = url,
            title = root.str("title").orEmpty(),
            channelName = root.str("channel") ?: root.str("uploader"),
            channelId = root.str("channel_id") ?: root.str("uploader_id"),
            itemCount = root.long("playlist_count")?.toInt(),
            thumbnailUrl = bestThumbnail(root),
            description = root.str("description"),
        )
        return PlaylistResult(
            details = PlaylistDetails(
                summary = summary,
                description = root.str("description"),
            ),
            page = page,
        )
    }

    fun suggestions(payload: String): List<String> {
        val array = runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonArray
            ?: return emptyList()
        return array.mapNotNull { element ->
            (element as? JsonPrimitive)?.contentOrNull
        }
    }

    fun channelTabs(baseUrl: String): List<ChannelTab> {
        val base = baseUrl.trimEnd('/')
        val tabs = listOf(
            ChannelTabKind.VIDEOS to "videos",
            ChannelTabKind.SHORTS to "shorts",
            ChannelTabKind.LIVE to "streams",
            ChannelTabKind.PLAYLISTS to "playlists",
            ChannelTabKind.PODCASTS to "podcasts",
            ChannelTabKind.RELEASES to "releases",
            ChannelTabKind.FEATURED to "featured",
        )
        return tabs.map { (kind, path) ->
            ChannelTab(
                id = "$base/$path",
                name = kind.name.lowercase().replaceFirstChar { it.uppercase() },
                url = "$base/$path",
                kind = kind,
            )
        }
    }

    // -- format mapping -------------------------------------------------------------

    private fun JsonObject.toStreamFormat(): StreamFormat? {
        val url = str("url")
        val manifest = str("manifest_url")
        if (url == null && manifest == null) return null

        val ext = str("ext")
        val formatId = str("format_id") ?: ext ?: return null

        val vcodec = str("vcodec")
        val acodec = str("acodec")
        val hasVideo = !vcodec.isNullOrBlank() && vcodec != "none"
        val hasAudio = !acodec.isNullOrBlank() && acodec != "none"
        val kind = when {
            hasVideo && hasAudio -> StreamKind.MUXED
            hasVideo -> StreamKind.VIDEO_ONLY
            hasAudio -> StreamKind.AUDIO_ONLY
            // The generic extractor reports a direct media file without probing its
            // tracks: `{"ext": "mp4", "vcodec": null, "audio_ext": "none"}`. Dropping
            // those left the player with an empty queue on every non-YouTube URL.
            else -> kindFromContainer(ext) ?: return null
        }

        val height = int("height")?.takeIf { it > 0 }
        val fps = dbl("fps")?.toInt()?.takeIf { it > 0 }
        val languagePreference = int("language_preference")

        return StreamFormat(
            formatId = formatId,
            kind = kind,
            delivery = deliveryOf(str("protocol")),
            url = url,
            manifestUrl = manifest,
            mimeType = mimeOf(ext),
            container = ext,
            codec = if (kind == StreamKind.AUDIO_ONLY) acodec else vcodec,
            qualityLabel = qualityLabel(height, fps, str("format_note"), str("quality")),
            height = height,
            width = int("width")?.takeIf { it > 0 },
            fps = fps,
            videoBitrateKbps = int("vbr") ?: int("tbr"),
            audioBitrateKbps = int("abr"),
            totalBitrateKbps = int("tbr"),
            approxBytes = long("filesize") ?: long("filesize_approx"),
            audioLanguage = str("language")?.takeIf { kind == StreamKind.AUDIO_ONLY || hasAudio },
            audioTrackName = str("format_note")?.takeIf { kind == StreamKind.AUDIO_ONLY },
            isOriginalAudio = languagePreference != null && languagePreference >= 10,
            isDefaultAudio = languagePreference != null && languagePreference >= 10,
            isDrm = bool("has_drm") == true,
            httpHeaders = headers("http_headers"),
        )
    }

    private fun subtitle(language: String, value: kotlinx.serialization.json.JsonElement, auto: Boolean): SubtitleTrack? {
        val formats = value as? JsonArray ?: return null
        val chosen = formats.mapNotNull { it as? JsonObject }
            .firstOrNull { it.str("ext") in setOf("vtt", "srt", "json3", "ttml") }
            ?: return null
        return SubtitleTrack(
            languageTag = language,
            label = language,
            url = chosen.str("url") ?: return null,
            container = chosen.str("ext") ?: "vtt",
            isAutoGenerated = auto,
        )
    }

    private fun JsonObject.toMediaSummary(): MediaSummary {
        val rawUrl = str("url") ?: str("webpage_url").orEmpty()
        val id = str("id") ?: UrlCanon.videoId(rawUrl) ?: rawUrl
        val url = UrlCanon.videoId(rawUrl)?.let(UrlCanon::watchUrl) ?: rawUrl
        val duration = dbl("duration")?.toLong()?.takeIf { it > 0 }
        return MediaSummary(
            id = id,
            url = url,
            title = str("title").orEmpty(),
            channelId = str("channel_id") ?: str("uploader_id"),
            channelName = str("channel") ?: str("uploader"),
            channelUrl = str("channel_url") ?: str("uploader_url"),
            durationSeconds = duration,
            viewCount = long("view_count"),
            publishedAtEpochMs = publishedAt(this),
            publishedText = str("upload_date"),
            thumbnailUrl = bestThumbnail(this),
            isLive = bool("is_live") == true || str("live_status") == "is_live",
            isShort = url.contains("/shorts/"),
            availability = availabilityOf(str("availability")),
        )
    }

    private fun JsonObject.toPlaylistSummary(): PlaylistSummary {
        val url = str("url") ?: str("webpage_url").orEmpty()
        return PlaylistSummary(
            id = str("id") ?: UrlCanon.playlistId(url) ?: url,
            url = url,
            title = str("title").orEmpty(),
            channelName = str("channel") ?: str("uploader"),
            channelId = str("channel_id") ?: str("uploader_id"),
            itemCount = long("playlist_count")?.toInt(),
            thumbnailUrl = bestThumbnail(this),
            description = str("description"),
        )
    }

    private fun JsonObject.toChannelSummary(): ChannelSummary {
        val url = str("url") ?: str("channel_url").orEmpty()
        return ChannelSummary(
            id = str("channel_id") ?: str("id") ?: url,
            url = url,
            name = str("channel") ?: str("title").orEmpty(),
            avatarUrl = bestThumbnail(this),
            subscriberCount = long("channel_follower_count"),
            description = str("description"),
        )
    }

    private fun JsonObject.isPlaylistEntry(): Boolean {
        if (str("_type") == "playlist") return true
        val url = str("url") ?: str("webpage_url") ?: return false
        return url.contains("playlist?list=") || url.contains("/playlist")
    }

    private fun JsonObject.isChannelEntry(): Boolean {
        val url = str("url") ?: return false
        return (url.contains("/channel/") || url.contains("/@")) &&
            str("_type") == "url" &&
            UrlCanon.videoId(url) == null
    }

    // -- primitives -----------------------------------------------------------------

    private fun bestThumbnail(root: JsonObject): String? {
        val thumbnails = root.array("thumbnails").orEmpty()
            .mapNotNull { it as? JsonObject }
        val best = thumbnails.maxByOrNull { (it.int("height") ?: 0) * (it.int("width") ?: 0) }
        return best?.str("url") ?: root.str("thumbnail")
    }

    private fun publishedAt(root: JsonObject): Long? {
        root.dbl("timestamp")?.let { return (it * 1000).toLong() }
        root.dbl("release_timestamp")?.let { return (it * 1000).toLong() }
        val date = root.str("upload_date") ?: return null
        if (date.length != 8) return null
        return runCatching {
            LocalDate.of(
                date.substring(0, 4).toInt(),
                date.substring(4, 6).toInt(),
                date.substring(6, 8).toInt(),
            ).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()
    }

    private fun availabilityOf(value: String?): Availability = when (value?.lowercase()) {
        null, "public", "unlisted" -> Availability.AVAILABLE
        "private", "needs_auth", "login_required" -> Availability.PRIVATE
        "premium", "subscriber_only" -> Availability.MEMBERS_ONLY
        "unavailable", "needs_subscription" -> Availability.REMOVED
        "upcoming" -> Availability.UPCOMING
        "drm" -> Availability.DRM_PROTECTED
        "geo_restricted" -> Availability.GEO_BLOCKED
        else -> Availability.UNKNOWN
    }

    private fun providerOf(url: String): MediaProvider = MediaProvider.fromUrl(url)

    private fun deliveryOf(protocol: String?): DeliveryKind = when {
        protocol == null -> DeliveryKind.PROGRESSIVE
        protocol.startsWith("m3u8") -> DeliveryKind.HLS
        protocol.contains("dash") -> DeliveryKind.DASH
        protocol.startsWith("http") -> DeliveryKind.PROGRESSIVE
        else -> DeliveryKind.OTHER
    }

    /**
     * Fallback kind for a format yt-dlp never probed.
     *
     * A direct media URL comes back as one file with `vcodec: null` and no `acodec`, so
     * the codecs decide nothing. Audio containers are audio; everything else is treated
     * as one muxed file, because calling it video-only would mute it and dropping it
     * would leave the player with nothing to play.
     */
    private fun kindFromContainer(ext: String?): StreamKind? = when (ext?.lowercase()) {
        null, "" -> null
        in AUDIO_CONTAINERS -> StreamKind.AUDIO_ONLY
        else -> StreamKind.MUXED
    }

    private fun mimeOf(ext: String?): String? = when (ext?.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "opus", "ogg", "oga" -> "audio/ogg"
        "m3u8" -> "application/x-mpegURL"
        "mpd" -> "application/dash+xml"
        else -> null
    }

    private fun qualityLabel(
        height: Int?,
        fps: Int?,
        formatNote: String?,
        quality: String?,
    ): String? = when {
        height != null && fps != null && fps > 30 -> "${height}p$fps"
        height != null -> "${height}p"
        !formatNote.isNullOrBlank() -> formatNote
        !quality.isNullOrBlank() -> quality
        else -> null
    }

    private fun JsonObject.headers(key: String): Map<String, String> {
        val obj = this[key] as? JsonObject ?: return emptyMap()
        return obj.entries.mapNotNull { (name, value) ->
            val text = (value as? JsonPrimitive)?.contentOrNull
            if (text == null) null else name to text
        }.toMap()
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.dbl(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.int(key: String): Int? = dbl(key)?.toInt()

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    /** Containers that only ever carry audio; everything else is a whole file. */
    private val AUDIO_CONTAINERS = setOf(
        "m4a", "mp3", "aac", "opus", "ogg", "oga", "flac", "wav", "wma", "weba",
    )
}
