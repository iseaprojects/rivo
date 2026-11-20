package io.github.iseaprojects.rivo.menu

import io.github.iseaprojects.rivo.core.config.ConfigKeys
import io.github.iseaprojects.rivo.core.config.ConfigSnapshot
import io.github.iseaprojects.rivo.core.i18n.MapStrings
import io.github.iseaprojects.rivo.core.menu.HandlerIds
import io.github.iseaprojects.rivo.core.menu.MenuContext
import io.github.iseaprojects.rivo.core.menu.MenuIds
import io.github.iseaprojects.rivo.core.menu.MenuLibrary
import io.github.iseaprojects.rivo.core.menu.MenuNavigator
import io.github.iseaprojects.rivo.core.menu.MenuNode
import io.github.iseaprojects.rivo.core.menu.MenuPayload
import io.github.iseaprojects.rivo.core.menu.MenuPlaylist
import io.github.iseaprojects.rivo.core.model.ChannelSummary
import io.github.iseaprojects.rivo.core.model.MediaSummary
import io.github.iseaprojects.rivo.core.model.PlaylistSummary
import io.github.iseaprojects.rivo.core.theme.ThemeSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The library catalog menus are rebuilt from the snapshot on every resolution, so these
 * tests are also the specification for what each screen offers.
 */
class LibraryMenusTest {

    private val catalog = BuiltInMenus.catalog()

    private fun context(
        menuId: String,
        payload: MenuPayload = MenuPayload.None,
        library: MenuLibrary = MenuLibrary.EMPTY,
        config: ConfigSnapshot = ConfigSnapshot(emptyMap()),
    ): MenuContext = MenuContext(
        menuId = menuId,
        payload = payload,
        strings = MapStrings("en", emptyMap()),
        config = config,
        theme = ThemeSpec.FALLBACK,
        themes = emptyList(),
        languages = emptyList(),
        navigator = object : MenuNavigator {
            override fun open(
                menuId: String,
                payload: MenuPayload,
                title: String,
                nodes: List<MenuNode>?,
            ) = Unit

            override fun back(): Boolean = false
            override fun exit() = Unit
        },
        library = library,
    )

    private fun rows(menuId: String, ctx: MenuContext): List<MenuNode> =
        catalog[menuId]?.build?.invoke(ctx) ?: error("menu $menuId is not in the catalog")

    private fun row(rows: List<MenuNode>, id: String): MenuNode =
        rows.firstOrNull { it.id == id } ?: error("missing row $id: ${rows.map { it.id }}")

    private fun video(id: String = "abc") = MediaSummary(
        id = id,
        url = "https://www.youtube.com/watch?v=$id",
        title = "Video $id",
    )

    @Test
    fun `search history rows re-run their query`() {
        val ctx = context(
            MenuIds.SEARCH_HISTORY,
            library = MenuLibrary(searchHistory = listOf("lofi", "jazz")),
        )
        val rows = rows(MenuIds.SEARCH_HISTORY, ctx)

        assertEquals("lofi", row(rows, "history.0").payload.let {
            (it as MenuPayload.Search).query
        })
        assertEquals("jazz", row(rows, "history.1").payload.let {
            (it as MenuPayload.Search).query
        })
        assertEquals(MenuIds.SEARCH, row(rows, "history.0").targetMenuId)
        assertEquals(HandlerIds.HISTORY_CLEAR_SEARCH, row(rows, "history.clear").handler)
    }

    @Test
    fun `an empty search history has no clear row`() {
        val rows = rows(MenuIds.SEARCH_HISTORY, context(MenuIds.SEARCH_HISTORY))
        assertTrue(rows.none { it.id == "history.clear" })
        assertEquals(listOf("app.back", "app.exit"), rows.map { it.id })
    }

    @Test
    fun `custom playlists lead with creation and open their actions`() {
        val ctx = context(
            MenuIds.CUSTOM_PLAYLISTS,
            library = MenuLibrary(
                playlists = listOf(MenuPlaylist(3L, "Road trip", 4), MenuPlaylist(4L, "Gym", 0)),
            ),
        )
        val rows = rows(MenuIds.CUSTOM_PLAYLISTS, ctx)

        assertEquals("playlists.new", rows.first().id)
        assertEquals(HandlerIds.PLAYLIST_CREATE, rows.first().handler)

        val first = row(rows, "playlist.3")
        assertEquals(MenuIds.LOCAL_PLAYLIST_ACTIONS, first.targetMenuId)
        val payload = first.payload as MenuPayload.LocalPlaylist
        assertEquals(3L, payload.id)
        assertEquals("Road trip", payload.title)
    }

    @Test
    fun `local playlist actions open rename and delete`() {
        val playlist = MenuPayload.LocalPlaylist(3L, "Road trip")
        val rows = rows(MenuIds.LOCAL_PLAYLIST_ACTIONS, context(MenuIds.LOCAL_PLAYLIST_ACTIONS, playlist))

        assertEquals(MenuIds.LOCAL_PLAYLIST, row(rows, "local_playlist.open").targetMenuId)
        assertEquals(HandlerIds.PLAYLIST_RENAME, row(rows, "local_playlist.rename").handler)
        assertEquals(HandlerIds.PLAYLIST_DELETE, row(rows, "local_playlist.delete").handler)
    }

    @Test
    fun `the playlist picker carries the media item into every row`() {
        val item = video()
        val ctx = context(
            MenuIds.ADD_TO_PLAYLIST,
            payload = MenuPayload.Media(item),
            library = MenuLibrary(playlists = listOf(MenuPlaylist(3L, "Road trip", 4))),
        )
        val rows = rows(MenuIds.ADD_TO_PLAYLIST, ctx)

        assertEquals(HandlerIds.PLAYLIST_CREATE, rows.first().handler)
        assertEquals(item, (rows.first().payload as MenuPayload.Media).item)

        val target = row(rows, "add_to_playlist.3")
        assertEquals(HandlerIds.PLAYLIST_ADD_ITEM, target.handler)
        assertEquals("3", target.value)
        assertEquals(item, (target.payload as MenuPayload.Media).item)
    }

    @Test
    fun `saved playlists and subscriptions route to their action menus`() {
        val playlist = PlaylistSummary(
            id = "PL1",
            url = "https://www.youtube.com/playlist?list=PL1",
            title = "Mix",
        )
        val channel = ChannelSummary(
            id = "UC1",
            url = "https://www.youtube.com/channel/UC1",
            name = "Chill",
        )

        val playlistRows = rows(
            MenuIds.PLAYLISTS,
            context(MenuIds.PLAYLISTS, library = MenuLibrary(savedPlaylists = listOf(playlist))),
        )
        assertEquals(HandlerIds.OPEN_ACTIONS, row(playlistRows, "saved_playlist.0").handler)

        val channelRows = rows(
            MenuIds.CHANNELS,
            context(MenuIds.CHANNELS, library = MenuLibrary(subscriptions = listOf(channel))),
        )
        val subscription = row(channelRows, "subscription.0")
        assertEquals(HandlerIds.OPEN_ACTIONS, subscription.handler)
        assertTrue((subscription.payload as MenuPayload.Channel).summary.isSubscribed)
    }

    @Test
    fun `histories offers both clears`() {
        val rows = rows(MenuIds.HISTORIES, context(MenuIds.HISTORIES))

        assertEquals(HandlerIds.HISTORY_CLEAR_SEARCH, row(rows, "histories.clear_search").handler)
        assertEquals(HandlerIds.HISTORY_CLEAR_RECENT, row(rows, "histories.clear_recent").handler)
    }

    @Test
    fun `the config menu exposes account feeds and search history toggles`() {
        val rows = rows(MenuIds.CONFIG, context(MenuIds.CONFIG))

        assertEquals(HandlerIds.TOGGLE_CONFIG, row(rows, "cfg.account_feeds").handler)
        assertEquals(HandlerIds.TOGGLE_CONFIG, row(rows, "cfg.search_history").handler)
    }

    @Test
    fun `the download retry budget is editable from the config menu`() {
        val rows = rows(MenuIds.CONFIG, context(MenuIds.CONFIG))
        val retries = row(rows, "cfg.download_retries")

        assertEquals(MenuIds.CHOICE, retries.targetMenuId)
        assertEquals(MenuPayload.ConfigValue(ConfigKeys.DOWNLOAD_RETRIES), retries.payload)

        val choices = rows(
            MenuIds.CHOICE,
            context(MenuIds.CHOICE, payload = retries.payload, config = ConfigSnapshot(
                mapOf(ConfigKeys.DOWNLOAD_RETRIES to "5"),
            )),
        )
        assertEquals(listOf("1", "3", "5", "10"), choices.map { it.value })
        assertEquals(listOf("retry.5"), choices.filter { it.checked == true }.map { it.id })
        assertEquals(HandlerIds.SELECT_CONFIG, choices.first().handler)
    }

    @Test
    fun `sync subscriptions is wired to the library handler`() {
        val rows = rows(MenuIds.MISC, context(MenuIds.MISC))
        assertEquals(HandlerIds.SUBS_SYNC, row(rows, "misc.sync_subs").handler)
    }

    @Test
    fun `downloads opens the download queue screen`() {
        val rows = rows(MenuIds.MISC, context(MenuIds.MISC))
        val downloads = row(rows, "misc.downloads")
        assertEquals(MenuIds.DOWNLOADS, downloads.targetMenuId)
        assertEquals(MenuPayload.None, downloads.payload)
    }
}
