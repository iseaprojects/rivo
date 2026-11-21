package io.github.iseaprojects.rivo.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The download layout is yt-x's, and these tests are the contract: a playlist download
 * lands under `video/<Playlist>/<Channel>/`, an individual one under
 * `video/individual/<Channel>/`, and no title can escape its folder or break SAF.
 */
class DownloadNamingTest {

    private fun task(
        title: String = "Lo-fi beats",
        kind: DownloadKind = DownloadKind.VIDEO,
        channel: String? = "Chill",
        playlist: String? = null,
    ) = DownloadTask(
        videoId = "abc123",
        url = "https://www.youtube.com/watch?v=abc123",
        title = title,
        kind = kind,
        channel = channel,
        playlist = playlist,
    )

    @Test
    fun `individual video goes under video individual channel`() {
        assertEquals("video/individual/Chill", DownloadNaming.directory(task()))
    }

    @Test
    fun `audio goes under audio and keeps the same channel`() {
        assertEquals(
            "audio/individual/Chill",
            DownloadNaming.directory(task(kind = DownloadKind.AUDIO)),
        )
    }

    @Test
    fun `playlist video is nested under the playlist`() {
        assertEquals(
            "video/Roadtrip Mix/Chill",
            DownloadNaming.directory(task(playlist = "Roadtrip Mix")),
        )
    }

    @Test
    fun `playlist audio is nested under the playlist too`() {
        assertEquals(
            "audio/Roadtrip Mix/Chill",
            DownloadNaming.directory(task(kind = DownloadKind.AUDIO, playlist = "Roadtrip Mix")),
        )
    }

    @Test
    fun `missing channel becomes a readable placeholder`() {
        assertEquals(
            "video/individual/Unknown channel",
            DownloadNaming.directory(task(channel = null)),
        )
    }

    @Test
    fun `file name uses the title and the container extension`() {
        assertEquals(
            "Lo-fi beats.mp4",
            DownloadNaming.fileName(task(), "MP4"),
        )
    }

    @Test
    fun `enumeration prefixes the position inside the folder`() {
        assertEquals(
            "07 - Lo-fi beats.mp4",
            DownloadNaming.fileName(task(), "mp4", enumerate = 7),
        )
    }

    @Test
    fun `collision suffix sits before the extension`() {
        assertEquals(
            "Lo-fi beats [abc123].mp4",
            DownloadNaming.fileName(task(), "mp4", suffix = " [abc123]"),
        )
    }

    @Test
    fun `blank title falls back to the video id`() {
        assertEquals("abc123.mp4", DownloadNaming.fileName(task(title = "  ..  "), "mp4"))
    }

    @Test
    fun `sanitizing removes every character SAF or Windows rejects`() {
        val cleaned = DownloadNaming.sanitize("a/b\\c:d*e?f\"g<h>i|j")
        assertFalse(cleaned.contains('/'))
        assertFalse(cleaned.contains('\\'))
        for (char in listOf(':', '*', '?', '"', '<', '>', '|')) {
            assertFalse("$char survived: $cleaned", cleaned.contains(char))
        }
        assertEquals("a_b_c_d_e_f_g_h_i_j", cleaned)
    }

    @Test
    fun `control characters become spaces and runs collapse`() {
        assertEquals("one two", DownloadNaming.sanitize("one\u0000\u0001   two"))
    }

    @Test
    fun `trailing dots and surrounding whitespace are trimmed`() {
        assertEquals("Title", DownloadNaming.sanitize("  Title... "))
    }

    @Test
    fun `reserved names sanitize to empty`() {
        assertEquals("", DownloadNaming.sanitize("."))
        assertEquals("", DownloadNaming.sanitize(".."))
        assertEquals("", DownloadNaming.sanitize("   "))
    }

    @Test
    fun `long titles are capped by bytes so the folder prefix still fits`() {
        val name = DownloadNaming.sanitize("x".repeat(300))
        assertEquals(180, name.length)
        assertEquals(180, name.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `a CJK title is capped by bytes, not characters`() {
        // 300 characters of three bytes each: a 100-character cap would leave 300 bytes.
        val name = DownloadNaming.sanitize("漢".repeat(300))
        assertEquals(60, name.length)
        assertEquals(180, name.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `an emoji is never cut in half`() {
        val name = DownloadNaming.sanitize("\uD83D\uDE00".repeat(100))
        assertEquals(180, name.toByteArray(Charsets.UTF_8).size)
        assertTrue(name.length % 2 == 0)
    }

    @Test
    fun `a taken name falls back to the id and then to a counter`() {
        val taken = setOf("Lo-fi beats.mp4", "Lo-fi beats [abc123].mp4")
        assertEquals(
            "Lo-fi beats [abc123] (2).mp4",
            DownloadNaming.uniqueName(task(), "mp4", enumerate = null, existing = taken),
        )
        assertEquals(
            "Lo-fi beats [abc123] (3).mp4",
            DownloadNaming.uniqueName(
                task(),
                "mp4",
                enumerate = null,
                existing = taken + "Lo-fi beats [abc123] (2).mp4",
            ),
        )
    }

    @Test
    fun `a free name is left alone`() {
        assertEquals(
            "Lo-fi beats.mp4",
            DownloadNaming.uniqueName(task(), "mp4", enumerate = null, existing = emptySet()),
        )
        assertEquals(
            "02 - Lo-fi beats.mp4",
            DownloadNaming.uniqueName(
                task(),
                "mp4",
                enumerate = 2,
                existing = setOf("01 - Other.mp4"),
            ),
        )
    }

    @Test
    fun `extension prefers the MIME type over the container`() {
        assertEquals("mp4", DownloadNaming.extensionFor("video/mp4", "webm"))
        assertEquals("weba", DownloadNaming.extensionFor("audio/webm", null))
        assertEquals("mkv", DownloadNaming.extensionFor(null, "mkv"))
        assertEquals("mp4", DownloadNaming.extensionFor(null, "whatever"))
        assertEquals("mp4", DownloadNaming.extensionFor(null, null))
    }

    @Test
    fun `mime lookup matches the extension table`() {
        assertEquals("video/mp4", DownloadNaming.mimeFor("a.MP4"))
        assertEquals("audio/mp4", DownloadNaming.mimeFor("a.m4a"))
        assertEquals("audio/ogg", DownloadNaming.mimeFor("a.opus"))
        assertEquals(null, DownloadNaming.mimeFor("a.unknown"))
    }

    @Test
    fun `audio containers are recognised as audio files`() {
        assertTrue(DownloadNaming.kindFor("a.m4a") == DownloadKind.AUDIO)
        assertTrue(DownloadNaming.kindFor("a.mp3") == DownloadKind.AUDIO)
        assertTrue(DownloadNaming.kindFor("a.mp4") == DownloadKind.VIDEO)
    }

    @Test
    fun `default folder is the yt-x one`() {
        assertEquals("Movies/Rivo", DownloadNaming.DEFAULT_FOLDER)
    }
}
