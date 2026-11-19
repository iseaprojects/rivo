package io.github.iseaprojects.rivo.data.extract

import io.github.iseaprojects.rivo.core.config.ConfigSnapshot
import io.github.iseaprojects.rivo.core.model.MediaSummary
import io.github.iseaprojects.rivo.data.net.CookieProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractorRouterTest {

    private val cookies = object : CookieProvider {
        override val enabled: Boolean = false
        override val count: Int = 0
        override fun cookieHeader(url: String): String? = null
        override fun exportNetscape(hostSuffix: String?): String? = null
    }

    private val newPipeCaps = ExtractorCapabilities(
        kiosks = setOf(Kiosk.MUSIC, Kiosk.LIVE),
        subtitles = true,
        storyboards = true,
        nonYouTubeSites = false,
        accountFeeds = false,
    )

    private val ytDlpCaps = ExtractorCapabilities(
        kiosks = emptySet(),
        chapters = true,
        cookies = true,
        poToken = true,
        nonYouTubeSites = true,
        accountFeeds = true,
    )

    private fun backend(
        id: String,
        caps: ExtractorCapabilities,
        onSearch: suspend (String, String?, ContinueToken?) -> MediaPage = { _, _, _ -> unsupported() },
        onChannelSearch: suspend (String, String) -> MediaPage = { _, _ -> unsupported() },
    ): VideoExtractor = object : VideoExtractor {
        override val id: String = id
        override val displayName: String = id
        override val capabilities: ExtractorCapabilities = caps
        override fun isAvailable(): Boolean = true
        override suspend fun warmUp(): String? = null
        override suspend fun video(idOrUrl: String): VideoDetails = unsupported()
        override suspend fun search(
            query: String,
            filters: String?,
            token: ContinueToken?,
        ): MediaPage = onSearch(query, filters, token)
        override suspend fun suggestions(query: String): List<String> = unsupported()
        override suspend fun channel(
            url: String,
            tab: ChannelTab?,
            token: ContinueToken?,
        ): ChannelResult = unsupported()
        override suspend fun channelSearch(
            channelUrl: String,
            query: String,
            token: ContinueToken?,
        ): MediaPage = onChannelSearch(channelUrl, query)
        override suspend fun playlist(url: String, token: ContinueToken?): PlaylistResult =
            unsupported()
        override suspend fun kiosk(kiosk: Kiosk, token: ContinueToken?): MediaPage = unsupported()
    }

    private fun unsupported(): Nothing = throw UnsupportedOperationException("not used")

    private fun video(id: String) = MediaSummary(
        id = id,
        url = "https://www.youtube.com/watch?v=$id",
        title = "Video $id",
    )

    private fun router(
        extractor: String?,
        withYtDlp: Boolean,
        newPipeSearch: suspend (String, String?, ContinueToken?) -> MediaPage =
            { _, _, _ -> unsupported() },
        ytDlpSearch: suspend (String, String?, ContinueToken?) -> MediaPage =
            { _, _, _ -> unsupported() },
        newPipeChannelSearch: suspend (String, String) -> MediaPage =
            { _, _ -> throw ExtractorException(FailureKind.UNSUPPORTED, "newpipe cannot") },
        ytDlpChannelSearch: suspend (String, String) -> MediaPage =
            { _, _ -> throw ExtractorException(FailureKind.UNSUPPORTED, "ytdlp cannot") },
    ) = ExtractorRouter(
        config = {
            ConfigSnapshot(
                if (extractor == null) emptyMap() else mapOf("CONFIG_EXTRACTOR" to extractor),
            )
        },
        cook = cookies,
        newPipe = backend("newpipe", newPipeCaps, newPipeSearch, newPipeChannelSearch),
        ytDlpFactory = { _, _ ->
            if (withYtDlp) {
                backend("ytdlp", ytDlpCaps, ytDlpSearch, ytDlpChannelSearch)
            } else {
                null
            }
        },
    )

    @Test
    fun `auto merges both capability sets`() {
        val caps = router(null, withYtDlp = true).capabilities()

        assertEquals(setOf(Kiosk.MUSIC, Kiosk.LIVE), caps.kiosks)
        assertTrue(caps.chapters)
        assertTrue(caps.nonYouTubeSites)
        assertTrue(caps.accountFeeds)
        assertTrue(caps.cookies)
    }

    @Test
    fun `forced newpipe hides yt-dlp-only features`() {
        val caps = router("newpipe", withYtDlp = true).capabilities()

        assertEquals(setOf(Kiosk.MUSIC, Kiosk.LIVE), caps.kiosks)
        // The router never sends a forced-newpipe URL to yt-dlp, so those features stay off.
        assertFalse(caps.chapters)
        assertFalse(caps.nonYouTubeSites)
    }

    @Test
    fun `fast flavor without yt-dlp reports newpipe only`() {
        val caps = router(null, withYtDlp = false).capabilities()

        assertEquals(setOf(Kiosk.MUSIC, Kiosk.LIVE), caps.kiosks)
        assertFalse(caps.chapters)
        assertFalse(caps.accountFeeds)
    }

    @Test
    fun `a filter newpipe cannot express falls back to yt-dlp`() = runBlocking {
        val filter = "EgIIAg%253D%253D"
        val page = MediaPage(items = listOf(video("v1")))
        val router = router(
            extractor = "auto",
            withYtDlp = true,
            newPipeSearch = { _, sp, _ ->
                if (sp == filter) {
                    throw ExtractorException(FailureKind.UNSUPPORTED, "needs yt-dlp")
                }
                unsupported()
            },
            ytDlpSearch = { _, sp, _ ->
                assertEquals(filter, sp)
                page
            },
        )

        val result = router.search("lofi", filter, null)
        assertEquals(listOf("v1"), result.items.map { it.id })
    }

    @Test
    fun `the fast flavor surfaces the unsupported filter instead of an empty page`() = runBlocking {
        val router = router(
            extractor = "auto",
            withYtDlp = false,
            newPipeSearch = { _, _, _ ->
                throw ExtractorException(FailureKind.UNSUPPORTED, "needs yt-dlp")
            },
        )

        val error = runCatching { router.search("lofi", "EgIIAg%253D%253D", null) }
            .exceptionOrNull()
        assertTrue(error is ExtractorException)
        assertEquals(FailureKind.UNSUPPORTED, (error as ExtractorException).kind)
    }

    @Test
    fun `the fast flavor points non-YouTube links at the yt-dlp build`() = runBlocking {
        val router = router(null, withYtDlp = false)

        val error = runCatching { router.video("https://vimeo.com/76979871") }
            .exceptionOrNull()

        assertTrue(error is ExtractorException)
        val failure = error as ExtractorException
        assertEquals(FailureKind.UNSUPPORTED, failure.kind)
        assertTrue(failure.message.orEmpty().contains("yt-dlp build"))
    }

    @Test
    fun `a working newpipe search never touches yt-dlp`() = runBlocking {
        val router = router(
            extractor = "auto",
            withYtDlp = true,
            newPipeSearch = { _, _, _ -> MediaPage(items = listOf(video("fast"))) },
            ytDlpSearch = { _, _, _ -> error("yt-dlp must not be used") },
        )

        assertEquals(listOf("fast"), router.search("lofi", null, null).items.map { it.id })
    }

    @Test
    fun `channel search always reaches yt-dlp when it is compiled in`() = runBlocking {
        val page = MediaPage(items = listOf(video("inside")))
        val router = router(
            extractor = "auto",
            withYtDlp = true,
            newPipeChannelSearch = { _, _ -> error("NewPipe has no channel search") },
            ytDlpChannelSearch = { url, query ->
                assertEquals("https://www.youtube.com/@lofigirl", url)
                assertEquals("jazz", query)
                page
            },
        )

        val result = router.channelSearch("https://www.youtube.com/@lofigirl", "jazz", null)
        assertEquals(listOf("inside"), result.items.map { it.id })
    }

    @Test
    fun `a forced newpipe build reports channel search as unsupported`() = runBlocking {
        val router = router(extractor = "newpipe", withYtDlp = true)

        val error = runCatching {
            router.channelSearch("https://www.youtube.com/@lofigirl", "jazz", null)
        }.exceptionOrNull()
        assertTrue(error is ExtractorException)
        assertEquals(FailureKind.UNSUPPORTED, (error as ExtractorException).kind)
    }

    @Test
    fun `the fast flavor reports channel search as unsupported`() = runBlocking {
        val router = router(extractor = null, withYtDlp = false)

        val error = runCatching {
            router.channelSearch("https://www.youtube.com/@lofigirl", "jazz", null)
        }.exceptionOrNull()
        assertTrue(error is ExtractorException)
        assertEquals(FailureKind.UNSUPPORTED, (error as ExtractorException).kind)
    }
}
