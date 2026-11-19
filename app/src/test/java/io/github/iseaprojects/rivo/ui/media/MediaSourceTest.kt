package io.github.iseaprojects.rivo.ui.media

import io.github.iseaprojects.rivo.core.model.ChannelSummary
import io.github.iseaprojects.rivo.core.model.MediaSummary
import io.github.iseaprojects.rivo.core.model.PlaylistSummary
import io.github.iseaprojects.rivo.core.model.SearchKind
import io.github.iseaprojects.rivo.core.model.mixPlaylist
import io.github.iseaprojects.rivo.data.extract.MediaPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSourceTest {

    @Test
    fun `a chip replaces the kind default`() {
        val plain = MediaSource.Search("lofi", SearchKind.VIDEOS)
        assertEquals("EgIQAQ%253D%253D", plain.sp)

        val filtered = MediaSource.Search("lofi", SearchKind.VIDEOS, "4k")
        assertEquals("EgJwAQ%253D%253D", filtered.sp)

        val channelSearch = MediaSource.Search("lofi", SearchKind.CHANNELS)
        assertEquals("EgIQAg%253D%253D", channelSearch.sp)
    }

    @Test
    fun `an unknown chip falls back to the kind default`() {
        val source = MediaSource.Search("lofi", SearchKind.MOVIES, "teleport")
        assertEquals("EgIQBA%253D%253D", source.sp)
    }

    @Test
    fun `a channel search carries no sp code`() {
        val source = MediaSource.Search(
            query = "lofi",
            kind = SearchKind.VIDEOS,
            filterToken = "4k",
            channelUrl = "https://www.youtube.com/@lofigirl",
        )
        assertNull(source.sp)
        assertEquals("https://www.youtube.com/@lofigirl", source.channelUrl)
    }

    @Test
    fun `entries keep videos then playlists then channels`() {
        val page = MediaPage(
            items = listOf(video("v1"), video("v2")),
            playlists = listOf(playlist("p1")),
            channels = listOf(channel("c1")),
        )
        val entries = page.entries()
        assertEquals(4, entries.size)
        assertTrue(entries[0] is MediaEntry.Video)
        assertTrue(entries[1] is MediaEntry.Video)
        assertTrue(entries[2] is MediaEntry.Playlist)
        assertTrue(entries[3] is MediaEntry.Channel)
        assertEquals(listOf("v:v1", "v:v2", "p:p1", "c:c1"), entries.map { it.key })
    }

    @Test
    fun `identity-less entries are skipped`() {
        val page = MediaPage(
            items = listOf(MediaSummary(id = "", url = "", title = "x")),
            playlists = listOf(PlaylistSummary(id = "", url = "", title = "x")),
        )
        assertTrue(page.entries().isEmpty())
    }

    @Test
    fun `search text carries what the filter needs`() {
        val entry = MediaEntry.Video(
            MediaSummary(
                id = "abc",
                url = "https://www.youtube.com/watch?v=abc",
                title = "Lo-fi beats",
                channelName = "Chill",
                durationSeconds = 3725,
                viewCount = 1_200_000,
            ),
        )
        assertTrue(entry.searchText.contains("Lo-fi beats"))
        assertTrue(entry.searchText.contains("Chill"))
        assertTrue(entry.searchText.contains("1:02:05"))
        assertTrue(entry.searchText.contains("1.2M"))
    }

    @Test
    fun `mix is an RD playlist of the seed video`() {
        val media = video("abc")
        val mix = media.mixPlaylist()
        assertEquals("RDabc", mix.id)
        assertEquals("https://www.youtube.com/watch?v=abc&list=RDabc", mix.url)
        assertEquals(media.title, mix.title)
    }

    private fun video(id: String) = MediaSummary(
        id = id,
        url = "https://www.youtube.com/watch?v=$id",
        title = "Video $id",
    )

    private fun playlist(id: String) = PlaylistSummary(
        id = id,
        url = "https://www.youtube.com/playlist?list=$id",
        title = "Playlist $id",
    )

    private fun channel(id: String) = ChannelSummary(
        id = id,
        url = "https://www.youtube.com/channel/$id",
        name = "Channel $id",
    )
}
