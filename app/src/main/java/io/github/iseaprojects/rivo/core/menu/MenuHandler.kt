package io.github.iseaprojects.rivo.core.menu

/**
 * Leaf action. Handlers are registered by id so extensions can reference them
 * (`"handler": "app.open_url"`) without linking against Kotlin code.
 */
fun interface MenuHandler {
    suspend fun handle(ctx: MenuContext, node: MenuNode): MenuResult
}

sealed interface MenuResult {
    /** Action completed; stay on the current frame. */
    data object Consumed : MenuResult

    /** Push a frame. [nodes] short-circuits catalog resolution for inline submenus. */
    data class Open(
        val menuId: String,
        val payload: MenuPayload = MenuPayload.None,
        val title: String? = null,
        val nodes: List<MenuNode>? = null,
    ) : MenuResult

    /** Show a transient message (snackbar/toast). */
    data class Message(
        val text: String,
        val isError: Boolean = false,
        /** Pop the current menu after showing the message (a row that no longer exists). */
        val pop: Boolean = false,
    ) : MenuResult

    /**
     * Ask for one line of text. The UI shows the dialog and re-dispatches [handler] with
     * the entered text as `MenuNode.value`, so the handler stays the only place that knows
     * what to do with the answer.
     */
    data class Prompt(
        val title: String,
        val hint: String? = null,
        val initial: String = "",
        val handler: String,
        val payload: MenuPayload = MenuPayload.None,
        /** Fields whose empty value is meaningful (optional options) must accept blank. */
        val allowEmpty: Boolean = false,
    ) : MenuResult

    /**
     * Ask yes/no before running a destructive action. On confirmation the UI dispatches
     * [handler] with this payload and value, which is how the handler tells "asked" from
     * "confirmed" without keeping state.
     */
    data class Confirm(
        val message: String,
        val handler: String,
        val payload: MenuPayload = MenuPayload.None,
        val value: String? = null,
    ) : MenuResult

    /**
     * Ask the platform for a folder (`ACTION_OPEN_DOCUMENT_TREE`). The chosen tree URI
     * comes back as `MenuNode.value` exactly like a prompt's text, so the handler is the
     * only place that decides what a folder means; cancelling dispatches nothing.
     */
    data class PickFolder(
        val handler: String,
        val payload: MenuPayload = MenuPayload.None,
    ) : MenuResult

    /**
     * Ask the platform for a document (`ACTION_OPEN_DOCUMENT`). Used by
     * `cookies.txt` import; the answer arrives as `MenuNode.value` (the document URI) with
     * read permission granted for the session.
     */
    data class PickFile(
        val handler: String,
        val payload: MenuPayload = MenuPayload.None,
        val mimeTypes: List<String> = listOf("*/*"),
    ) : MenuResult

    data object Exit : MenuResult

    /** No handler is registered for the requested id; the UI degrades gracefully. */
    data class Unhandled(val handlerId: String) : MenuResult
}

class MenuHandlerRegistry(private val handlers: Map<String, MenuHandler>) {

    fun handlerFor(id: String?): MenuHandler? = id?.let(handlers::get)

    fun ids(): Set<String> = handlers.keys

    operator fun plus(other: MenuHandlerRegistry): MenuHandlerRegistry =
        MenuHandlerRegistry(handlers + other.handlers)

    class Builder {
        private val handlers = LinkedHashMap<String, MenuHandler>()

        fun register(id: String, handler: MenuHandler): Builder {
            require(id.isNotBlank()) { "handler id must not be blank" }
            handlers[id] = handler
            return this
        }

        fun build(): MenuHandlerRegistry = MenuHandlerRegistry(handlers.toMap())
    }

    companion object {
        fun empty(): MenuHandlerRegistry = MenuHandlerRegistry(emptyMap())
    }
}

object HandlerIds {
    const val BACK = "app.back"
    const val EXIT = "app.exit"
    const val OPEN_URL = "app.open_url"
    const val COPY_URL = "app.copy_url"
    const val SHARE_URL = "app.share_url"
    const val ABOUT = "app.about"
    const val TOGGLE_CONFIG = "config.toggle"
    const val SELECT_CONFIG = "config.select"
    const val SELECT_THEME = "theme.select"
    const val SELECT_LANG = "lang.select"
    const val OPEN_CONFIG = "config.open_external"

    /** Pushes whatever `MenuPayload` a node carries; the payload decides the menu. */
    const val OPEN_PAYLOAD = "app.open_payload"

    /** Pushes the actions menu for the item a row carries (media, playlist, channel). */
    const val OPEN_ACTIONS = "app.open_actions"

    // -- local library (F4) ----------------------------------------------------------

    const val MEDIA_SAVE = "library.media.save"
    const val MEDIA_UNSAVE = "library.media.unsave"
    const val PLAYLIST_ADD_ITEM = "library.playlist.add_item"
    const val PLAYLIST_REMOVE_ITEM = "library.playlist.remove_item"
    const val PLAYLIST_CREATE = "library.playlist.create"
    const val PLAYLIST_RENAME = "library.playlist.rename"
    const val PLAYLIST_DELETE = "library.playlist.delete"
    const val PLAYLIST_SAVE_REMOTE = "library.playlist.save_remote"
    const val PLAYLIST_UNSAVE_REMOTE = "library.playlist.unsave_remote"
    const val CHANNEL_SUBSCRIBE = "library.channel.subscribe"
    const val CHANNEL_UNSUBSCRIBE = "library.channel.unsubscribe"
    const val SUBS_SYNC = "library.subs.sync"
    const val HISTORY_CLEAR_SEARCH = "library.history.clear_search"
    const val HISTORY_CLEAR_RECENT = "library.history.clear_recent"

    // -- playback (F5) ---------------------------------------------------------------

    const val MEDIA_WATCH = "player.watch"
    const val MEDIA_LISTEN = "player.listen"
    const val PLAYLIST_PLAY_ALL = "player.play_all"
    const val PLAYLIST_LISTEN_ALL = "player.listen_all"
    const val MEDIA_MIX = "player.mix"
    const val MEDIA_EXTERNAL = "player.external"
    const val MEDIA_SHELL = "player.shell"

    // -- downloads (F6) --------------------------------------------------------------

    const val DOWNLOADS_ENQUEUE = "downloads.enqueue"
    const val DOWNLOADS_ENQUEUE_AUDIO = "downloads.enqueue_audio"
    const val DOWNLOADS_ENQUEUE_PLAYLIST = "downloads.enqueue_playlist"
    const val DOWNLOADS_ENQUEUE_PLAYLIST_AUDIO = "downloads.enqueue_playlist_audio"
    const val DOWNLOAD_TASK_PAUSE = "downloads.task.pause"
    const val DOWNLOAD_TASK_RESUME = "downloads.task.resume"
    const val DOWNLOAD_TASK_CANCEL = "downloads.task.cancel"
    const val DOWNLOAD_TASK_RETRY = "downloads.task.retry"
    const val DOWNLOAD_TASK_REMOVE = "downloads.task.remove"
    const val DOWNLOAD_FILE_PLAY = "downloads.file.play"
    const val DOWNLOAD_FILE_SHARE = "downloads.file.share"
    const val DOWNLOAD_FILE_OPEN = "downloads.file.open"
    const val DOWNLOAD_FILE_DELETE = "downloads.file.delete"
    const val DOWNLOADS_CLEAR_FINISHED = "downloads.clear_finished"
    const val DOWNLOADS_REFRESH = "downloads.refresh"
    const val DOWNLOADS_OPEN_FOLDER = "downloads.open_folder"
    const val DOWNLOADS_PICK_FOLDER = "downloads.pick_folder"
    const val DOWNLOADS_SET_FOLDER = "downloads.set_folder"

    // -- custom commands, sites and extensions (F7) ----------------------------------

    const val CUSTOM_CMD_NEW = "commands.new"
    const val CUSTOM_CMD_RUN = "commands.run"
    const val CUSTOM_CMD_EDIT = "commands.edit"
    const val CUSTOM_CMD_DELETE = "commands.delete"
    const val CUSTOM_CMD_SHELL = "commands.shell"
    const val SITE_RUN = "sites.run"

    const val EXTENSIONS_TOGGLE = "extensions.toggle"
    const val EXTENSIONS_PICK = "extensions.pick"
    const val EXTENSIONS_CLEAR = "extensions.clear"
    const val EXTENSIONS_AUTOLOAD = "extensions.autoload"
    const val EXTENSIONS_RELOAD = "extensions.reload"

    // -- accounts, updater and runtime settings (F7) ----------------------------------

    const val COOKIES_IMPORT = "cookies.import"
    const val COOKIES_LOGIN = "cookies.login"
    const val COOKIES_CLEAR = "cookies.clear"
    const val YTDLP_UPDATE = "ytdlp.update"
    const val UPDATE_CHECK = "update.check"
    const val SELECT_CACHE = "config.select_cache"
    const val UPDATE_OPEN = "update.open"
    const val CACHE_CLEAR = "cache.clear"
    const val CONFIG_OPEN_EDITOR = "config.open_editor"
    const val CONFIG_OPEN_EXTERNAL = "config.open_external_app"

    /**
     * A row that F5/F6 will wire to the player or the downloader. It answers with the
     * localized label so the user learns what is missing instead of getting a dead tap.
     */
    const val PENDING = "app.pending"
}
