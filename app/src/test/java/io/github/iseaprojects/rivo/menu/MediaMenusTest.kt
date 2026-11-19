package io.github.iseaprojects.rivo.menu

import io.github.iseaprojects.rivo.core.config.ConfigSnapshot
import io.github.iseaprojects.rivo.core.i18n.MapStrings
import io.github.iseaprojects.rivo.core.menu.HandlerIds
import io.github.iseaprojects.rivo.core.menu.MediaCollection
import io.github.iseaprojects.rivo.core.menu.MenuContext
import io.github.iseaprojects.rivo.core.menu.MenuIds
import io.github.iseaprojects.rivo.core.menu.MenuLibrary
import io.github.iseaprojects.rivo.core.menu.MenuNavigator
import io.github.iseaprojects.rivo.core.menu.MenuNode
import io.github.iseaprojects.rivo.core.menu.MenuPayload
import io.github.iseaprojects.rivo.core.menu.MenuResult
import io.github.iseaprojects.rivo.core.model.ChannelSummary
import io.github.iseaprojects.rivo.core.model.MediaSummary
import io.github.iseaprojects.rivo.core.model.PlaylistSummary
import io.github.iseaprojects.rivo.core.theme.ThemeSpec
import io.github.iseaprojects.rivo.menu.handlers.OpenPayloadHandler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaMenusTest {

    private fun context(
        payload: MenuPayload,
        library: MenuLibrary = MenuLibrary.EMPTY,
    ): MenuContext = MenuContext(
        menuId = MenuIds.MEDIA_ACTIONS,
        payload = payload,
        strings = MapStrings("en", emptyMap()),
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
            ) = Unit

            override fun back(): Boolean = false
            override fun exit() = Unit
        },
        library = library,
    )

    private fun video(channelUrl: String? = "https://www.youtube.com/@chill") = MediaSummary(
        id = "abc",
        url = "https://www.youtube.com/watch?v=abc",
        title = "Lo-fi beats",
        channelName = "Chill",
        channelUrl = channelUrl,
        playlistId = "PL1",
    )

    private fun row(rows: List<MenuNode>, id: String): MenuNode =
        rows.firstOrNull { it.id == id } ?: error("missing row $id: ${rows.map { it.id }}")

    @Test
    fun `video actions keep the upstream order and handlers`() {
        val rows = MediaMenus.mediaActions().build(context(MenuPayload.Media(video())))

        assertEquals("media.watch", rows.first().id)
        assertEquals(HandlerIds.MEDIA_WATCH, row(rows, "media.watch").handler)
        assertEquals(HandlerIds.MEDIA_LISTEN, row(rows, "media.listen").handler)
        assertEquals(HandlerIds.MEDIA_EXTERNAL, row(rows, "media.external").handler)
        assertEquals(HandlerIds.MEDIA_SHELL, row(rows, "media.shell").handler)
        assertEquals(HandlerIds.DOWNLOADS_ENQUEUE, row(rows, "media.download").handler)
        assertEquals(HandlerIds.DOWNLOADS_ENQUEUE_AUDIO, row(rows, "media.download_audio").handler)
        assertEquals(HandlerIds.COPY_URL, row(rows, "media.copy").handler)
        assertEquals(HandlerIds.SHARE_URL, row(rows, "media.share").handler)
        assertEquals(HandlerIds.OPEN_URL, row(rows, "media.browser").handler)
        assertEquals(HandlerIds.BACK, row(rows, "app.back").handler)
        assertEquals(HandlerIds.EXIT, row(rows, "app.exit").handler)
    }

    @Test
    fun `visit channel is disabled without an uploader URL`() {
        val withChannel = MediaMenus.mediaActions().build(context(MenuPayload.Media(video())))
        assertTrue(row(withChannel, "media.visit_channel").enabled)
        assertEquals(
            MenuIds.CHANNEL_ACTIONS,
            row(withChannel, "media.visit_channel").targetMenuId,
        )

        val without = MediaMenus.mediaActions()
            .build(context(MenuPayload.Media(video(channelUrl = null))))
        assertFalse(row(without, "media.visit_channel").enabled)
        assertEquals(MenuPayload.None, row(without, "media.visit_channel").payload)
    }

    @Test
    fun `mix points at the generated RD playlist`() {
        val rows = MediaMenus.mediaActions().build(context(MenuPayload.Media(video())))
        val mix = row(rows, "media.mix").payload as MenuPayload.Playlist
        assertEquals("RDabc", mix.summary.id)
        assertEquals("https://www.youtube.com/watch?v=abc&list=RDabc", mix.summary.url)
    }

    @Test
    fun `browser playlist carries the list parameter`() {
        val rows = MediaMenus.mediaActions().build(context(MenuPayload.Media(video())))
        val url = (row(rows, "media.browser_playlist").payload as MenuPayload.Url).url
        assertEquals("https://www.youtube.com/watch?v=abc&list=PL1", url)
    }

    @Test
    fun `playlist actions play first and keep the contents reachable`() {
        val playlist = PlaylistSummary(
            id = "PL1",
            url = "https://www.youtube.com/playlist?list=PL1",
            title = "Mix",
        )
        val rows = MediaMenus.mediaActions().build(context(MenuPayload.Playlist(playlist)))

        // F5 makes the upstream order true: Play All is the primary row, Open playlist stays
        // one tap away so a saved remote playlist is still browsable.
        assertEquals("playlist.play_all", rows.first().id)
        assertEquals(HandlerIds.PLAYLIST_PLAY_ALL, row(rows, "playlist.play_all").handler)
        assertEquals(HandlerIds.PLAYLIST_LISTEN_ALL, row(rows, "playlist.listen_all").handler)
        assertEquals(HandlerIds.OPEN_PAYLOAD, row(rows, "playlist.open").handler)
        assertEquals(HandlerIds.DOWNLOADS_ENQUEUE_PLAYLIST, row(rows, "playlist.download_all").handler)
        assertEquals(
            MenuPayload.Playlist(playlist),
            row(rows, "playlist.download_all").payload,
        )
        assertEquals(
            "https://www.youtube.com/playlist?list=PL1",
            (row(rows, "playlist.browser").payload as MenuPayload.Url).url,
        )
    }

    @Test
    fun `playlist actions follow the saved state`() {
        val playlist = PlaylistSummary(
            id = "PL1",
            url = "https://www.youtube.com/playlist?list=PL1",
            title = "Mix",
        )
        val saved = MediaMenus.mediaActions()
            .build(context(MenuPayload.Playlist(playlist), MenuLibrary(savedPlaylists = listOf(playlist))))
        assertEquals(HandlerIds.PLAYLIST_UNSAVE_REMOTE, row(saved, "playlist.save").handler)
        assertEquals("TXT_MENU_MEDIA_ACTIONS_UNSAVE_PLAYLIST", row(saved, "playlist.save").labelKey)

        val unsaved = MediaMenus.mediaActions().build(context(MenuPayload.Playlist(playlist)))
        assertEquals(HandlerIds.PLAYLIST_SAVE_REMOTE, row(unsaved, "playlist.save").handler)
        assertEquals("TXT_MENU_MEDIA_ACTIONS_SAVE_PLAYLIST", row(unsaved, "playlist.save").labelKey)
    }

    @Test
    fun `channel actions list every tab plus subscribe and browser`() {
        val channel = ChannelSummary(
            id = "UC1",
            url = "https://www.youtube.com/channel/UC1",
            name = "Chill",
        )
        val rows = MediaMenus.channelActions().build(context(MenuPayload.Channel(channel)))

        val tabs = rows.mapNotNull { it.payload as? MenuPayload.Channel }.filter {
            it.tab != null
        }
        assertEquals(
            listOf("VIDEOS", "SHORTS", "LIVE", "PLAYLISTS", "FEATURED", "PODCASTS"),
            tabs.map { it.tab },
        )
        assertEquals(HandlerIds.CHANNEL_SUBSCRIBE, row(rows, "channel.subscribe").handler)
        assertEquals(
            "https://www.youtube.com/channel/UC1",
            (row(rows, "channel.browser").payload as MenuPayload.Url).url,
        )
    }

    @Test
    fun `subscribed channels and saved videos swap their action rows`() {
        val channel = ChannelSummary(
            id = "UC1",
            url = "https://www.youtube.com/channel/UC1",
            name = "Chill",
        )
        val channelRows = MediaMenus.channelActions().build(
            context(
                MenuPayload.Channel(channel),
                MenuLibrary(subscriptions = listOf(channel)),
            ),
        )
        assertEquals(HandlerIds.CHANNEL_UNSUBSCRIBE, row(channelRows, "channel.subscribe").handler)
        assertEquals("TXT_ANDROID_UNSUBSCRIBE", row(channelRows, "channel.subscribe").labelKey)

        val item = video()
        val videoRows = MediaMenus.mediaActions().build(
            context(
                MenuPayload.Media(item),
                MenuLibrary(saved = listOf(item)),
            ),
        )
        assertEquals(HandlerIds.MEDIA_UNSAVE, row(videoRows, "media.save").handler)
        assertEquals("TXT_MENU_MEDIA_ACTIONS_UNSAVE", row(videoRows, "media.save").labelKey)
    }

    @Test
    fun `video actions offer save and the playlist picker by default`() {
        val rows = MediaMenus.mediaActions().build(context(MenuPayload.Media(video())))

        assertEquals(HandlerIds.MEDIA_SAVE, row(rows, "media.save").handler)
        assertEquals(MenuIds.ADD_TO_PLAYLIST, row(rows, "media.add_to_playlist").targetMenuId)
        assertTrue(rows.none { it.id == "media.remove_from_playlist" })
    }

    @Test
    fun `items opened from a custom playlist can be removed again`() {
        val item = video()
        val rows = MediaMenus.mediaActions().build(
            context(
                MenuPayload.Media(
                    item = item,
                    collection = MediaCollection.CUSTOM_PLAYLIST,
                    collectionId = 7L,
                    collectionTitle = "Road trip",
                ),
            ),
        )
        val remove = row(rows, "media.remove_from_playlist")
        assertEquals(HandlerIds.PLAYLIST_REMOVE_ITEM, remove.handler)
        assertEquals("Road trip", remove.detail)
        assertEquals(7L, (remove.payload as MenuPayload.Media).collectionId)
    }

    @Test
    fun `channel search targets the search screen scoped to the channel`() {
        val channel = ChannelSummary(
            id = "UC1",
            url = "https://www.youtube.com/channel/UC1",
            name = "Chill",
        )
        val rows = MediaMenus.channelActions().build(context(MenuPayload.Channel(channel)))
        val search = row(rows, "channel.search")

        assertEquals(MenuIds.SEARCH, search.targetMenuId)
        val payload = search.payload as MenuPayload.Search
        assertEquals("", payload.query)
        assertEquals("https://www.youtube.com/channel/UC1", payload.channelUrl)
    }

    @Test
    fun `open payload routes by payload type`() = runBlocking {
        val channel = ChannelSummary(
            id = "UC1",
            url = "https://www.youtube.com/channel/UC1",
            name = "Chill",
        )
        val drawer = OpenPayloadHandler()
        val opened = drawer.handle(
            context(MenuPayload.None),
            MenuNode(
                id = "x",
                payload = MenuPayload.Channel(channel, "SHORTS"),
                handler = HandlerIds.OPEN_PAYLOAD,
            ),
        )
        assertTrue(opened is MenuResult.Open)
        opened as MenuResult.Open
        assertEquals(MenuIds.CHANNEL, opened.menuId)
        assertEquals("Chill", opened.title)
        assertEquals(channel, (opened.payload as MenuPayload.Channel).summary)
    }

    @Test
    fun `open payload refuses payloads that are not menus`() = runBlocking {
        val result = OpenPayloadHandler().handle(
            context(MenuPayload.None),
            MenuNode(id = "x", payload = MenuPayload.Url("https://example.com")),
        )
        assertTrue(result is MenuResult.Unhandled)
    }
}
