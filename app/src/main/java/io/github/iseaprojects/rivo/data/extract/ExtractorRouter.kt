package io.github.iseaprojects.rivo.data.extract

import io.github.iseaprojects.rivo.core.config.ConfigKeys
import io.github.iseaprojects.rivo.core.config.ConfigSnapshot
import io.github.iseaprojects.rivo.data.net.CookieProvider
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Chooses a backend, caches resolved media and re-resolves on expiry.
 *
 * Two rules drive the design:
 *
 *  1. **fast by default.** `CONFIG_EXTRACTOR=auto` sends YouTube URLs to NewPipe (no
 *     interpreter warm-up) and only falls back to yt-dlp for non-YouTube sites or when
 *     NewPipe reports a parser failure.
 *  2. **URLs rot.** YouTube's signed stream URLs expire in roughly six hours
 *     (`CONFIG_RESOLVE_MAX_AGE_HOURS` rewrites the threshold). [fresh] retries once with
 *     a forced re-extraction, which is what the player calls when a 403 shows up.
 */
class ExtractorRouter(
    private val config: () -> ConfigSnapshot,
    private val cook: CookieProvider,
    private val newPipe: VideoExtractor,
    private val ytDlpFactory: (CookieProvider, () -> ConfigSnapshot) -> VideoExtractor?,
) {

    private val ytDlp: VideoExtractor? by lazy {
        runCatching { ytDlpFactory(cook, config) }.getOrNull()
    }

    private val cache = ConcurrentHashMap<String, CachedDetails>()
    private val refreshLocks = ConcurrentHashMap<String, Mutex>()

    fun backends(): List<VideoExtractor> = listOfNotNull(newPipe, ytDlp)

    fun ytDlpAvailable(): Boolean = ytDlp != null

    /** The backend that list screens should use by default. */
    fun default(): VideoExtractor = when (configured()) {
        "newpipe", "fast" -> newPipe
        "ytdlp" -> ytDlp ?: newPipe
        else -> newPipe
    }

    fun forUrl(url: String): VideoExtractor {
        when (configured()) {
            "newpipe", "fast" -> return newPipe
            "ytdlp" -> return ytDlp ?: newPipe
        }
        // A bare 11-character id or any YouTube URL shape is YouTube; everything else
        // (SoundCloud, PeerTube, a random site) only yt-dlp can handle.
        if (UrlCanon.videoId(url) != null) return newPipe
        val host = runCatching { java.net.URI(url).host }.getOrNull()
        return if (UrlCanon.isYoutubeHost(host)) newPipe else ytDlp ?: newPipe
    }

    fun capabilities(): ExtractorCapabilities {
        // Only backends the router may actually hand a request to: a forced `newpipe`
        // build never calls yt-dlp, while `ytdlp` and `auto` can use both.
        val all = when (configured()) {
            "newpipe", "fast" -> listOf(newPipe.capabilities)
            else -> backends().map { it.capabilities }
        }
        val first = all.first()
        if (all.size == 1) return first
        // With both backends compiled in, a feature is offered when either can serve it:
        // NewPipe wins for YouTube lists and kiosks, yt-dlp adds chapters, account feeds
        // and non-YouTube sites. The router picks per URL at call time.
        return first.copy(
            search = all.any { it.search },
            searchFilters = all.any { it.searchFilters },
            suggestions = all.any { it.suggestions },
            channels = all.any { it.channels },
            channelTabs = all.any { it.channelTabs },
            playlists = all.any { it.playlists },
            kiosks = all.flatMap { it.kiosks }.toSet(),
            chapters = all.any { it.chapters },
            subtitles = all.any { it.subtitles },
            storyboards = all.any { it.storyboards },
            cookies = all.any { it.cookies },
            poToken = all.any { it.poToken },
            nonYouTubeSites = all.any { it.nonYouTubeSites },
            accountFeeds = all.any { it.accountFeeds },
        )
    }

    /**
     * Kiosk feeds always come from NewPipe: they are YouTube's own `/feed/...` URLs, which
     * yt-dlp does not extract. NewPipe also reports the retired Trending feed with a clear
     * message instead of a generic extraction error.
     */
    suspend fun kiosk(kiosk: Kiosk, token: ContinueToken?): MediaPage = newPipe.kiosk(kiosk, token)

    /**
     * Search with an optional `sp` filter.
     *
     * NewPipe is tried first because it needs no interpreter. It can only express the
     * videos/channels/playlists scopes, so a date/duration/feature/sort chip comes back as
     * UNSUPPORTED and is retried on yt-dlp when that backend is part of the build. In the
     * fast flavor the error reaches the screen with the reason intact instead of being
     * swallowed or silently unfiltered.
     */
    suspend fun search(query: String, sp: String?, token: ContinueToken?): MediaPage = try {
        newPipe.search(query, sp, token)
    } catch (error: ExtractorException) {
        val backup = ytDlp
        if (backup != null && (error.kind == FailureKind.UNSUPPORTED || shouldFallBack(error))) {
            backup.search(query, sp, token)
        } else {
            throw error
        }
    }

    /**
     * Channel-scoped search. NewPipeExtractor has no channel-search entry point, so the
     * request goes to yt-dlp whenever it is compiled in. A fast build (no yt-dlp) or a
     * `newpipe`-forced one receives the backend's own UNSUPPORTED message, which names
     * the extractor that is missing.
     */
    suspend fun channelSearch(
        channelUrl: String,
        query: String,
        token: ContinueToken?,
    ): MediaPage = when (configured()) {
        "newpipe" -> newPipe.channelSearch(channelUrl, query, token)
        else -> (ytDlp ?: newPipe).channelSearch(channelUrl, query, token)
    }

    /**
     * Account-only feeds.
     *
     * Three preconditions are checked here rather than in the backend, because each one has
     * a different fix and the screen should say which: the feature must be enabled
     * (`CONFIG_ACCOUNT_FEEDS`), a cookie set must exist, and a backend that can read the
     * page must be compiled in. The fast flavor then gets a message naming yt-dlp instead
     * of a generic extraction failure.
     */
    suspend fun accountFeed(feed: AccountFeed, token: ContinueToken?): MediaPage {
        if (!config().bool(ConfigKeys.ACCOUNT_FEEDS, false)) {
            throw ExtractorException(
                FailureKind.UNSUPPORTED,
                "account feeds are off; enable ${ConfigKeys.ACCOUNT_FEEDS} in the config",
            )
        }
        if (!cook.enabled || cook.count == 0) {
            throw ExtractorException(
                FailureKind.UNSUPPORTED,
                "the ${feed.name.lowercase()} feed needs cookies; import cookies.txt or sign in",
            )
        }
        val backend = when (configured()) {
            "newpipe", "fast" -> newPipe
            else -> ytDlp ?: newPipe
        }
        return backend.accountFeed(feed, token)
    }

    suspend fun warmUp(): Map<String, String?> = buildMap {
        backends().forEach { backend ->
            put(backend.id, runCatching { backend.warmUp() }.getOrNull())
        }
    }

    suspend fun video(idOrUrl: String, forceRefresh: Boolean = false): VideoDetails {
        val key = UrlCanon.videoId(idOrUrl) ?: idOrUrl
        if (!forceRefresh) {
            cached(key)?.let { return it }
        }
        requireBackendFor(idOrUrl)
        val lock = refreshLocks.getOrPut(key) { Mutex() }
        return lock.withLock {
            if (!forceRefresh) {
                cached(key)?.let { return@withLock it }
            }
            val backend = forUrl(idOrUrl)
            val details = try {
                backend.video(idOrUrl)
            } catch (error: ExtractorException) {
                // A parser break in NewPipe is exactly the case yt-dlp exists for.
                if (backend.id == NewPipeBackendId && shouldFallBack(error)) {
                    val backup = ytDlp ?: throw error
                    backup.video(idOrUrl)
                } else {
                    throw error
                }
            }
            cache[key] = CachedDetails(details, System.currentTimeMillis())
            details
        }
    }

    /** Forces a new extraction, e.g. after the player got HTTP 403 from the CDN. */
    suspend fun fresh(videoIdOrUrl: String): VideoDetails = video(videoIdOrUrl, forceRefresh = true)

    fun cached(videoIdOrUrl: String): VideoDetails? {
        val key = UrlCanon.videoId(videoIdOrUrl) ?: videoIdOrUrl
        val entry = cache[key] ?: return null
        val maxAge = config().long(ConfigKeys.RESOLVE_MAX_AGE_HOURS, 5)
            .coerceIn(1, 24) * 60L * 60L * 1000L
        val age = System.currentTimeMillis() - entry.resolvedAt
        if (age > maxAge) {
            cache.remove(key)
            return null
        }
        return entry.details
    }

    fun invalidate(videoIdOrUrl: String) {
        cache.remove(UrlCanon.videoId(videoIdOrUrl) ?: videoIdOrUrl)
    }

    fun clearCache() {
        cache.clear()
    }

    private fun configured(): String =
        config().string(ConfigKeys.EXTRACTOR, "auto").lowercase()

    /**
     * A non-YouTube URL in a build without yt-dlp.
     *
     * NewPipe answers "not a video URL" for a Vimeo link, which reads like the link is
     * broken. The truth is that the fast flavor only opens YouTube; saying so is the
     * difference between "try the other build" and "try another link".
     */
    private fun requireBackendFor(url: String) {
        if (ytDlp != null) return
        if (UrlCanon.videoId(url) != null) return
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return
        if (UrlCanon.isYoutubeHost(host)) return
        throw ExtractorException(
            FailureKind.UNSUPPORTED,
            "the fast build opens YouTube links only; the yt-dlp build adds non-YouTube sites",
        )
    }

    private fun shouldFallBack(error: ExtractorException): Boolean = when (error.kind) {
        // A parser break or an opaque failure is what a newer yt-dlp usually fixes.
        // Definitive verdicts (private, removed, age) are not worth a second extraction.
        FailureKind.EXTRACTOR_BROKEN, FailureKind.UNKNOWN, FailureKind.RATE_LIMITED -> true
        else -> false
    }

    private data class CachedDetails(val details: VideoDetails, val resolvedAt: Long)

    private companion object {
        const val NewPipeBackendId = "newpipe"
    }
}
