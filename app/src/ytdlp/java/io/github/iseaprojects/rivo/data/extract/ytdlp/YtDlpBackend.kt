package io.github.iseaprojects.rivo.data.extract.ytdlp

import android.content.Context
import io.github.iseaprojects.rivo.core.config.ConfigKeys
import io.github.iseaprojects.rivo.core.config.ConfigSnapshot
import io.github.iseaprojects.rivo.data.extract.AccountFeed
import io.github.iseaprojects.rivo.data.extract.ChannelResult
import io.github.iseaprojects.rivo.data.extract.ContinueToken
import io.github.iseaprojects.rivo.data.extract.ExtractorCapabilities
import io.github.iseaprojects.rivo.data.extract.ExtractorException
import io.github.iseaprojects.rivo.data.extract.ExtractorFailures
import io.github.iseaprojects.rivo.data.extract.FailureKind
import io.github.iseaprojects.rivo.data.extract.Kiosk
import io.github.iseaprojects.rivo.data.extract.MediaPage
import io.github.iseaprojects.rivo.data.extract.PlaylistResult
import io.github.iseaprojects.rivo.data.extract.UrlCanon
import io.github.iseaprojects.rivo.data.extract.VideoDetails
import io.github.iseaprojects.rivo.data.extract.VideoExtractor
import io.github.iseaprojects.rivo.data.extract.YtdlpChannels
import io.github.iseaprojects.rivo.data.net.CookieProvider
import io.github.iseaprojects.rivo.data.net.HttpClients
import io.github.iseaprojects.rivo.data.net.PoTokenConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest

/**
 * yt-dlp backend, available only in the `ytdlp` flavor.
 *
 * The bundled interpreter is yt-dlp 2025.11.12 (the library is named
 * `youtubedl-android` for historical reasons; it ships yt-dlp, not the abandoned
 * youtube-dl). yt-dlp's release cadence outpaces any APK, so the backend also exposes
 * [update] and the app can refresh the extracted binary without a store release.
 *
 * Extraction uses `--dump-single-json`, i.e. the same JSON yt-dlp prints for `-J`, and
 * [YtdlpJsonMapper] turns it into the app's models. Keeping the mapper pure is what makes
 * format changes testable without the Python runtime.
 */
class YtDlpBackend(
    private val context: Context,
    private val cookies: CookieProvider,
    private val config: () -> ConfigSnapshot,
) : VideoExtractor {

    override val id: String = ID
    override val displayName: String = "yt-dlp"

    override val capabilities: ExtractorCapabilities = ExtractorCapabilities(
        search = true,
        searchFilters = true,
        suggestions = true,
        channels = true,
        channelTabs = true,
        playlists = true,
        // Kiosks are YouTube `/feed/...` URLs, which yt-dlp does not extract. The router
        // serves them from NewPipe instead (and reports the retired Trending feed there).
        kiosks = emptySet(),
        chapters = true,
        subtitles = true,
        storyboards = true,
        cookies = true,
        poToken = true,
        nonYouTubeSites = true,
        accountFeeds = true,
    )

    private val initialized = AtomicBoolean(false)
    private val initLock = Mutex()

    override fun isAvailable(): Boolean = true

    override suspend fun warmUp(): String? {
        ensureInitialized()
        return runCatching { YoutubeDL.getInstance().versionName(context) }.getOrNull()
    }

    override suspend fun video(idOrUrl: String): VideoDetails {
        val url = UrlCanon.videoId(idOrUrl)?.let(UrlCanon::watchUrl) ?: idOrUrl
        val request = baseRequest(url, flat = false).apply {
            // A watch URL may carry a playlist id; jailbreak it so a single video stays
            // a single video instead of dragging in a 500-entry playlist.
            addOption("--no-playlist")
        }
        return YtdlpJsonMapper.video(execute(request))
    }

    override suspend fun search(
        query: String,
        filters: String?,
        token: ContinueToken?,
    ): MediaPage {
        val start = startIndex(token)
        val limit = perPage()
        val request = baseRequest(UrlCanon.searchUrl(query, filters), flat = true).apply {
            addOption("--playlist-start", start + 1)
            addOption("--playlist-end", start + limit)
        }
        val page = YtdlpJsonMapper.list(execute(request))
        return page.copy(nextToken = nextToken(page, start, limit))
    }

    override suspend fun suggestions(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val encoded = UrlCanon.searchUrl(query).substringAfter("search_query=")
            .substringBefore('&')
        val url = "https://suggestqueries.google.com/complete/search" +
            "?client=firefox&ds=yt&q=$encoded"
        val request = Request.Builder().url(url).get().build()
        return withContext(Dispatchers.IO) {
            runCatching {
                HttpClients.shared.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyList()
                    YtdlpJsonMapper.suggestions(response.body.string())
                }
            }.getOrDefault(emptyList())
        }
    }

    override suspend fun channel(
        url: String,
        tab: io.github.iseaprojects.rivo.data.extract.ChannelTab?,
        token: ContinueToken?,
    ): ChannelResult {
        val base = UrlCanon.channelUrl(url) ?: url
        val target = tab?.url ?: "$base/videos"
        val start = startIndex(token)
        val limit = perPage()
        val request = baseRequest(target, flat = true).apply {
            addOption("--playlist-start", start + 1)
            addOption("--playlist-end", start + limit)
        }
        val result = YtdlpJsonMapper.channel(execute(request), target)
        return result.copy(page = result.page.copy(nextToken = nextToken(result.page, start, limit)))
    }

    /**
     * Channel search is the channel's Search tab: yt-dlp's `YoutubeTabIE` handles the
     * `/search?query=` URL like any other tab, so paging is the same start/end window
     * used by [channel].
     */
    override suspend fun channelSearch(
        channelUrl: String,
        query: String,
        token: ContinueToken?,
    ): MediaPage {
        val target = UrlCanon.channelSearchUrl(channelUrl, query)
        val start = startIndex(token)
        val limit = perPage()
        val request = baseRequest(target, flat = true).apply {
            addOption("--playlist-start", start + 1)
            addOption("--playlist-end", start + limit)
        }
        val page = YtdlpJsonMapper.list(execute(request))
        return page.copy(nextToken = nextToken(page, start, limit))
    }

    override suspend fun playlist(url: String, token: ContinueToken?): PlaylistResult {
        val target = UrlCanon.playlistId(url)
            ?.let { "https://www.youtube.com/playlist?list=$it" }
            ?: url
        val start = startIndex(token)
        val limit = perPage()
        val request = baseRequest(target, flat = true).apply {
            addOption("--playlist-start", start + 1)
            addOption("--playlist-end", start + limit)
        }
        val result = YtdlpJsonMapper.playlist(execute(request), target)
        return result.copy(page = result.page.copy(nextToken = nextToken(result.page, start, limit)))
    }

    override suspend fun kiosk(kiosk: Kiosk, token: ContinueToken?): MediaPage {
        val url = kiosk.url
            ?: throw ExtractorException(FailureKind.UNSUPPORTED, "kiosk ${kiosk.name} has no feed URL")
        val start = startIndex(token)
        val limit = perPage()
        val request = baseRequest(url, flat = true).apply {
            addOption("--playlist-start", start + 1)
            addOption("--playlist-end", start + limit)
        }
        val page = YtdlpJsonMapper.list(execute(request))
        return page.copy(nextToken = nextToken(page, start, limit))
    }

    /**
     * Account feeds are ordinary tab pages for yt-dlp: the difference is the cookie jar,
     * which [baseRequest] already attaches when a set is configured. `--flat-playlist`
     * keeps the response to one metadata row per entry.
     */
    override suspend fun accountFeed(feed: AccountFeed, token: ContinueToken?): MediaPage {
        val start = startIndex(token)
        val limit = perPage()
        val request = baseRequest(feed.url, flat = true).apply {
            addOption("--playlist-start", start + 1)
            addOption("--playlist-end", start + limit)
        }
        val page = YtdlpJsonMapper.list(execute(request))
        return page.copy(nextToken = nextToken(page, start, limit))
    }

    /** Self-update; the caller decides how often to try. */
    suspend fun update(channel: String): String? {
        ensureInitialized()
        val target = YoutubeDL.UpdateChannel(YtdlpChannels.apiUrl(channel))
        val status = withContext(Dispatchers.IO) {
            YoutubeDL.getInstance().updateYoutubeDL(context, target)
        }
        return status?.name
    }

    fun version(): String? = runCatching { YoutubeDL.getInstance().versionName(context) }.getOrNull()

    // -- plumbing -------------------------------------------------------------------

    private suspend fun ensureInitialized() {
        if (initialized.get()) return
        initLock.withLock {
            if (initialized.get()) return
            withContext(Dispatchers.IO) {
                try {
                    YoutubeDL.getInstance().init(context)
                    initialized.set(true)
                } catch (error: YoutubeDLException) {
                    throw ExtractorException(
                        FailureKind.EXTRACTOR_BROKEN,
                        "yt-dlp failed to start: ${error.message}",
                        error,
                    )
                }
            }
        }
    }

    private fun baseRequest(url: String, flat: Boolean): YoutubeDLRequest =
        YoutubeDLRequest(url).apply {
            addOption("--dump-single-json")
            addOption("--no-warnings")
            addOption("--socket-timeout", "15")
            addOption("--retries", "2")
            if (flat) addOption("--flat-playlist")

            if (cookies.enabled && cookies.count > 0) {
                cookiesFile()?.let { addOption("--cookies", it.absolutePath) }
            }

            PoTokenConfig.parse(config().string(ConfigKeys.PO_TOKEN))
                ?.let(PoTokenConfig::extractorArgs)
                ?.let { addOption("--extractor-args", it) }

            applyUserOptions(this)
        }

    private suspend fun execute(request: YoutubeDLRequest): String {
        ensureInitialized()
        val response = try {
            withContext(Dispatchers.IO) { YoutubeDL.getInstance().execute(request) }
        } catch (cancelled: YoutubeDL.CanceledException) {
            throw CancellationException("yt-dlp download cancelled")
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ExtractorException(FailureKind.TIMEOUT, "yt-dlp was interrupted", interrupted)
        } catch (error: YoutubeDLException) {
            throw ExtractorFailures.fromYtDlpMessage(error.message.orEmpty())
        }

        if (response.exitCode != 0) {
            throw ExtractorFailures.fromYtDlpMessage(response.err.orEmpty(), response.exitCode)
        }
        return response.out.orEmpty()
    }

    private fun applyUserOptions(request: YoutubeDLRequest) {
        val tokens = CommandLine.split(config().string(ConfigKeys.YT_DLP_OPTS))
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (!token.startsWith("-")) {
                index++
                continue
            }
            val next = tokens.getOrNull(index + 1)
            if (next != null && !next.startsWith("-")) {
                runCatching { request.addOption(token, next) }
                index += 2
            } else {
                runCatching { request.addOption(token) }
                index++
            }
        }
    }

    /**
     * yt-dlp wants a Netscape file on disk. It is written to the app cache (never shared)
     * from the encrypted store, and only when the cookie set is non-empty.
     */
    private fun cookiesFile(): File? {
        val text = cookies.exportNetscape(null) ?: return null
        val file = File(context.cacheDir, "rivo-cookies.txt")
        return runCatching {
            file.writeText(text)
            file
        }.getOrNull()
    }

    private fun perPage(): Int = config().int(ConfigKeys.PER_PAGE, 30).coerceIn(5, 100)

    private fun startIndex(token: ContinueToken?): Int {
        if (token == null || token.backend != ID) return 0
        return token.payload.toIntOrNull()?.coerceAtLeast(0) ?: 0
    }

    private fun nextToken(page: MediaPage, start: Int, limit: Int): ContinueToken? {
        val returned = page.items.size + page.playlists.size + page.channels.size
        if (returned < limit) return null
        return ContinueToken(ID, (start + limit).toString())
    }

    companion object {
        const val ID = "ytdlp"
    }
}
