package io.github.iseaprojects.rivo.data.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlCanonTest {

    private val id = "dQw4w9WgXcQ"

    @Test
    fun `watch url with playlist keeps the video id`() {
        assertEquals(
            id,
            UrlCanon.videoId("https://www.youtube.com/watch?v=$id&list=PL123&index=4"),
        )
    }

    @Test
    fun `short link with timestamp`() {
        assertEquals(id, UrlCanon.videoId("https://youtu.be/$id?t=42"))
    }

    @Test
    fun `shorts live and embed paths`() {
        assertEquals(id, UrlCanon.videoId("https://www.youtube.com/shorts/$id"))
        assertEquals(id, UrlCanon.videoId("https://m.youtube.com/live/$id?feature=share"))
        assertEquals(id, UrlCanon.videoId("https://www.youtube-nocookie.com/embed/$id"))
    }

    @Test
    fun `bare id and music host`() {
        assertEquals(id, UrlCanon.videoId(id))
        assertEquals(id, UrlCanon.videoId("https://music.youtube.com/watch?v=$id"))
    }

    @Test
    fun `channel and playlist urls are not videos`() {
        assertNull(UrlCanon.videoId("https://www.youtube.com/@SomeOne"))
        assertNull(UrlCanon.videoId("https://www.youtube.com/playlist?list=PL123"))
        assertNull(UrlCanon.videoId("https://vimeo.com/12345"))
        assertNull(UrlCanon.videoId(""))
    }

    @Test
    fun `playlist id extraction`() {
        assertEquals(
            "PL123",
            UrlCanon.playlistId("https://www.youtube.com/watch?v=$id&list=PL123"),
        )
        assertEquals("PL123", UrlCanon.playlistId("https://www.youtube.com/playlist?list=PL123"))
    }

    @Test
    fun `channel url normalisation`() {
        assertEquals("https://www.youtube.com/@handle", UrlCanon.channelUrl("@handle"))
        assertEquals(
            "https://www.youtube.com/channel/UCabc",
            UrlCanon.channelUrl("https://www.youtube.com/channel/UCabc/videos"),
        )
        assertNull(UrlCanon.channelUrl("https://example.com/@handle"))
    }

    @Test
    fun `canonical form is stable for cache keys`() {
        assertEquals(
            UrlCanon.watchUrl(id),
            UrlCanon.canonical("https://youtu.be/$id?si=abc"),
        )
        assertEquals(
            UrlCanon.watchUrl(id),
            UrlCanon.canonical(id),
        )
    }

    @Test
    fun `search url encodes query and keeps the sp code verbatim`() {
        val url = UrlCanon.searchUrl("lo-fi beats", "EgIQAQ%253D%253D")
        assertEquals(
            "https://www.youtube.com/results?search_query=lo-fi+beats&sp=EgIQAQ%253D%253D",
            url,
        )
    }

    @Test
    fun `channel tab detection`() {
        assertEquals(true, UrlCanon.isChannelTab("https://www.youtube.com/@x/videos"))
        assertEquals(false, UrlCanon.isChannelTab("https://www.youtube.com/watch?v=$id"))
    }

    @Test
    fun `channel search url drops the tab and encodes the query`() {
        assertEquals(
            "https://www.youtube.com/@lofigirl/search?query=lo-fi+beats",
            UrlCanon.channelSearchUrl("@lofigirl", "lo-fi beats"),
        )
        // A tab URL is normalised back to the channel identity first.
        assertEquals(
            "https://www.youtube.com/channel/UCabc/search?query=jazz",
            UrlCanon.channelSearchUrl("https://www.youtube.com/channel/UCabc/videos", "jazz"),
        )
    }
}
