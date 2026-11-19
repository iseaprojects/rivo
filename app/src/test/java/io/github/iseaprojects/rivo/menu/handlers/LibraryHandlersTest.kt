package io.github.iseaprojects.rivo.menu.handlers

import io.github.iseaprojects.rivo.core.config.ConfigSnapshot
import io.github.iseaprojects.rivo.core.i18n.MapStrings
import io.github.iseaprojects.rivo.core.menu.HandlerIds
import io.github.iseaprojects.rivo.core.menu.MenuContext
import io.github.iseaprojects.rivo.core.menu.MenuIds
import io.github.iseaprojects.rivo.core.menu.MenuNavigator
import io.github.iseaprojects.rivo.core.menu.MenuNode
import io.github.iseaprojects.rivo.core.menu.MenuPayload
import io.github.iseaprojects.rivo.core.menu.MenuResult
import io.github.iseaprojects.rivo.core.model.ChannelSummary
import io.github.iseaprojects.rivo.core.model.MediaSummary
import io.github.iseaprojects.rivo.core.theme.ThemeSpec
import io.github.iseaprojects.rivo.data.library.FakeLibraryDao
import io.github.iseaprojects.rivo.data.library.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The handlers are the only place that writes the library, and the Prompt/Confirm protocol
 * is stateless by design: these tests call the same handler twice, exactly as the UI does.
 */
class LibraryHandlersTest {

    private val dao = FakeLibraryDao()
    private var now = 1_000L
    private val library = LibraryRepository(dao, Dispatchers.Unconfined) { now }

    private var backs = 0
    private var opened: MenuResult.Open? = null

    private val strings = MapStrings(
        "en",
        mapOf(
            "TXT_ANDROID_SAVED_TO_LIBRARY" to "Saved",
            "TXT_ANDROID_ALREADY_SAVED" to "Already saved",
            "TXT_ANDROID_UNSAVED" to "Removed from saved",
            "TXT_ANDROID_PLAYLIST_NEW" to "New playlist",
            "TXT_ANDROID_PLAYLIST_CREATED" to "Playlist created: %1\$s",
            "TXT_ANDROID_PLAYLIST_RENAME" to "Rename playlist",
            "TXT_ANDROID_PLAYLIST_RENAMED" to "Renamed to %1\$s",
            "TXT_ANDROID_PLAYLIST_DELETE" to "Delete playlist",
            "TXT_ANDROID_PLAYLIST_DELETED" to "Playlist deleted: %1\$s",
            "TXT_ANDROID_PLAYLIST_DELETE_CONFIRM" to "Delete the playlist %1\$s and its items?",
            "TXT_ANDROID_PLAYLIST_ADDED" to "Added to %1\$s",
            "TXT_ANDROID_PLAYLIST_ALREADY" to "Already in %1\$s",
            "TXT_ANDROID_PLAYLIST_ITEM_REMOVED" to "Removed from playlist",
            "TXT_ANDROID_SUBSCRIBED" to "Subscribed to %1\$s",
            "TXT_ANDROID_UNSUBSCRIBED" to "Unsubscribed from %1\$s",
            "TXT_ENTER_PLAYLIST_TITLE" to "Enter playlist title",
        ),
    )

    private fun context(payload: MenuPayload = MenuPayload.None) = MenuContext(
        menuId = MenuIds.MEDIA_ACTIONS,
        payload = payload,
        strings = strings,
        config = ConfigSnapshot(emptyMap()),
        theme = ThemeSpec.FALLBACK,
        themes = emptyList(),
        languages = emptyList(),
        navigator = object : MenuNavigator {
            override fun open(
                menuId: String,
                payload: MenuPayload,
                title: String,
                nodes: List<MenuNode>?,
            ) {
                opened = MenuResult.Open(menuId, payload, title, nodes)
            }

            override fun back(): Boolean {
                backs++
                return true
            }

            override fun exit() = Unit
        },
    )

    private fun video(id: String = "abc") = MediaSummary(
        id = id,
        url = "https://www.youtube.com/watch?v=$id",
        title = "Video $id",
    )

    private fun channel() = ChannelSummary(
        id = "UC1",
        url = "https://www.youtube.com/channel/UC1",
        name = "Chill",
    )

    @Test
    fun `save reports the first save and then idempotence`() = runTest {
        val handler = LibraryHandlers.save(library)
        val node = MenuNode(id = "x", handler = HandlerIds.MEDIA_SAVE, payload = MenuPayload.Media(video()))

        val first = handler.handle(context(), node)
        val second = handler.handle(context(), node)

        assertEquals("Saved", (first as MenuResult.Message).text)
        assertEquals("Already saved", (second as MenuResult.Message).text)
        assertTrue(library.isSaved(video()))
    }

    @Test
    fun `unsave removes the item`() = runTest {
        library.save(video())
        val handler = LibraryHandlers.unsave(library)
        val result = handler.handle(
            context(),
            MenuNode(id = "x", handler = HandlerIds.MEDIA_UNSAVE, payload = MenuPayload.Media(video())),
        )

        assertEquals("Removed from saved", (result as MenuResult.Message).text)
        assertTrue(library.savedOnce().isEmpty())
    }

    @Test
    fun `creating a playlist from the picker adds the item`() = runTest {
        val handler = LibraryHandlers.createPlaylist(library)
        val node = MenuNode(id = "x", handler = HandlerIds.PLAYLIST_CREATE, payload = MenuPayload.Media(video()))

        val prompt = handler.handle(context(), node)
        assertTrue(prompt is MenuResult.Prompt)
        prompt as MenuResult.Prompt
        assertEquals(HandlerIds.PLAYLIST_CREATE, prompt.handler)
        assertEquals(MenuPayload.Media(video()), prompt.payload)

        val created = handler.handle(context(), node.copy(value = "  Road trip  "))
        assertEquals("Playlist created: Road trip", (created as MenuResult.Message).text)

        val stored = library.playlists().first().single()
        assertEquals("Road trip", stored.title)
        assertEquals(1, stored.itemCount)
        assertEquals(listOf("abc"), library.playlistItemsOnce(stored.id).map { it.id })
    }

    @Test
    fun `delete asks first and only deletes on confirmation`() = runTest {
        val id = library.createPlaylist("Road trip")
        library.addToPlaylist(id, video())
        val handler = LibraryHandlers.deletePlaylist(library)
        val payload = MenuPayload.LocalPlaylist(id, "Road trip")
        val node = MenuNode(id = "x", handler = HandlerIds.PLAYLIST_DELETE, payload = payload)

        val question = handler.handle(context(payload), node)
        assertTrue(question is MenuResult.Confirm)
        question as MenuResult.Confirm
        assertEquals("Delete the playlist Road trip and its items?", question.message)
        assertEquals(LibraryHandlers.CONFIRMED, question.value)
        assertEquals(0, backs)
        assertEquals("Road trip", library.playlistTitle(id))

        val done = handler.handle(context(payload), node.copy(value = LibraryHandlers.CONFIRMED))
        assertEquals("Playlist deleted: Road trip", (done as MenuResult.Message).text)
        assertEquals(null, library.playlistTitle(id))
        assertTrue(library.playlistItemsOnce(id).isEmpty())
        assertEquals(1, backs)
    }

    @Test
    fun `rename prompts with the live title and applies the trimmed value`() = runTest {
        val id = library.createPlaylist("Old")
        val handler = LibraryHandlers.renamePlaylist(library)
        // A stale payload, as if the row was built before an earlier rename.
        val payload = MenuPayload.LocalPlaylist(id, "Stale")
        val node = MenuNode(id = "x", handler = HandlerIds.PLAYLIST_RENAME, payload = payload)

        val prompt = handler.handle(context(payload), node) as MenuResult.Prompt
        assertEquals("Old", prompt.initial)

        val result = handler.handle(context(payload), node.copy(value = " New "))
        assertEquals("Renamed to New", (result as MenuResult.Message).text)
        assertEquals("New", library.playlistTitle(id))
    }

    @Test
    fun `adding the same item twice reports it`() = runTest {
        val id = library.createPlaylist("Road trip")
        val handler = LibraryHandlers.addToPlaylist(library)
        val node = MenuNode(
            id = "x",
            handler = HandlerIds.PLAYLIST_ADD_ITEM,
            payload = MenuPayload.Media(video()),
            value = id.toString(),
        )

        assertEquals("Added to Road trip", (handler.handle(context(), node) as MenuResult.Message).text)
        assertEquals("Already in Road trip", (handler.handle(context(), node) as MenuResult.Message).text)
        assertEquals(1, library.playlistItemsOnce(id).size)
    }

    @Test
    fun `removing from a playlist uses the collection id`() = runTest {
        val id = library.createPlaylist("Road trip")
        library.addToPlaylist(id, video())
        val handler = LibraryHandlers.removeFromPlaylist(library)
        val payload = MenuPayload.Media(
            item = video(),
            collectionId = id,
            collectionTitle = "Road trip",
        )

        val result = handler.handle(
            context(payload),
            MenuNode(id = "x", handler = HandlerIds.PLAYLIST_REMOVE_ITEM, payload = payload),
        )

        assertEquals("Removed from playlist", (result as MenuResult.Message).text)
        assertTrue(library.playlistItemsOnce(id).isEmpty())
    }

    @Test
    fun `subscribe and unsubscribe are reversible`() = runTest {
        val subscribe = LibraryHandlers.subscribe(library, subscribe = true)
        val unsubscribe = LibraryHandlers.subscribe(library, subscribe = false)
        val node = MenuNode(
            id = "x",
            handler = HandlerIds.CHANNEL_SUBSCRIBE,
            payload = MenuPayload.Channel(channel()),
        )

        assertEquals(
            "Subscribed to Chill",
            (subscribe.handle(context(), node) as MenuResult.Message).text,
        )
        assertTrue(library.isSubscribed(channel()))

        assertEquals(
            "Unsubscribed from Chill",
            (unsubscribe.handle(context(), node) as MenuResult.Message).text,
        )
        assertTrue(library.subscriptions().first().isEmpty())
    }
}
