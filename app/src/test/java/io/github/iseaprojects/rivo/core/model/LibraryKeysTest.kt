package io.github.iseaprojects.rivo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryKeysTest {

    private fun video(id: String = "abc", url: String = "https://www.youtube.com/watch?v=abc") =
        MediaSummary(id = id, url = url, title = "Video")

    @Test
    fun `video keys carry the provider`() {
        assertEquals("youtube:abc", LibraryKeys.video(video()))
        assertEquals(
            "peertube:abc",
            LibraryKeys.video(video().copy(provider = MediaProvider.PEERTUBE)),
        )
        assertNotEquals(
            LibraryKeys.video(video()),
            LibraryKeys.video(video().copy(provider = MediaProvider.OTHER)),
        )
    }

    @Test
    fun `a video without an id falls back to its url`() {
        val key = LibraryKeys.video(video(id = "", url = "https://example.com/v/1"))
        assertEquals("youtube:https://example.com/v/1", key)
    }

    @Test
    fun `channel keys prefer the channel id over the url`() {
        assertEquals(
            "channel:UC1",
            LibraryKeys.channel("UC1", "https://www.youtube.com/@chill"),
        )
        assertEquals(
            "channel:UC2",
            LibraryKeys.channel(null, "https://www.youtube.com/channel/UC2/videos"),
        )
    }

    @Test
    fun `a handle-only channel key is the lowercased url`() {
        assertEquals(
            "channel:https://www.youtube.com/@chill",
            LibraryKeys.channel(null, "https://www.youtube.com/@Chill/"),
        )
    }

    @Test
    fun `a video uploader and the channel page resolve to one key`() {
        val fromVideo = LibraryKeys.channel(
            channelId = "UC1",
            url = "https://www.youtube.com/channel/UC1",
        )
        val fromPage = LibraryKeys.channel(
            channelId = null,
            url = "https://www.youtube.com/channel/UC1/videos",
        )
        assertEquals(fromVideo, fromPage)
    }

    @Test
    fun `playlists are namespaced away from videos`() {
        val playlist = PlaylistSummary(id = "abc", url = "https://x", title = "P")
        assertEquals("youtube:playlist:abc", LibraryKeys.playlist(playlist))
        assertNotEquals(LibraryKeys.video(video()), LibraryKeys.playlist(playlist))
    }

    @Test
    fun `search keys fold case and trim`() {
        assertEquals("lofi", LibraryKeys.query("  LoFi  "))
        assertEquals(LibraryKeys.query("Lo-fi"), LibraryKeys.query("lo-fi"))
    }

    @Test
    fun `recall accepts one-based indexes only`() {
        assertEquals(0, LibraryKeys.recallIndex("!1"))
        assertEquals(4, LibraryKeys.recallIndex(" !5 "))
        assertNull(LibraryKeys.recallIndex("!"))
        assertNull(LibraryKeys.recallIndex("!0"))
        assertNull(LibraryKeys.recallIndex("!-1"))
        assertNull(LibraryKeys.recallIndex("!a"))
        assertNull(LibraryKeys.recallIndex("lo-fi"))
    }
}
