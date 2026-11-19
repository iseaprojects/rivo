package io.github.iseaprojects.rivo.core.menu

import io.github.iseaprojects.rivo.core.model.ChannelSummary
import io.github.iseaprojects.rivo.core.model.FeedKind
import io.github.iseaprojects.rivo.core.model.LibraryKind
import io.github.iseaprojects.rivo.core.model.MediaSummary
import io.github.iseaprojects.rivo.core.model.PlaylistSummary
import io.github.iseaprojects.rivo.core.model.SearchKind

/**
 * What a menu entry refers to. Payloads are part of the navigation frame, so the same
 * menu id ("feed", "library", "media_actions") serves many screens without inventing
 * a menu id per variant.
 */
sealed interface MenuPayload {
    data object None : MenuPayload

    /**
     * [collection] records where the item was opened from, so the actions menu can offer
     * "Remove from playlist" only where that makes sense. [collectionId] is the custom
     * playlist id; [collectionTitle] is what the row label and confirmation use.
     */
    data class Media(
        val item: MediaSummary,
        val collection: MediaCollection = MediaCollection.NONE,
        val collectionId: Long? = null,
        val collectionTitle: String? = null,
    ) : MenuPayload

    data class Playlist(val summary: PlaylistSummary) : MenuPayload

    /** A user-created playlist: its id is local, so it cannot be a [PlaylistSummary]. */
    data class LocalPlaylist(val id: Long, val title: String) : MenuPayload

    /**
     * [tab] is the `ChannelTabKind` name ("VIDEOS", "SHORTS", …) the screen should open
     * on. It is a plain string because the payload is part of the navigation contract
     * and must not pull the extractor layer into `core`.
     */
    data class Channel(
        val summary: ChannelSummary,
        val tab: String? = null,
    ) : MenuPayload

    /**
     * [filterToken] is the `sp` filter selected on the chips (":4k", set by token name).
     * Keeping the token instead of the raw protobuf lets the chips re-open on the right
     * selection after a config change.
     *
     * [channelUrl] turns the screen into the channel's own Search tab (upstream
     * "Search" in `menu_channel_actions`): same list, scoped to one channel, with no
     * global filter chips.
     */
    data class Search(
        val query: String,
        val kind: SearchKind = SearchKind.VIDEOS,
        val filterToken: String? = null,
        val channelUrl: String? = null,
    ) : MenuPayload

    data class Feed(val kind: FeedKind) : MenuPayload

    data class Library(val kind: LibraryKind) : MenuPayload

    data class Url(val url: String) : MenuPayload

    data class ConfigValue(val key: String) : MenuPayload

    data class Text(val value: String) : MenuPayload

    /**
     * A row in the download queue, identified by the task id (`video:<videoId>`). The
     * payload carries the id rather than the task so a menu never holds a stale copy of
     * state that changes between builds.
     */
    data class Download(val id: String) : MenuPayload

    /** A finished file in the download folder, identified by its content or file URI. */
    data class DownloadFile(val id: String) : MenuPayload

    /**
     * A custom command, local or extension-provided, identified by its stable id.
     * The row is rendered from the live command list, so renaming a command relabels it
     * without leaving a stale copy in the navigation stack.
     */
    data class Command(val id: String) : MenuPayload

    /**
     * The multi-prompt "New custom command" flow.
     *
     * [stage] advances name -> URL -> yt-dlp options; the handler re-sends the draft with
     * the previous answer filled in, which keeps the flow stateless and survives rotation
     * (the payload lives in the dialog call, not in a mutable singleton).
     */
    data class CommandDraft(
        val stage: Int = 0,
        val id: String? = null,
        val name: String = "",
        val url: String = "",
        val options: String = "",
    ) : MenuPayload

    /** One action of a declarative `.sites` extension. */
    data class SiteActionRef(val siteId: String, val actionId: String) : MenuPayload
}

/** Where a media item was opened from. */
enum class MediaCollection {
    NONE,
    CUSTOM_PLAYLIST,
}

enum class MenuNodeKind {
    ACTION,
    SUBMENU,
    TOGGLE,
    INFO,
}

enum class MenuTone {
    NORMAL,
    ACCENT,
    DESTRUCTIVE,
    MUTED,
}

enum class MenuSource {
    BUILTIN,
    EXTENSION,
}

/**
 * One entry of an action menu.
 *
 * The node is declarative: it names its label/icon keys and its handler instead of
 * calling code directly. That is what allows a `.ui.json` extension to sort, filter
 * and inject entries without recompiling the app, mirroring `_menu_main [sort]
 * [filter_regex] [extra_actions] [handler]` in yt-x.
 *
 * Exactly one of these drives a click:
 *  - [targetMenuId]: push the registered menu with this id (extension sorting applies).
 *  - [children]: push this node's inline submenu (typical for extension injections).
 *  - [handler]: execute a registered leaf action.
 */
data class MenuNode(
    val id: String,
    val labelKey: String? = null,
    val label: String = "",
    val iconKey: String? = null,
    val icon: String? = null,
    val detail: String? = null,
    val detailKey: String? = null,
    val value: String? = null,
    val kind: MenuNodeKind = MenuNodeKind.ACTION,
    val handler: String? = null,
    val targetMenuId: String? = null,
    val payload: MenuPayload = MenuPayload.None,
    val children: List<MenuNode> = emptyList(),
    val toggleKey: String? = null,
    val checked: Boolean? = null,
    val tone: MenuTone = MenuTone.NORMAL,
    val enabled: Boolean = true,
    val keyHint: String? = null,
    val badge: String? = null,
    val position: String? = null,
    val source: MenuSource = MenuSource.BUILTIN,
) {
    val isSubmenu: Boolean get() = targetMenuId != null || children.isNotEmpty()

    val isToggle: Boolean get() = toggleKey != null || kind == MenuNodeKind.TOGGLE
}
