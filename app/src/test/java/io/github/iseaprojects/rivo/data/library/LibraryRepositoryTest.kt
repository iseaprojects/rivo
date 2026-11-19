package io.github.iseaprojects.rivo.data.library

import app.cash.turbine.test
import io.github.iseaprojects.rivo.core.model.ChannelSummary
import io.github.iseaprojects.rivo.core.model.MediaProvider
import io.github.iseaprojects.rivo.core.model.MediaSummary
import io.github.iseaprojects.rivo.core.model.PlaylistSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRepositoryTest {

    private val dao = FakeLibraryDao()
    private var now = 1_000L

    private fun repository() = LibraryRepository(
        dao = dao,
        dispatcher = Dispatchers.Unconfined,
        clock = { now },
    )

    private fun video(id: String, title: String = "Video $id") = MediaSummary(
        id = id,
        url = "https://www.youtube.com/watch?v=$id",
        title = title,
        provider = MediaProvider.YOUTUBE,
        channelId = "UC1",
        channelName = "Chill",
        channelUrl = "https://www.youtube.com/channel/UC1",
        durationSeconds = 120,
        thumbnailUrl = "https://i.ytimg.com/vi/$id/hq.jpg",
    )

    private fun channel() = ChannelSummary(
        id = "UC1",
        url = "https://www.youtube.com/channel/UC1",
        name = "Chill",
        avatarUrl = "https://example.com/a.jpg",
    )

    private fun playlist(id: String = "PL1") = PlaylistSummary(
        id = id,
        url = "https://www.youtube.com/playlist?list=$id",
        title = "Mix $id",
        channelName = "Chill",
        itemCount = 12,
    )

    @Test
    fun `save is idempotent and survives a round trip`() = runTest {
        val library = repository()
        val item = video("abc")

        assertTrue(library.save(item))
        assertFalse(library.save(item))
        assertTrue(library.isSaved(item))

        val stored = library.savedOnce().single()
        assertEquals("abc", stored.id)
        assertEquals("Video abc", stored.title)
        assertEquals(MediaProvider.YOUTUBE, stored.provider)
        assertEquals("Chill", stored.channelName)
        assertEquals(120L, stored.durationSeconds)
    }

    @Test
    fun `unsave reports whether anything was removed`() = runTest {
        val library = repository()
        val item = video("abc")

        assertFalse(library.unsave(item))
        library.save(item)
        assertTrue(library.unsave(item))
        assertFalse(library.isSaved(item))
        assertTrue(library.savedOnce().isEmpty())
    }

    @Test
    fun `saved list is newest first`() = runTest {
        val library = repository()
        library.save(video("old"))
        now = 2_000L
        library.save(video("new"))

        assertEquals(listOf("new", "old"), library.savedOnce().map { it.id })
    }

    @Test
    fun `record recent bumps the row and trims to the limit`() = runTest {
        val library = repository()
        val first = video("a")
        val second = video("b")

        library.recordRecent(first, limit = 2)
        now = 2_000L
        library.recordRecent(second, limit = 2)
        now = 3_000L
        library.recordRecent(first, limit = 2)

        val ids = library.recentsOnce(10).map { it.id }
        assertEquals(listOf("a", "b"), ids)
        assertEquals(2, library.recentCount())

        now = 4_000L
        library.recordRecent(video("c"), limit = 2)
        assertEquals(listOf("c", "a"), library.recentsOnce(10).map { it.id })
    }

    @Test
    fun `custom playlists keep insertion order and record their size`() = runTest {
        val library = repository()
        val id = library.createPlaylist("  Road trip  ")
        assertEquals("Road trip", library.playlistTitle(id))
        assertEquals(0, library.playlists().first().single().itemCount)

        assertTrue(library.addToPlaylist(id, video("a")))
        assertTrue(library.addToPlaylist(id, video("b")))
        assertFalse(library.addToPlaylist(id, video("a")))

        assertEquals(listOf("a", "b"), library.playlistItemsOnce(id).map { it.id })
        assertEquals(2, library.playlists().first().single().itemCount)

        library.removeFromPlaylist(id, video("a"))
        assertEquals(listOf("b"), library.playlistItemsOnce(id).map { it.id })
    }

    @Test
    fun `deleting a playlist removes its items`() = runTest {
        val library = repository()
        val first = library.createPlaylist("One")
        val second = library.createPlaylist("Two")
        library.addToPlaylist(first, video("a"))
        library.addToPlaylist(second, video("b"))

        library.deletePlaylist(first)

        assertNull(library.playlistTitle(first))
        assertTrue(library.playlistItemsOnce(first).isEmpty())
        assertEquals(listOf("b"), library.playlistItemsOnce(second).map { it.id })
        assertEquals(listOf("Two"), library.playlists().first().map { it.title })
    }

    @Test
    fun `rename trims the title and touches the playlist`() = runTest {
        val library = repository()
        val id = library.createPlaylist("Old")
        now = 5_000L

        library.renamePlaylist(id, "  New  ")

        assertEquals("New", library.playlistTitle(id))
        assertEquals(5_000L, library.playlists().first().single().updatedAt)
    }

    @Test
    fun `search history folds case, bumps use count and recalls newest first`() = runTest {
        val library = repository()
        library.recordSearch("LoFi", 50)
        now = 2_000L
        library.recordSearch("  lofi  ", 50)
        now = 3_000L
        library.recordSearch("jazz", 50)

        assertEquals(listOf("jazz", "lofi"), library.recall(50))
        val stored = dao.searchHistoryEntry("lofi")
        assertEquals(2, stored?.useCount)
    }

    @Test
    fun `search history is trimmed to the limit`() = runTest {
        val library = repository()
        repeat(4) { index ->
            now = 1_000L + index
            library.recordSearch("q$index", limit = 2)
        }

        assertEquals(listOf("q3", "q2"), library.recall(10))
    }

    @Test
    fun `blank searches are ignored`() = runTest {
        val library = repository()
        library.recordSearch("   ", 10)
        assertTrue(library.recall(10).isEmpty())
    }

    @Test
    fun `subscriptions are idempotent and cleared by sync`() = runTest {
        val library = repository()
        assertTrue(library.subscribe(channel()))
        assertFalse(library.subscribe(channel()))
        assertTrue(library.isSubscribed(channel()))

        val other = ChannelSummary(
            id = "UC2",
            url = "https://www.youtube.com/channel/UC2",
            name = "Ambient",
        )
        assertEquals(2, library.replaceSubscriptions(listOf(channel(), other, channel())))

        val names = library.subscriptions().first().map { it.name }
        assertEquals(listOf("Ambient", "Chill"), names)
    }

    @Test
    fun `an empty sync never wipes local subscriptions`() = runTest {
        val library = repository()
        library.subscribe(channel())

        assertEquals(0, library.replaceSubscriptions(emptyList()))
        assertEquals(listOf("Chill"), library.subscriptions().first().map { it.name })
    }

    @Test
    fun `saved playlists are idempotent and reversible`() = runTest {
        val library = repository()
        val mix = playlist()

        assertTrue(library.savePlaylist(mix))
        assertFalse(library.savePlaylist(mix))
        assertTrue(library.isPlaylistSaved(mix))
        assertEquals("Mix PL1", library.savedPlaylists().first().single().title)

        assertTrue(library.unsavePlaylist(mix))
        assertFalse(library.unsavePlaylist(mix))
        assertTrue(library.savedPlaylists().first().isEmpty())
    }

    @Test
    fun `every write emits a change so open lists reload`() = runTest {
        val library = repository()

        library.changes.test {
            library.save(video("abc"))
            awaitItem()

            library.recordSearch("lofi", 10)
            awaitItem()

            library.clearRecents()
            awaitItem()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `identity includes the provider`() = runTest {
        val library = repository()
        library.save(video("abc"))
        library.save(video("abc").copy(provider = MediaProvider.PEERTUBE))

        assertEquals(2, library.savedOnce().size)
        assertEquals(
            listOf(MediaProvider.YOUTUBE, MediaProvider.PEERTUBE).sorted(),
            library.savedOnce().map { it.provider }.sorted(),
        )
    }
}
