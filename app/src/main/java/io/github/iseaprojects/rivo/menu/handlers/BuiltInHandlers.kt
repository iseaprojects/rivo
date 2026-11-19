package io.github.iseaprojects.rivo.menu.handlers

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.iseaprojects.rivo.BuildConfig
import io.github.iseaprojects.rivo.core.commands.CustomCommandStore
import io.github.iseaprojects.rivo.core.config.ConfigKeys
import io.github.iseaprojects.rivo.core.config.ConfigStore
import io.github.iseaprojects.rivo.core.menu.HandlerIds
import io.github.iseaprojects.rivo.core.menu.MenuContext
import io.github.iseaprojects.rivo.core.menu.MenuHandler
import io.github.iseaprojects.rivo.core.menu.MenuHandlerRegistry
import io.github.iseaprojects.rivo.core.menu.MenuIds
import io.github.iseaprojects.rivo.core.menu.MenuNode
import io.github.iseaprojects.rivo.core.menu.MenuPayload
import io.github.iseaprojects.rivo.core.menu.MenuResult
import io.github.iseaprojects.rivo.core.menu.extension.ExtensionRepository
import io.github.iseaprojects.rivo.core.update.UpdateRepository
import io.github.iseaprojects.rivo.data.download.DownloadKind
import io.github.iseaprojects.rivo.data.download.DownloadRepository
import io.github.iseaprojects.rivo.data.extract.ExtractorRouter
import io.github.iseaprojects.rivo.data.library.LibraryRepository
import io.github.iseaprojects.rivo.data.net.CookieRepository
import io.github.iseaprojects.rivo.player.PlaybackActions

/**
 * Handlers that need no player: navigation, config writes, URL actions, and the local
 * library (saved videos, custom playlists, subscriptions, history). Extraction-backed
 * actions are added by F5-F7.
 */
object BuiltInHandlers {

    fun registry(
        appContext: Context,
        config: ConfigStore,
        router: ExtractorRouter,
        library: LibraryRepository,
        playback: PlaybackActions,
        downloads: DownloadRepository,
        cookies: CookieRepository,
        commands: CustomCommandStore,
        extensions: ExtensionRepository,
        updates: UpdateRepository,
    ): MenuHandlerRegistry =
        MenuHandlerRegistry.Builder()
            .register(
                HandlerIds.BACK,
                object : MenuHandler {
                    override suspend fun handle(ctx: MenuContext, node: MenuNode): MenuResult =
                        if (ctx.navigator.back()) MenuResult.Consumed else MenuResult.Exit
                },
            )
            .register(
                HandlerIds.EXIT,
                object : MenuHandler {
                    override suspend fun handle(ctx: MenuContext, node: MenuNode): MenuResult =
                        MenuResult.Exit
                },
            )
            .register(HandlerIds.OPEN_URL, OpenUrlHandler(appContext))
            .register(HandlerIds.COPY_URL, CopyUrlHandler(appContext))
            .register(HandlerIds.SHARE_URL, ShareUrlHandler(appContext))
            .register(HandlerIds.ABOUT, AboutHandler())
            .register(HandlerIds.TOGGLE_CONFIG, ToggleConfigHandler(config))
            .register(HandlerIds.SELECT_CONFIG, SelectConfigHandler(config))
            .register(HandlerIds.SELECT_THEME, SelectThemeHandler(config))
            .register(HandlerIds.SELECT_LANG, SelectLanguageHandler(config))
            .register(HandlerIds.OPEN_CONFIG, OpenConfigFileHandler(appContext, config))
            .register(HandlerIds.OPEN_PAYLOAD, OpenPayloadHandler())
            .register(HandlerIds.OPEN_ACTIONS, OpenActionsHandler())
            .register(HandlerIds.PENDING, PendingActionHandler())
            .register(HandlerIds.MEDIA_SAVE, LibraryHandlers.save(library))
            .register(HandlerIds.MEDIA_UNSAVE, LibraryHandlers.unsave(library))
            .register(HandlerIds.PLAYLIST_ADD_ITEM, LibraryHandlers.addToPlaylist(library))
            .register(HandlerIds.PLAYLIST_REMOVE_ITEM, LibraryHandlers.removeFromPlaylist(library))
            .register(HandlerIds.PLAYLIST_CREATE, LibraryHandlers.createPlaylist(library))
            .register(HandlerIds.PLAYLIST_RENAME, LibraryHandlers.renamePlaylist(library))
            .register(HandlerIds.PLAYLIST_DELETE, LibraryHandlers.deletePlaylist(library))
            .register(
                HandlerIds.PLAYLIST_SAVE_REMOTE,
                LibraryHandlers.saveRemotePlaylist(library, save = true),
            )
            .register(
                HandlerIds.PLAYLIST_UNSAVE_REMOTE,
                LibraryHandlers.saveRemotePlaylist(library, save = false),
            )
            .register(HandlerIds.CHANNEL_SUBSCRIBE, LibraryHandlers.subscribe(library, true))
            .register(HandlerIds.CHANNEL_UNSUBSCRIBE, LibraryHandlers.subscribe(library, false))
            .register(HandlerIds.SUBS_SYNC, LibraryHandlers.syncSubscriptions(library, router))
            .register(HandlerIds.HISTORY_CLEAR_SEARCH, LibraryHandlers.clearSearchHistory(library))
            .register(HandlerIds.HISTORY_CLEAR_RECENT, LibraryHandlers.clearRecents(library))
            .register(HandlerIds.MEDIA_WATCH, PlaybackHandlers.watch(appContext, playback))
            .register(HandlerIds.MEDIA_LISTEN, PlaybackHandlers.listen(appContext, playback))
            .register(HandlerIds.PLAYLIST_PLAY_ALL, PlaybackHandlers.playAll(appContext, playback))
            .register(HandlerIds.PLAYLIST_LISTEN_ALL, PlaybackHandlers.listenAll(appContext, playback))
            .register(HandlerIds.MEDIA_MIX, PlaybackHandlers.mix(appContext, playback))
            .register(HandlerIds.MEDIA_EXTERNAL, PlaybackHandlers.external(appContext, playback))
            .register(HandlerIds.MEDIA_SHELL, PlaybackHandlers.shell(appContext, playback))
            .register(
                HandlerIds.DOWNLOADS_ENQUEUE,
                DownloadHandlers.enqueue(appContext, downloads, router, DownloadKind.VIDEO),
            )
            .register(
                HandlerIds.DOWNLOADS_ENQUEUE_AUDIO,
                DownloadHandlers.enqueue(appContext, downloads, router, DownloadKind.AUDIO),
            )
            .register(
                HandlerIds.DOWNLOADS_ENQUEUE_PLAYLIST,
                DownloadHandlers.enqueue(appContext, downloads, router, DownloadKind.VIDEO),
            )
            .register(
                HandlerIds.DOWNLOADS_ENQUEUE_PLAYLIST_AUDIO,
                DownloadHandlers.enqueue(appContext, downloads, router, DownloadKind.AUDIO),
            )
            .register(HandlerIds.DOWNLOAD_TASK_PAUSE, DownloadHandlers.pause(downloads))
            .register(HandlerIds.DOWNLOAD_TASK_RESUME, DownloadHandlers.resume(downloads))
            .register(HandlerIds.DOWNLOAD_TASK_CANCEL, DownloadHandlers.cancel(downloads))
            .register(HandlerIds.DOWNLOAD_TASK_RETRY, DownloadHandlers.retry(downloads))
            .register(HandlerIds.DOWNLOAD_TASK_REMOVE, DownloadHandlers.remove(downloads))
            .register(HandlerIds.DOWNLOAD_FILE_PLAY, DownloadHandlers.play(appContext, downloads, playback))
            .register(HandlerIds.DOWNLOAD_FILE_OPEN, DownloadHandlers.openFile(appContext))
            .register(HandlerIds.DOWNLOAD_FILE_SHARE, DownloadHandlers.shareFile(appContext))
            .register(HandlerIds.DOWNLOAD_FILE_DELETE, DownloadHandlers.deleteFile(appContext, downloads))
            .register(HandlerIds.DOWNLOADS_CLEAR_FINISHED, DownloadHandlers.clearFinished(downloads))
            .register(HandlerIds.DOWNLOADS_REFRESH, DownloadHandlers.refresh(downloads))
            .register(HandlerIds.DOWNLOADS_OPEN_FOLDER, DownloadHandlers.openFolder(appContext, downloads))
            .register(
                HandlerIds.DOWNLOADS_PICK_FOLDER,
                DownloadHandlers.setFolder(appContext, config, downloads),
            )
            .register(
                HandlerIds.DOWNLOADS_SET_FOLDER,
                DownloadHandlers.setFolder(appContext, config, downloads),
            )
            .register(HandlerIds.CUSTOM_CMD_NEW, CommandHandlers.create(commands))
            .register(HandlerIds.CUSTOM_CMD_EDIT, CommandHandlers.edit(commands))
            .register(HandlerIds.CUSTOM_CMD_DELETE, CommandHandlers.delete(commands))
            .register(HandlerIds.CUSTOM_CMD_RUN, CommandHandlers.run(router))
            .register(HandlerIds.CUSTOM_CMD_SHELL, CommandHandlers.shell(appContext, router))
            .register(HandlerIds.SITE_RUN, CommandHandlers.site(router))
            .register(
                HandlerIds.EXTENSIONS_TOGGLE,
                SettingsHandlers.toggleExtensions(config, extensions),
            )
            .register(
                HandlerIds.EXTENSIONS_PICK,
                SettingsHandlers.pickExtensions(appContext, extensions),
            )
            .register(
                HandlerIds.EXTENSIONS_AUTOLOAD,
                SettingsHandlers.autoloadExtensions(config, extensions),
            )
            .register(
                HandlerIds.EXTENSIONS_RELOAD,
                SettingsHandlers.reloadExtensions(commands, extensions),
            )
            .register(HandlerIds.EXTENSIONS_CLEAR, SettingsHandlers.clearExtensions(extensions))
            .register(HandlerIds.COOKIES_IMPORT, SettingsHandlers.importCookies(cookies))
            .register(HandlerIds.COOKIES_LOGIN, SettingsHandlers.loginCookies(appContext))
            .register(HandlerIds.COOKIES_CLEAR, SettingsHandlers.clearCookies(cookies))
            .register(HandlerIds.YTDLP_UPDATE, SettingsHandlers.updateYtdlp(appContext))
            .register(HandlerIds.UPDATE_CHECK, SettingsHandlers.checkUpdates(updates))
            .register(HandlerIds.CACHE_CLEAR, SettingsHandlers.clearCache(appContext))
            .register(
                HandlerIds.SELECT_CACHE,
                SettingsHandlers.selectCacheRetention(appContext, config),
            )
            .register(HandlerIds.CONFIG_OPEN_EDITOR, SettingsHandlers.openConfigEditor(appContext))
            .register(HandlerIds.CONFIG_OPEN_EXTERNAL, OpenConfigFileHandler(appContext, config))
            .build()
}

class OpenUrlHandler(private val appContext: Context) : MenuHandler {
    override suspend fun handle(ctx: MenuContext, node: MenuNode): MenuResult {
        val url = urlOf(node) ?: return MenuResult.Unhandled(HandlerIds.OPEN_URL)
        return openUrl(appContext, url, ctx)
    }
}

class CopyUrlHandler(private val appContext: Context) : MenuHandler {
    override suspend fun handle(ctx: MenuContext, node: MenuNode): MenuResult {
        val url = urlOf(node) ?: return MenuResult.Unhandled(HandlerIds.COPY_URL)
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return MenuResult.Message(
                ctx.strings.text("TXT_MENU_MEDIA_ACTIONS_COPY_VIDEO_URL_ERROR"),
                isError = true,
            )
        clipboard.setPrimaryClip(ClipData.newPlainText(node.label, url))
        return MenuResult.Message(ctx.strings.text("TXT_ANDROID_COPIED"))
    }
}

class ShareUrlHandler(private val appContext: Context) : MenuHandler {
    override suspend fun handle(ctx: MenuContext, node: MenuNode): MenuResult {
        val url = urlOf(node) ?: return MenuResult.Unhandled(HandlerIds.SHARE_URL)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            putExtra(Intent.EXTRA_TITLE, node.label)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(
                Intent.createChooser(send, ctx.strings.text("TXT_ANDROID_SHARE"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            MenuResult.Consumed
        } catch (_: ActivityNotFoundException) {
            MenuResult.Message(ctx.strings.text("TXT_ANDROID_NO_HANDLER"), isError = true)
        }
    }
}

class AboutHandler : MenuHandler {
    override suspend fun handle(ctx: MenuContext, node: MenuNode): MenuResult {
        val version = ctx.strings.format("TXT_ANDROID_VERSION_FMT", BuildConfig.VERSION_NAME)
        val upstream = ctx.strings.text("TXT_ANDROID_UPSTREAM_VERSION")
        val extractor = ctx.strings.text("TXT_ANDROID_EXTRACTOR")
        val text = buildString {
            appendLine(version)
            appendLine("$upstream: ${BuildConfig.UPSTREAM_VERSION}")
            appendLine("$extractor: ${BuildConfig.EXTRACTOR_FLAVOR}")
            append("MIT · ${BuildConfig.UPSTREAM_REPO}")
        }
        return MenuResult.Message(text)
    }
}

class ToggleConfigHandler(private val config: ConfigStore) : MenuHandler {
    override suspend fun handle(ctx: MenuContext, node: MenuNode): MenuResult {
        val key = node.toggleKey ?: return MenuResult.Unhandled(HandlerIds.TOGGLE_CONFIG)
        val next = !ctx.config.bool(key, false)
        config.setBoolean(key, next)
        return MenuResult.Consumed
    }
}

class SelectConfigHandler(private val config: ConfigStore) : MenuHandler {
    override suspend fun handle(ctx: MenuContext, node: MenuNode): MenuResult {
        val key = (node.payload as? MenuPayload.ConfigValue)?.key
            ?: node.toggleKey
            ?: return MenuResult.Unhandled(HandlerIds.SELECT_CONFIG)
        val value = node.value ?: return MenuResult.Unhandled(HandlerIds.SELECT_CONFIG)
        config.set(key, value)
        popChoice(ctx)
        return MenuResult.Consumed
    }
}

class SelectThemeHandler(private val config: ConfigStore) : MenuHandler {
    override suspend fun handle(ctx: MenuContext, node: MenuNode): MenuResult {
        val id = node.value ?: (node.payload as? MenuPayload.Text)?.value
            ?: return MenuResult.Unhandled(HandlerIds.SELECT_THEME)
        config.set(ConfigKeys.THEME, id)
        popChoice(ctx)
        return MenuResult.Consumed
    }
}

class SelectLanguageHandler(private val config: ConfigStore) : MenuHandler {
    override suspend fun handle(ctx: MenuContext, node: MenuNode): MenuResult {
        val tag = node.value ?: (node.payload as? MenuPayload.Text)?.value
            ?: return MenuResult.Unhandled(HandlerIds.SELECT_LANG)
        config.set(ConfigKeys.LANG, tag)
        popChoice(ctx)
        return MenuResult.Consumed
    }
}

/** Choosing a value closes the choice screen, like yt-x returning to the menu it came from. */
private fun popChoice(ctx: MenuContext) {
    if (ctx.menuId == MenuIds.CHOICE) ctx.navigator.back()
}

/**
 * Hands the plain-text config to any installed text editor through FileProvider.
 * The in-app editor (F7) writes the same file, so external edits show up after
 * [ConfigStore.reloadBlocking] on resume.
 */
class OpenConfigFileHandler(
    private val appContext: Context,
    private val config: ConfigStore,
) : MenuHandler {
    override suspend fun handle(ctx: MenuContext, node: MenuNode): MenuResult {
        val file = config.configFile
        if (!file.exists()) {
            return MenuResult.Message("${ctx.strings.text("TXT_ANDROID_CONFIG_PATH")}: missing", isError = true)
        }
        return try {
            val uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.files",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "text/plain")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(
                Intent.createChooser(intent, ctx.strings.text("TXT_ANDROID_CFG_EDITOR"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
            MenuResult.Consumed
        } catch (_: IllegalArgumentException) {
            MenuResult.Message(ctx.strings.text("TXT_ANDROID_NO_HANDLER"), isError = true)
        } catch (_: ActivityNotFoundException) {
            MenuResult.Message(ctx.strings.text("TXT_ANDROID_NO_HANDLER"), isError = true)
        }
    }
}

private fun urlOf(node: MenuNode): String? =
    (node.payload as? MenuPayload.Url)?.url
        ?: node.value?.takeIf { it.startsWith("http", ignoreCase = true) || it.startsWith("rivo://") }
        ?: node.detail?.takeIf { it.startsWith("http", ignoreCase = true) }

/**
 * Pushes the payload a row carries: "Go To Channel", "Mix", a channel tab, a search
 * result. One handler covers all of them because the payload type alone says which menu
 * renders it, which is also what lets an extension link to those screens.
 */
class OpenPayloadHandler : MenuHandler {
    override suspend fun handle(ctx: MenuContext, node: MenuNode): MenuResult {
        val payload = node.payload
        val menuId = when (payload) {
            is MenuPayload.Channel -> MenuIds.CHANNEL
            is MenuPayload.Playlist -> MenuIds.PLAYLIST
            is MenuPayload.Media -> MenuIds.MEDIA_ACTIONS
            is MenuPayload.Search -> MenuIds.SEARCH
            is MenuPayload.Feed -> MenuIds.FEED
            is MenuPayload.Library -> MenuIds.LIBRARY
            is MenuPayload.LocalPlaylist -> MenuIds.LOCAL_PLAYLIST
            is MenuPayload.Url, is MenuPayload.ConfigValue, is MenuPayload.Text,
            is MenuPayload.Download, is MenuPayload.DownloadFile,
            is MenuPayload.Command, is MenuPayload.CommandDraft,
            is MenuPayload.SiteActionRef -> null
            MenuPayload.None -> null
        } ?: return MenuResult.Unhandled(HandlerIds.OPEN_PAYLOAD)

        val title = when (payload) {
            is MenuPayload.Channel -> payload.summary.name
            is MenuPayload.Playlist -> payload.summary.title
            is MenuPayload.Media -> payload.item.title
            is MenuPayload.Search -> payload.query
            is MenuPayload.LocalPlaylist -> payload.title
            else -> null
        }
        return MenuResult.Open(menuId = menuId, payload = payload, title = title)
    }
}

/**
 * Pushes the actions menu for whatever a row carries, keeping the item's name as the
 * frame title.
 *
 * `OpenPayloadHandler` navigates *into* a payload (channel screen, playlist contents,
 * search); this one goes to the action list. Library screens use it for rows that are
 * objects to manage rather than lists to open — a saved playlist, a subscription.
 */
class OpenActionsHandler : MenuHandler {
    override suspend fun handle(ctx: MenuContext, node: MenuNode): MenuResult =
        when (val payload = node.payload) {
            is MenuPayload.Media -> MenuResult.Open(
                menuId = MenuIds.MEDIA_ACTIONS,
                payload = payload,
                title = payload.item.title,
            )

            is MenuPayload.Playlist -> MenuResult.Open(
                menuId = MenuIds.MEDIA_ACTIONS,
                payload = payload,
                title = payload.summary.title,
            )

            is MenuPayload.Channel -> MenuResult.Open(
                menuId = MenuIds.CHANNEL_ACTIONS,
                payload = payload,
                title = payload.summary.name,
            )

            is MenuPayload.LocalPlaylist -> MenuResult.Open(
                menuId = MenuIds.LOCAL_PLAYLIST_ACTIONS,
                payload = payload,
                title = payload.title,
            )

            else -> MenuResult.Unhandled(HandlerIds.OPEN_ACTIONS)
        }
}

/**
 * A row whose implementation lands in F5/F6 (playback, downloads, library writes).
 * Naming the label is the honest answer: the action exists in the contract and the user
 * sees which one it is, instead of a tap that silently does nothing.
 */
class PendingActionHandler : MenuHandler {
    override suspend fun handle(ctx: MenuContext, node: MenuNode): MenuResult = MenuResult.Message(
        ctx.strings.text("TXT_ANDROID_HANDLER_MISSING") + ": " + node.label,
    )
}

private fun openUrl(appContext: Context, url: String, ctx: MenuContext): MenuResult = try {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    appContext.startActivity(intent)
    MenuResult.Consumed
} catch (_: ActivityNotFoundException) {
    MenuResult.Message(
        ctx.strings.text("TXT_MENU_MEDIA_ACTIONS_OPEN_IN_BROWSER_ERROR"),
        isError = true,
    )
}
