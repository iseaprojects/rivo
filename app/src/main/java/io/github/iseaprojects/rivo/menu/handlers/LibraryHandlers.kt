package io.github.iseaprojects.rivo.menu.handlers

import io.github.iseaprojects.rivo.core.menu.HandlerIds
import io.github.iseaprojects.rivo.core.menu.MenuContext
import io.github.iseaprojects.rivo.core.menu.MenuHandler
import io.github.iseaprojects.rivo.core.menu.MenuNode
import io.github.iseaprojects.rivo.core.menu.MenuPayload
import io.github.iseaprojects.rivo.core.menu.MenuResult
import io.github.iseaprojects.rivo.core.model.MediaSummary
import io.github.iseaprojects.rivo.data.extract.AccountFeed
import io.github.iseaprojects.rivo.data.extract.ExtractorException
import io.github.iseaprojects.rivo.data.extract.ExtractorRouter
import io.github.iseaprojects.rivo.data.library.LibraryRepository

/**
 * Every handler that writes to the local library.
 *
 * They share one rule: a destructive action first answers with [MenuResult.Confirm], and
 * only runs when the UI re-dispatches it with [CONFIRMED] as the node value. The handler
 * therefore keeps no state between the dialog and the tap, and a rotation — or a process
 * death — cannot turn a question into an execution.
 */
object LibraryHandlers {

    /** Value a confirmed dialog re-dispatches with. */
    const val CONFIRMED = "confirmed"

    fun save(library: LibraryRepository): MenuHandler = object : MenuHandler {
        override suspend fun handle(ctx: MenuContext, node: MenuNode): MenuResult {
            val media = mediaOf(node) ?: return MenuResult.Unhandled(HandlerIds.MEDIA_SAVE)
            return if (library.save(media)) {
                MenuResult.Message(ctx.strings.text("TXT_ANDROID_SAVED_TO_LIBRARY"))
            } else {
                MenuResult.Message(ctx.strings.text("TXT_ANDROID_ALREADY_SAVED"))
            }
        }
    }

    fun unsave(library: LibraryRepository): MenuHandler = object : MenuHandler {
        override suspend fun handle(ctx: MenuContext, node: MenuNode): MenuResult {
            val media = mediaOf(node) ?: return MenuResult.Unhandled(HandlerIds.MEDIA_UNSAVE)
            library.unsave(media)
            return MenuResult.Message(ctx.strings.text("TXT_ANDROID_UNSAVED"))
        }
    }

    fun addToPlaylist(library: LibraryRepository): MenuHandler = object : MenuHandler {
        override suspend fun handle(ctx: MenuContext, node: MenuNode): MenuResult {
            val media = mediaOf(node) ?: return MenuResult.Unhandled(HandlerIds.PLAYLIST_ADD_ITEM)
            val id = node.value?.toLongOrNull()
                ?: return MenuResult.Unhandled(HandlerIds.PLAYLIST_ADD_ITEM)
            val title = library.playlistTitle(id) ?: return MenuResult.Message(
                ctx.strings.text("TXT_ANDROID_PLAYLIST_MISSING"),
                isError = true,
            )
            val added = library.addToPlaylist(id, media)
            return MenuResult.Message(
                if (added) {
                    ctx.strings.format("TXT_ANDROID_PLAYLIST_ADDED", title)
                } else {
                    ctx.strings.format("TXT_ANDROID_PLAYLIST_ALREADY", title)
                },
            )
        }
    }

    fun removeFromPlaylist(library: LibraryRepository): MenuHandler = object : MenuHandler {
        override suspend fun handle(ctx: MenuContext, node: MenuNode): MenuResult {
            val payload = node.payload as? MenuPayload.Media
                ?: return MenuResult.Unhandled(HandlerIds.PLAYLIST_REMOVE_ITEM)
            val id = payload.collectionId
                ?: return MenuResult.Unhandled(HandlerIds.PLAYLIST_REMOVE_ITEM)
            library.removeFromPlaylist(id, payload.item)
            return MenuResult.Message(ctx.strings.text("TXT_ANDROID_PLAYLIST_ITEM_REMOVED"))
        }
    }

    /**
     * "New playlist" first asks for a name, then creates it. When the row carries a media
     * item (the picker's own "New playlist"), the item is added right after creation so
     * the user does not have to find it again.
     */
    fun createPlaylist(library: LibraryRepository): MenuHandler = object : MenuHandler {
        override suspend fun handle(ctx: MenuContext, node: MenuNode): MenuResult {
            val title = node.value?.trim().orEmpty()
            if (title.isEmpty()) {
                return MenuResult.Prompt(
                    title = ctx.strings.text("TXT_ANDROID_PLAYLIST_NEW"),
                    hint = ctx.strings.text("TXT_ENTER_PLAYLIST_TITLE"),
                    handler = HandlerIds.PLAYLIST_CREATE,
                    payload = node.payload,
                )
            }
            val id = library.createPlaylist(title)
            mediaOf(node)?.let { media -> library.addToPlaylist(id, media) }
            return MenuResult.Message(ctx.strings.format("TXT_ANDROID_PLAYLIST_CREATED", title))
        }
    }

    fun renamePlaylist(library: LibraryRepository): MenuHandler = object : MenuHandler {
        override suspend fun handle(ctx: MenuContext, node: MenuNode): MenuResult {
            val payload = node.payload as? MenuPayload.LocalPlaylist
                ?: return MenuResult.Unhandled(HandlerIds.PLAYLIST_RENAME)
            // The payload is a snapshot; a rename earlier in the same session would make
            // the prompt show the old name, so the live title wins.
            val current = library.playlistTitle(payload.id) ?: payload.title
            val title = node.value?.trim().orEmpty()
            if (title.isEmpty()) {
                return MenuResult.Prompt(
                    title = ctx.strings.text("TXT_ANDROID_PLAYLIST_RENAME"),
                    hint = ctx.strings.text("TXT_ENTER_PLAYLIST_TITLE"),
                    initial = current,
                    handler = HandlerIds.PLAYLIST_RENAME,
                    payload = payload,
                )
            }
            library.renamePlaylist(payload.id, title)
            return MenuResult.Message(ctx.strings.format("TXT_ANDROID_PLAYLIST_RENAMED", title))
        }
    }

    fun deletePlaylist(library: LibraryRepository): MenuHandler = object : MenuHandler {
        override suspend fun handle(ctx: MenuContext, node: MenuNode): MenuResult {
            val payload = node.payload as? MenuPayload.LocalPlaylist
                ?: return MenuResult.Unhandled(HandlerIds.PLAYLIST_DELETE)
            val title = library.playlistTitle(payload.id) ?: payload.title
            if (node.value != CONFIRMED) {
                return MenuResult.Confirm(
                    message = ctx.strings.format("TXT_ANDROID_PLAYLIST_DELETE_CONFIRM", title),
                    handler = HandlerIds.PLAYLIST_DELETE,
                    payload = payload,
                    value = CONFIRMED,
                )
            }
            library.deletePlaylist(payload.id)
            ctx.navigator.back()
            return MenuResult.Message(ctx.strings.format("TXT_ANDROID_PLAYLIST_DELETED", title))
        }
    }

    fun saveRemotePlaylist(library: LibraryRepository, save: Boolean): MenuHandler =
        object : MenuHandler {
            override suspend fun handle(ctx: MenuContext, node: MenuNode): MenuResult {
                val playlist = (node.payload as? MenuPayload.Playlist)?.summary
                    ?: return MenuResult.Unhandled(HandlerIds.PLAYLIST_SAVE_REMOTE)
                return if (save) {
                    library.savePlaylist(playlist)
                    MenuResult.Message(ctx.strings.text("TXT_ANDROID_PLAYLIST_SAVED"))
                } else {
                    library.unsavePlaylist(playlist)
                    MenuResult.Message(ctx.strings.text("TXT_ANDROID_PLAYLIST_UNSAVED"))
                }
            }
        }

    fun subscribe(library: LibraryRepository, subscribe: Boolean): MenuHandler =
        object : MenuHandler {
            private val handlerId =
                if (subscribe) HandlerIds.CHANNEL_SUBSCRIBE else HandlerIds.CHANNEL_UNSUBSCRIBE

            override suspend fun handle(ctx: MenuContext, node: MenuNode): MenuResult {
                val channel = (node.payload as? MenuPayload.Channel)?.summary
                    ?: return MenuResult.Unhandled(handlerId)
                return if (subscribe) {
                    library.subscribe(channel)
                    MenuResult.Message(
                        ctx.strings.format("TXT_ANDROID_SUBSCRIBED", channel.name),
                    )
                } else {
                    library.unsubscribe(channel)
                    MenuResult.Message(
                        ctx.strings.format("TXT_ANDROID_UNSUBSCRIBED", channel.name),
                    )
                }
            }
        }

    /**
     * Imports the account's subscriptions from `/feed/channels`.
     *
     * The replacement is destructive (it erases whatever the user added by hand), so it is
     * confirmed first, and an empty result never wipes anything.
     */
    fun syncSubscriptions(
        library: LibraryRepository,
        router: ExtractorRouter,
    ): MenuHandler = object : MenuHandler {
        override suspend fun handle(ctx: MenuContext, node: MenuNode): MenuResult {
            if (node.value != CONFIRMED) {
                return MenuResult.Confirm(
                    message = ctx.strings.text("TXT_MENU_MISC_SYNC_SUBS_CONFIRM"),
                    handler = HandlerIds.SUBS_SYNC,
                    value = CONFIRMED,
                )
            }
            return try {
                val page = router.accountFeed(AccountFeed.CHANNELS, token = null)
                val channels = page.channels.filter {
                    it.id.isNotBlank() || it.url.isNotBlank()
                }
                val count = library.replaceSubscriptions(channels)
                if (count == 0) {
                    MenuResult.Message(ctx.strings.text("TXT_ANDROID_SYNC_EMPTY"), isError = true)
                } else {
                    MenuResult.Message(ctx.strings.format("TXT_ANDROID_SYNC_DONE", count))
                }
            } catch (error: ExtractorException) {
                MenuResult.Message(
                    ctx.strings.text("TXT_MENU_MISC_SYNC_SUBS_FAILED") + ": " + error.message,
                    isError = true,
                )
            }
        }
    }

    fun clearSearchHistory(library: LibraryRepository): MenuHandler = object : MenuHandler {
        override suspend fun handle(ctx: MenuContext, node: MenuNode): MenuResult {
            if (node.value != CONFIRMED) {
                return MenuResult.Confirm(
                    message = ctx.strings.text("TXT_ANDROID_CLEAR_SEARCH_CONFIRM"),
                    handler = HandlerIds.HISTORY_CLEAR_SEARCH,
                    value = CONFIRMED,
                )
            }
            library.clearSearchHistory()
            ctx.navigator.back()
            return MenuResult.Message(ctx.strings.text("TXT_ANDROID_CLEARED"))
        }
    }

    fun clearRecents(library: LibraryRepository): MenuHandler = object : MenuHandler {
        override suspend fun handle(ctx: MenuContext, node: MenuNode): MenuResult {
            if (node.value != CONFIRMED) {
                return MenuResult.Confirm(
                    message = ctx.strings.text("TXT_ANDROID_CLEAR_RECENT_CONFIRM"),
                    handler = HandlerIds.HISTORY_CLEAR_RECENT,
                    value = CONFIRMED,
                )
            }
            library.clearRecents()
            ctx.navigator.back()
            return MenuResult.Message(ctx.strings.text("TXT_ANDROID_CLEARED"))
        }
    }

    private fun mediaOf(node: MenuNode): MediaSummary? = when (val payload = node.payload) {
        is MenuPayload.Media -> payload.item
        else -> null
    }
}
