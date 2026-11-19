package io.github.iseaprojects.rivo.menu

import io.github.iseaprojects.rivo.core.config.ConfigKeys
import io.github.iseaprojects.rivo.core.menu.HandlerIds
import io.github.iseaprojects.rivo.core.menu.MediaCollection
import io.github.iseaprojects.rivo.core.menu.MenuContext
import io.github.iseaprojects.rivo.core.menu.MenuDefinition
import io.github.iseaprojects.rivo.core.menu.MenuDsl
import io.github.iseaprojects.rivo.core.menu.MenuIds
import io.github.iseaprojects.rivo.core.menu.MenuNode
import io.github.iseaprojects.rivo.core.menu.MenuPayload
import io.github.iseaprojects.rivo.core.menu.MenuTone
import io.github.iseaprojects.rivo.core.model.ChannelSummary
import io.github.iseaprojects.rivo.core.model.MediaSummary
import io.github.iseaprojects.rivo.core.model.PlaylistSummary
import io.github.iseaprojects.rivo.core.model.SearchKind
import io.github.iseaprojects.rivo.core.model.mixPlaylist
import io.github.iseaprojects.rivo.data.extract.ChannelTabKind

/**
 * Per-item action menus.
 *
 * The rows are the upstream `menu_media_actions` / `menu_channel_actions` sets. Rows that
 * belong to a later phase are real entries wired to [HandlerIds.PENDING]: the label is
 * what the user gets back, so a tap explains itself instead of doing nothing. Everything
 * that only needs navigation, the browser, the clipboard, the config or the local library
 * works now — including the Save/UnSave, Subscribe/Unsubscribe and playlist rows, whose
 * labels follow the library snapshot in [MenuContext.library].
 */
object MediaMenus {

    fun mediaActions(): MenuDefinition = MenuDefinition(
        id = MenuIds.MEDIA_ACTIONS,
        titleKey = "TXT_ANDROID_ACTIONS_PANEL",
        build = { ctx ->
            when (val payload = ctx.payload) {
                is MenuPayload.Media -> videoActions(ctx, payload)
                is MenuPayload.Playlist -> playlistActions(ctx, payload.summary)
                else -> emptyList()
            }
        },
    )

    fun channelActions(): MenuDefinition = MenuDefinition(
        id = MenuIds.CHANNEL_ACTIONS,
        titleKey = "TXT_MENU_CHANNEL_ACTIONS_PROMPT",
        build = { ctx ->
            val summary = (ctx.payload as? MenuPayload.Channel)?.summary
            if (summary == null) emptyList() else channelRows(ctx, summary)
        },
    )

    private fun videoActions(ctx: MenuContext, payload: MenuPayload.Media): List<MenuNode> {
        val media = payload.item
        val channel = channelOf(media)?.let { candidate ->
            if (ctx.library.isSubscribed(candidate)) candidate.copy(isSubscribed = true) else candidate
        }
        val saved = ctx.library.isSaved(media)
        val buttons = mutableListOf<MenuNode>()

        buttons += MenuDsl.action(
            id = "media.watch",
            labelKey = "TXT_MENU_MEDIA_ACTIONS_WATCH",
            iconKey = "TXT_ICON_MENU_MEDIA_ACTIONS_WATCH",
            handler = HandlerIds.MEDIA_WATCH,
            payload = payload,
            tone = MenuTone.ACCENT,
        )
        buttons += MenuDsl.action(
            id = "media.listen",
            labelKey = "TXT_MENU_MEDIA_ACTIONS_LISTEN",
            iconKey = "TXT_ICON_MENU_MEDIA_ACTIONS_LISTEN",
            handler = HandlerIds.MEDIA_LISTEN,
            payload = payload,
        )
        buttons += MenuDsl.action(
            id = "media.external",
            labelKey = "TXT_ANDROID_SEND_EXTERNAL",
            iconKey = "TXT_ICON_MENU_MEDIA_ACTIONS_BROWSER",
            handler = HandlerIds.MEDIA_EXTERNAL,
            payload = payload,
        )
        buttons += MenuDsl.action(
            id = "media.download",
            labelKey = "TXT_MENU_MEDIA_ACTIONS_DOWNLOAD",
            iconKey = "TXT_ICON_MENU_MEDIA_ACTIONS_DOWNLOAD",
            handler = HandlerIds.DOWNLOADS_ENQUEUE,
            payload = payload,
        )
        buttons += MenuDsl.action(
            id = "media.download_audio",
            labelKey = "TXT_MENU_MEDIA_ACTIONS_DOWNLOAD_AUDIO",
            iconKey = "TXT_ICON_MENU_MEDIA_ACTIONS_DOWNLOAD_AUDIO",
            handler = HandlerIds.DOWNLOADS_ENQUEUE_AUDIO,
            payload = payload,
        )
        buttons += MenuDsl.action(
            id = "media.save",
            labelKey = if (saved) {
                "TXT_MENU_MEDIA_ACTIONS_UNSAVE"
            } else {
                "TXT_MENU_MEDIA_ACTIONS_SAVE"
            },
            iconKey = if (saved) {
                "TXT_ICON_MENU_MEDIA_ACTIONS_UNSAVE"
            } else {
                "TXT_ICON_MENU_MEDIA_ACTIONS_SAVE"
            },
            handler = if (saved) HandlerIds.MEDIA_UNSAVE else HandlerIds.MEDIA_SAVE,
            payload = payload,
        )
        buttons += MenuNode(
            id = "media.add_to_playlist",
            labelKey = "TXT_ANDROID_PLAYLIST_ADD",
            icon = Glyphs.PLUS,
            targetMenuId = MenuIds.ADD_TO_PLAYLIST,
            payload = payload,
        )
        if (payload.collection == MediaCollection.CUSTOM_PLAYLIST) {
            buttons += MenuDsl.action(
                id = "media.remove_from_playlist",
                labelKey = "TXT_ANDROID_PLAYLIST_REMOVE",
                icon = Glyphs.MINUS,
                handler = HandlerIds.PLAYLIST_REMOVE_ITEM,
                payload = payload,
                detail = payload.collectionTitle,
                tone = MenuTone.DESTRUCTIVE,
            )
        }
        if (channel != null) {
            buttons += MenuDsl.action(
                id = "media.subscribe",
                labelKey = if (channel.isSubscribed) {
                    "TXT_ANDROID_UNSUBSCRIBE"
                } else {
                    "TXT_MENU_MEDIA_ACTIONS_SUBSCRIBE"
                },
                icon = if (channel.isSubscribed) Glyphs.UNSUBSCRIBE else Glyphs.SUBSCRIBE,
                handler = if (channel.isSubscribed) {
                    HandlerIds.CHANNEL_UNSUBSCRIBE
                } else {
                    HandlerIds.CHANNEL_SUBSCRIBE
                },
                payload = MenuPayload.Channel(channel),
            )
        }
        buttons += MenuDsl.target(
            id = "media.visit_channel",
            labelKey = "TXT_MENU_MEDIA_ACTIONS_VISIT_CHANNEL",
            targetMenuId = MenuIds.CHANNEL_ACTIONS,
            iconKey = "TXT_ICON_MENU_MEDIA_ACTIONS_VISIT_CHANNEL",
            payload = channel?.let { MenuPayload.Channel(it) } ?: MenuPayload.None,
            detailKey = if (channel == null) "TXT_MENU_MEDIA_ACTIONS_CHANNEL_NOT_FOUND" else null,
            enabled = channel != null,
        )
        buttons += MenuDsl.action(
            id = "media.mix",
            labelKey = "TXT_MENU_MEDIA_ACTIONS_MIX",
            iconKey = "TXT_ICON_MENU_MEDIA_ACTIONS_MIX",
            handler = HandlerIds.OPEN_PAYLOAD,
            payload = MenuPayload.Playlist(media.mixPlaylist()),
        )
        buttons += MenuDsl.action(
            id = "media.browser",
            labelKey = "TXT_MENU_MEDIA_ACTIONS_BROWSER",
            iconKey = "TXT_ICON_MENU_MEDIA_ACTIONS_BROWSER",
            handler = HandlerIds.OPEN_URL,
            payload = MenuPayload.Url(media.url),
        )
        playlistUrl(media)?.let { url ->
            buttons += MenuDsl.action(
                id = "media.browser_playlist",
                labelKey = "TXT_MENU_MEDIA_ACTIONS_BROWSER_PLAYLIST",
                iconKey = "TXT_ICON_MENU_MEDIA_ACTIONS_BROWSER_PLAYLIST",
                handler = HandlerIds.OPEN_URL,
                payload = MenuPayload.Url(url),
            )
        }
        buttons += MenuDsl.action(
            id = "media.copy",
            labelKey = "TXT_MENU_MEDIA_ACTIONS_COPY",
            iconKey = "TXT_ICON_MENU_MEDIA_ACTIONS_COPY",
            handler = HandlerIds.COPY_URL,
            payload = MenuPayload.Url(media.url),
        )
        buttons += MenuDsl.action(
            id = "media.share",
            labelKey = "TXT_ANDROID_SHARE",
            iconKey = "TXT_ICON_MENU_MEDIA_ACTIONS_COPY",
            handler = HandlerIds.SHARE_URL,
            payload = MenuPayload.Url(media.url),
        )
        buttons += MenuDsl.toggle(
            id = "media.enumerate",
            labelKey = "TXT_MENU_MEDIA_ACTIONS_TOGGLE_ENUM",
            configKey = ConfigKeys.DOWNLOADS_ENUMERATE,
            handler = HandlerIds.TOGGLE_CONFIG,
            iconKey = "TXT_ICON_MENU_MEDIA_ACTIONS_TOGGLE_ENUM",
        )
        buttons += MenuDsl.action(
            id = "media.shell",
            labelKey = "TXT_ANDROID_SHELL",
            iconKey = "TXT_ICON_MENU_MEDIA_ACTIONS_SHELL",
            handler = HandlerIds.MEDIA_SHELL,
            payload = payload,
        )
        buttons += MenuDsl.back()
        buttons += MenuDsl.exit()
        return buttons
    }

    private fun playlistActions(ctx: MenuContext, playlist: PlaylistSummary): List<MenuNode> {
        val saved = ctx.library.isPlaylistSaved(playlist)
        return listOf(
            MenuDsl.action(
                id = "playlist.play_all",
                labelKey = "TXT_MENU_MEDIA_ACTIONS_WATCH_ALL",
                iconKey = "TXT_ICON_MENU_MEDIA_ACTIONS_WATCH_ALL",
                handler = HandlerIds.PLAYLIST_PLAY_ALL,
                payload = MenuPayload.Playlist(playlist),
                tone = MenuTone.ACCENT,
            ),
            MenuDsl.action(
                id = "playlist.listen_all",
                labelKey = "TXT_MENU_MEDIA_ACTIONS_LISTEN_ALL",
                iconKey = "TXT_ICON_MENU_MEDIA_ACTIONS_LISTEN_ALL",
                handler = HandlerIds.PLAYLIST_LISTEN_ALL,
                payload = MenuPayload.Playlist(playlist),
            ),
            MenuDsl.action(
                id = "playlist.open",
                labelKey = "TXT_ANDROID_PLAYLIST_OPEN",
                icon = Glyphs.FOLDER,
                handler = HandlerIds.OPEN_PAYLOAD,
                payload = MenuPayload.Playlist(playlist),
            ),
            MenuDsl.action(
                id = "playlist.download_all",
                labelKey = "TXT_MENU_MEDIA_ACTIONS_DOWNLOAD_ALL",
                iconKey = "TXT_ICON_MENU_MEDIA_ACTIONS_DOWNLOAD_ALL",
                handler = HandlerIds.DOWNLOADS_ENQUEUE_PLAYLIST,
                payload = MenuPayload.Playlist(playlist),
            ),
            MenuDsl.action(
                id = "playlist.download_all_audio",
                labelKey = "TXT_MENU_MEDIA_ACTIONS_DOWNLOAD_ALL_AUDIO",
                iconKey = "TXT_ICON_MENU_MEDIA_ACTIONS_DOWNLOAD_ALL_AUDIO",
                handler = HandlerIds.DOWNLOADS_ENQUEUE_PLAYLIST_AUDIO,
                payload = MenuPayload.Playlist(playlist),
            ),
            MenuDsl.action(
                id = "playlist.save",
                labelKey = if (saved) {
                    "TXT_MENU_MEDIA_ACTIONS_UNSAVE_PLAYLIST"
                } else {
                    "TXT_MENU_MEDIA_ACTIONS_SAVE_PLAYLIST"
                },
                iconKey = if (saved) {
                    "TXT_ICON_MENU_MEDIA_ACTIONS_UNSAVE_PLAYLIST"
                } else {
                    "TXT_ICON_MENU_MEDIA_ACTIONS_SAVE_PLAYLIST"
                },
                handler = if (saved) HandlerIds.PLAYLIST_UNSAVE_REMOTE else HandlerIds.PLAYLIST_SAVE_REMOTE,
                payload = MenuPayload.Playlist(playlist),
            ),
            MenuDsl.action(
                id = "playlist.browser",
                labelKey = "TXT_MENU_MEDIA_ACTIONS_BROWSER_PLAYLIST",
                iconKey = "TXT_ICON_MENU_MEDIA_ACTIONS_BROWSER_PLAYLIST",
                handler = HandlerIds.OPEN_URL,
                payload = MenuPayload.Url(playlist.url),
            ),
            MenuDsl.back(),
            MenuDsl.exit(),
        )
    }

    private fun channelRows(ctx: MenuContext, channel: ChannelSummary): List<MenuNode> {
        val subscribed = ctx.library.isSubscribed(channel)
        return buildList {
            add(
                tabRow(
                    channel,
                    ChannelTabKind.VIDEOS,
                    "TXT_MENU_CHANNEL_ACTIONS_VIDEOS",
                    "TXT_ICON_MENU_CHANNEL_ACTIONS_VIDEOS",
                ),
            )
            add(
                tabRow(
                    channel,
                    ChannelTabKind.SHORTS,
                    "TXT_MENU_CHANNEL_ACTIONS_SHORTS",
                    "TXT_ICON_MENU_CHANNEL_ACTIONS_SHORTS",
                ),
            )
            add(
                tabRow(
                    channel,
                    ChannelTabKind.LIVE,
                    "TXT_MENU_CHANNEL_ACTIONS_STREAMS",
                    "TXT_ICON_MENU_CHANNEL_ACTIONS_STREAMS",
                ),
            )
            add(
                tabRow(
                    channel,
                    ChannelTabKind.PLAYLISTS,
                    "TXT_MENU_CHANNEL_ACTIONS_PLAYLISTS",
                    "TXT_ICON_MENU_CHANNEL_ACTIONS_PLAYLISTS",
                ),
            )
            add(
                tabRow(
                    channel,
                    ChannelTabKind.FEATURED,
                    "TXT_MENU_CHANNEL_ACTIONS_FEATURED",
                    "TXT_ICON_MENU_CHANNEL_ACTIONS_FEATURED",
                ),
            )
            add(
                MenuDsl.target(
                    id = "channel.search",
                    labelKey = "TXT_MENU_CHANNEL_ACTIONS_SEARCH",
                    targetMenuId = MenuIds.SEARCH,
                    iconKey = "TXT_ICON_MENU_CHANNEL_ACTIONS_SEARCH",
                    payload = MenuPayload.Search(
                        query = "",
                        kind = SearchKind.VIDEOS,
                        channelUrl = channel.url,
                    ),
                ),
            )
            add(
                tabRow(
                    channel,
                    ChannelTabKind.PODCASTS,
                    "TXT_MENU_CHANNEL_ACTIONS_PODCASTS",
                    "TXT_ICON_MENU_CHANNEL_ACTIONS_PODCASTS",
                ),
            )
            add(
                MenuDsl.action(
                    id = "channel.subscribe",
                    labelKey = if (subscribed) {
                        "TXT_ANDROID_UNSUBSCRIBE"
                    } else {
                        "TXT_MENU_CHANNEL_ACTIONS_SUBSCRIBE"
                    },
                    icon = if (subscribed) Glyphs.UNSUBSCRIBE else Glyphs.SUBSCRIBE,
                    handler = if (subscribed) {
                        HandlerIds.CHANNEL_UNSUBSCRIBE
                    } else {
                        HandlerIds.CHANNEL_SUBSCRIBE
                    },
                    payload = MenuPayload.Channel(channel),
                ),
            )
            add(
                MenuDsl.action(
                    id = "channel.browser",
                    labelKey = "TXT_MENU_CHANNEL_ACTIONS_OPEN_IN_BROWSER",
                    iconKey = "TXT_ICON_MENU_CHANNEL_ACTIONS_BROWSER",
                    handler = HandlerIds.OPEN_URL,
                    payload = MenuPayload.Url(channel.url),
                ),
            )
            add(MenuDsl.back())
            add(MenuDsl.exit())
        }
    }

    private fun tabRow(
        channel: ChannelSummary,
        kind: ChannelTabKind,
        labelKey: String,
        iconKey: String,
    ): MenuNode = MenuDsl.action(
        id = "channel.tab.${kind.name.lowercase()}",
        labelKey = labelKey,
        iconKey = iconKey,
        handler = HandlerIds.OPEN_PAYLOAD,
        payload = MenuPayload.Channel(channel, kind.name),
    )

    /** Channel identity for "Go To Channel"; null when the item carries no uploader URL. */
    private fun channelOf(media: MediaSummary): ChannelSummary? {
        val url = media.channelUrl?.takeIf { it.isNotBlank() } ?: return null
        return ChannelSummary(
            id = media.channelId ?: url,
            url = url,
            name = media.channelName.orEmpty(),
        )
    }

    /** The watch URL with its list parameter, i.e. the browser's view of the playlist. */
    private fun playlistUrl(media: MediaSummary): String? {
        val list = media.playlistId?.takeIf { it.isNotBlank() } ?: return null
        return "https://www.youtube.com/watch?v=${media.id}&list=$list"
    }
}
