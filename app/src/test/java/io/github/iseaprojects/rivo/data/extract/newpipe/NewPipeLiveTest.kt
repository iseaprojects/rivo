package io.github.iseaprojects.rivo.data.extract.newpipe

import io.github.iseaprojects.rivo.data.extract.ExtractorException
import io.github.iseaprojects.rivo.data.extract.FailureKind
import io.github.iseaprojects.rivo.data.extract.Kiosk
import io.github.iseaprojects.rivo.data.net.CookieProvider
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in integration check against the live site.
 *
 * The rest of the suite is offline and deterministic. This one exists because the mapping
 * code cannot be proven by fixtures alone: a renamed YouTube field would keep every unit
 * test green and break the app. Run it explicitly:
 *
 * ```
 * RIVO_LIVE=1 ./gradlew :app:testFastDebugUnitTest --tests '*NewPipeLiveTest'
 * ```
 */
class NewPipeLiveTest {

    private val cookies = object : CookieProvider {
        override val enabled: Boolean = false
        override val count: Int = 0
        override fun cookieHeader(url: String): String? = null
        override fun exportNetscape(hostSuffix: String?): String? = null
    }

    private val backend = NewPipeBackend(
        cookies = cookies,
        poToken = { null },
        locale = { Locale.US },
    )

    @Test
    fun `resolves a public video into playable formats`() = runBlocking {
        assumeTrue(System.getenv("RIVO_LIVE") == "1")

        val details = backend.video("dQw4w9WgXcQ")

        println(
            "video: ${details.summary.title} | formats=${details.formats.size} " +
                "| subs=${details.subtitles.size} | hls=${details.hlsManifestUrl != null}",
        )
        assertTrue(details.summary.title.isNotBlank())
        assertFalse(details.formats.isEmpty())
        assertTrue(details.formats.any { it.isPlayable })
        assertTrue(details.bestAudio() != null)
        assertTrue(details.bestMuxed() != null || details.bestVideoOnly() != null)
    }

    @Test
    fun `search returns a page with a working continuation`() = runBlocking {
        assumeTrue(System.getenv("RIVO_LIVE") == "1")

        val first = backend.search("lofi", null, null)
        println("search: items=${first.items.size} next=${first.nextToken != null}")
        assertFalse(first.items.isEmpty())
        assertTrue(first.items.all { it.title.isNotBlank() })
        assertTrue(first.items.any { !it.thumbnailUrl.isNullOrBlank() })
        assertTrue(first.nextToken != null)

        // Proves the serialised Page round-trips through a real continuation token.
        val second = backend.search("lofi", null, first.nextToken)
        val overlap = second.items.map { it.id }.intersect(first.items.map { it.id }.toSet())
        println(
            "search page 2: items=${second.items.size} overlap=$overlap " +
                second.items.filter { it.id in overlap }.map { "${it.id}='${it.title}' ${it.url}" },
        )
        assertFalse(second.items.isEmpty())
        // YouTube occasionally repeats a result at a page boundary; the page must still be
        // mostly new material rather than the first page again.
        assertTrue(overlap.size < second.items.size)
        assertTrue(second.items.size >= first.items.size - 2)
    }

    @Test
    fun `search honours the sp scope codes and rejects the rest`() = runBlocking {
        assumeTrue(System.getenv("RIVO_LIVE") == "1")

        // The default VIDEOS code is what the chip row sends on an unfiltered search.
        val videos = backend.search("lofi", "EgIQAQ%253D%253D", null)
        println("search sp=videos: items=${videos.items.size}")
        assertFalse(videos.items.isEmpty())

        // NewPipe's handler takes content-filter names, so only the three scope codes can
        // be expressed; a date chip has to go to yt-dlp. The error must say so instead of
        // returning an empty page.
        val today = runCatching { backend.search("lofi", "EgIIAg%253D%253D", null) }
        val error = today.exceptionOrNull()
        println("search sp=today: ${error?.message}")
        assertTrue(error is ExtractorException)
        assertEquals(FailureKind.UNSUPPORTED, (error as ExtractorException).kind)
        assertTrue(error.message!!.contains("yt-dlp"))
    }

    @Test
    fun `channel exposes tabs and videos`() = runBlocking {
        assumeTrue(System.getenv("RIVO_LIVE") == "1")

        val result = backend.channel("@MrBeast", null, null)

        println(
            "channel: ${result.details.summary.name} " +
                "| tabs=${result.details.tabs.size} | items=${result.page.items.size}",
        )
        assertTrue(result.details.summary.name.isNotBlank())
        assertFalse(result.details.tabs.isEmpty())
        assertFalse(result.page.items.isEmpty())
        assertTrue(result.page.items.all { it.channelId != null })
    }

    @Test
    fun `music kiosk returns items`() = runBlocking {
        assumeTrue(System.getenv("RIVO_LIVE") == "1")

        // charts.youtube.com intermittently returns an empty grid; one retry keeps the test
        // about the mapping rather than about the feed's mood.
        var page = backend.kiosk(Kiosk.MUSIC, null)
        if (page.isEmpty) page = backend.kiosk(Kiosk.MUSIC, null)

        println("music kiosk: items=${page.items.size}")
        assertFalse(page.isEmpty)
    }

    @Test
    fun `retired trending feed has a truthful error`() = runBlocking {
        assumeTrue(System.getenv("RIVO_LIVE") == "1")

        assertFalse(Kiosk.TRENDING in backend.capabilities.kiosks)

        val error = runCatching { backend.kiosk(Kiosk.TRENDING, null) }.exceptionOrNull()
        assertTrue(error is ExtractorException)
        assertEquals(FailureKind.REMOVED, (error as ExtractorException).kind)
        assertTrue(error.message!!.contains("Trending", ignoreCase = true))
    }

    @Test
    fun `description chapters become closed chapter intervals`() = runBlocking {
        assumeTrue(System.getenv("RIVO_LIVE") == "1")

        // 3Blue1Brown timestamps the description; the chapters panel is what NewPipe parses.
        val details = backend.video("aircAruvnKk")

        println(
            "chapters: " + details.chapters.joinToString { "${it.startMs}-${it.endMs} ${it.title}" },
        )
        assertTrue(details.chapters.size >= 5)
        assertTrue(details.chapters.all { it.title.isNotBlank() })
        assertTrue(details.chapters.zipWithNext().all { (a, b) -> a.endMs == b.startMs })
        assertEquals(details.summary.durationSeconds!! * 1000, details.chapters.last().endMs)
    }
}
