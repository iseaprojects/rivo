package io.github.iseaprojects.rivo.core.menu

import io.github.iseaprojects.rivo.core.model.FeedKind
import io.github.iseaprojects.rivo.core.model.LibraryKind
import io.github.iseaprojects.rivo.core.model.SearchKind

/** Stable menu ids. Extensions reference these strings in `.ui.json` patches. */
object MenuIds {
    const val MAIN = "main"
    const val MISC = "misc"
    const val CONFIG = "config"
    const val CHOICE = "config_choice"

    const val FEED = "feed"
    const val SEARCH = "search"
    const val PLAYLISTS = "playlists"
    const val CUSTOM_PLAYLISTS = "custom_playlists"
    const val LIBRARY = "library"
    const val CHANNELS = "channels"
    const val CHANNEL = "channel"
    const val PLAYLIST = "playlist"
    const val MEDIA_ACTIONS = "media_actions"
    const val CHANNEL_ACTIONS = "channel_actions"

    /** Custom playlist contents (local videos) and its per-playlist actions. */
    const val LOCAL_PLAYLIST = "local_playlist"
    const val LOCAL_PLAYLIST_ACTIONS = "local_playlist_actions"

    /** Playlist picker shown by "Add to playlist". */
    const val ADD_TO_PLAYLIST = "add_to_playlist"

    const val SEARCH_HISTORY = "search_history"
    const val CUSTOM_COMMANDS = "custom_commands"
    const val CUSTOM_COMMAND = "custom_command"
    const val CUSTOM_COMMAND_ACTIONS = "custom_command_actions"

    /** Settings screens that own more than one row (folders, credentials, updater). */
    const val EXTENSIONS = "extensions"
    const val COOKIES = "cookies"
    const val EXTRACTOR = "extractor"
    const val UPDATE = "update"

    const val HISTORIES = "histories"
    const val DOWNLOADS = "downloads"

    /** Per-queue-row and per-file actions behind the downloads screen. */
    const val DOWNLOAD_TASK_ACTIONS = "download_task_actions"
    const val DOWNLOAD_FILE_ACTIONS = "download_file_actions"

    const val SITES = "sites"
    const val ACCOUNTS = "accounts"
}

/** Helper factory functions, used by the built-in catalog and by extension injection. */
object MenuDsl {

    fun action(
        id: String,
        labelKey: String,
        iconKey: String? = null,
        icon: String? = null,
        handler: String,
        payload: MenuPayload = MenuPayload.None,
        detail: String? = null,
        detailKey: String? = null,
        tone: MenuTone = MenuTone.NORMAL,
        enabled: Boolean = true,
        keyHint: String? = null,
        badge: String? = null,
    ): MenuNode = MenuNode(
        id = id,
        labelKey = labelKey,
        iconKey = iconKey,
        icon = icon,
        kind = MenuNodeKind.ACTION,
        handler = handler,
        payload = payload,
        detail = detail,
        detailKey = detailKey,
        tone = tone,
        enabled = enabled,
        keyHint = keyHint,
        badge = badge,
    )

    fun submenu(
        id: String,
        labelKey: String,
        iconKey: String? = null,
        icon: String? = null,
        children: List<MenuNode>,
        payload: MenuPayload = MenuPayload.None,
        detail: String? = null,
    ): MenuNode = MenuNode(
        id = id,
        labelKey = labelKey,
        iconKey = iconKey,
        icon = icon,
        kind = MenuNodeKind.SUBMENU,
        children = children,
        payload = payload,
        detail = detail,
    )

    fun target(
        id: String,
        labelKey: String,
        targetMenuId: String,
        iconKey: String? = null,
        icon: String? = null,
        payload: MenuPayload = MenuPayload.None,
        detail: String? = null,
        detailKey: String? = null,
        tone: MenuTone = MenuTone.NORMAL,
        enabled: Boolean = true,
    ): MenuNode = MenuNode(
        id = id,
        labelKey = labelKey,
        iconKey = iconKey,
        icon = icon,
        kind = MenuNodeKind.SUBMENU,
        targetMenuId = targetMenuId,
        payload = payload,
        detail = detail,
        detailKey = detailKey,
        tone = tone,
        enabled = enabled,
    )

    fun toggle(
        id: String,
        labelKey: String,
        configKey: String,
        handler: String,
        iconKey: String? = null,
        icon: String? = null,
        detail: String? = null,
    ): MenuNode = MenuNode(
        id = id,
        labelKey = labelKey,
        iconKey = iconKey,
        icon = icon,
        kind = MenuNodeKind.TOGGLE,
        handler = handler,
        toggleKey = configKey,
        detail = detail,
    )

    fun info(
        id: String,
        labelKey: String,
        detail: String,
        iconKey: String? = null,
    ): MenuNode = MenuNode(
        id = id,
        labelKey = labelKey,
        iconKey = iconKey,
        kind = MenuNodeKind.INFO,
        detail = detail,
        enabled = false,
    )

    fun back(): MenuNode = MenuNode(
        id = "app.back",
        labelKey = "TXT_MENU_BACK",
        iconKey = "TXT_ICON_MENU_MISC_BACK",
        handler = HandlerIds.BACK,
    )

    fun exit(): MenuNode = MenuNode(
        id = "app.exit",
        labelKey = "TXT_MENU_EXIT",
        iconKey = "TXT_ICON_MENU_MISC_EXIT",
        handler = HandlerIds.EXIT,
        tone = MenuTone.DESTRUCTIVE,
    )

    /** Feed entries share the same target menu and differ only by payload. */
    fun feed(
        id: String,
        labelKey: String,
        iconKey: String,
        kind: FeedKind,
    ): MenuNode = target(
        id = id,
        labelKey = labelKey,
        targetMenuId = MenuIds.FEED,
        iconKey = iconKey,
        payload = MenuPayload.Feed(kind),
    )

    fun library(
        id: String,
        labelKey: String,
        iconKey: String,
        kind: LibraryKind,
    ): MenuNode = target(
        id = id,
        labelKey = labelKey,
        targetMenuId = MenuIds.LIBRARY,
        iconKey = iconKey,
        payload = MenuPayload.Library(kind),
    )

    fun search(
        id: String,
        labelKey: String,
        iconKey: String,
        kind: SearchKind,
        query: String = "",
    ): MenuNode = target(
        id = id,
        labelKey = labelKey,
        targetMenuId = MenuIds.SEARCH,
        iconKey = iconKey,
        payload = MenuPayload.Search(query, kind),
    )
}
