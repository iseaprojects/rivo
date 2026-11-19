package io.github.iseaprojects.rivo.menu.handlers

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import io.github.iseaprojects.rivo.core.config.ConfigKeys
import io.github.iseaprojects.rivo.core.config.ConfigStore
import io.github.iseaprojects.rivo.core.menu.HandlerIds
import io.github.iseaprojects.rivo.core.menu.MenuHandler
import io.github.iseaprojects.rivo.core.menu.MenuIds
import io.github.iseaprojects.rivo.core.menu.MenuResult
import io.github.iseaprojects.rivo.core.menu.extension.ExtensionRepository
import io.github.iseaprojects.rivo.core.update.UpdateRepository
import io.github.iseaprojects.rivo.core.update.UpdateState
import io.github.iseaprojects.rivo.data.extract.UpdateOutcome
import io.github.iseaprojects.rivo.data.extract.YtdlpSupport
import io.github.iseaprojects.rivo.data.net.CookieRepository
import io.github.iseaprojects.rivo.platform.CacheCleaner
import io.github.iseaprojects.rivo.platform.CacheCleanupWorker
import io.github.iseaprojects.rivo.ui.login.WebLoginActivity
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings screens that act on more than one row: the SAF extension folder, the cookie
 * store, the extractor/runtime updater and the cache sweeper.
 *
 * Every handler re-reads state from its repository instead of the menu snapshot, so a
 * slow folder or a revoked grant surfaces as a message rather than as a stale row.
 */
object SettingsHandlers {

    // -- extensions -----------------------------------------------------------------

    fun toggleExtensions(
        config: ConfigStore,
        extensions: ExtensionRepository,
    ): MenuHandler = MenuHandler { ctx, node ->
        val next = !ctx.config.bool(ConfigKeys.EXTENSIONS_ENABLED, true)
        config.setBoolean(ConfigKeys.EXTENSIONS_ENABLED, next)
        extensions.reload()
        MenuResult.Consumed
    }

    fun pickExtensions(
        appContext: Context,
        extensions: ExtensionRepository,
    ): MenuHandler = MenuHandler { ctx, node ->
        val picked = node.value
        if (picked.isNullOrBlank()) {
            return@MenuHandler MenuResult.PickFolder(handler = HandlerIds.EXTENSIONS_PICK)
        }
        if (!picked.startsWith("content://")) {
            return@MenuHandler MenuResult.Message(
                ctx.strings.text("TXT_ANDROID_EXTENSIONS_BAD_FOLDER"),
                isError = true,
            )
        }
        // Read is all an extension folder needs; keeping it read-only means a misbehaving
        // extension can never write back into a folder the user picked for the app.
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                picked.toUri(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        extensions.setFolder(picked)
        MenuResult.Message(ctx.strings.text("TXT_ANDROID_EXTENSIONS_LOADING"))
    }

    fun autoloadExtensions(
        config: ConfigStore,
        extensions: ExtensionRepository,
    ): MenuHandler = MenuHandler { ctx, node ->
        val current = ctx.config.string(ConfigKeys.AUTOLOADED_EXTENSIONS)
        val answer = node.value
        if (answer == null) {
            return@MenuHandler MenuResult.Prompt(
                title = ctx.strings.text("TXT_ANDROID_EXTENSIONS_AUTOLOAD"),
                hint = ctx.strings.text("TXT_ANDROID_EXTENSIONS_AUTOLOAD_HINT"),
                initial = current,
                handler = HandlerIds.EXTENSIONS_AUTOLOAD,
            )
        }
        config.set(ConfigKeys.AUTOLOADED_EXTENSIONS, answer.trim())
        extensions.reload()
        MenuResult.Message(
            if (answer.isBlank()) {
                ctx.strings.text("TXT_ANDROID_EXTENSIONS_AUTOLOAD_ALL")
            } else {
                ctx.strings.format("TXT_ANDROID_EXTENSIONS_AUTOLOAD_SET_FMT", answer.trim())
            },
        )
    }

    fun reloadExtensions(
        commands: io.github.iseaprojects.rivo.core.commands.CustomCommandStore,
        extensions: ExtensionRepository,
    ): MenuHandler = MenuHandler { ctx, _ ->
        commands.reload()
        extensions.reload()
        val loaded = extensions.state.value.entries.size
        MenuResult.Message(
            if (loaded == 1) {
                ctx.strings.text("TXT_ANDROID_EXTENSIONS_LOADED_ONE")
            } else {
                ctx.strings.format("TXT_ANDROID_EXTENSIONS_LOADED_FMT", loaded)
            },
        )
    }

    fun clearExtensions(extensions: ExtensionRepository): MenuHandler = MenuHandler { ctx, _ ->
        extensions.setFolder(null)
        MenuResult.Message(ctx.strings.text("TXT_ANDROID_EXTENSIONS_CLEARED"))
    }

    // -- cookies --------------------------------------------------------------------

    fun importCookies(cookies: CookieRepository): MenuHandler = MenuHandler { ctx, node ->
        val picked = node.value
        if (picked.isNullOrBlank()) {
            return@MenuHandler MenuResult.PickFile(
                handler = HandlerIds.COOKIES_IMPORT,
                mimeTypes = listOf("text/plain", "text/*", "application/json"),
            )
        }
        when (val result = cookies.importFrom(picked.toUri())) {
            is CookieRepository.Result.Ok -> MenuResult.Message(
                ctx.strings.format(
                    "TXT_ANDROID_COOKIES_IMPORTED_FMT",
                    result.imported,
                    result.skipped,
                ),
            )

            is CookieRepository.Result.Failure -> MenuResult.Message(
                ctx.strings.format("TXT_ANDROID_COOKIES_IMPORT_FAILED_FMT", result.reason),
                isError = true,
            )
        }
    }

    fun loginCookies(appContext: Context): MenuHandler = MenuHandler { ctx, _ ->
        try {
            appContext.startActivity(
                Intent(appContext, WebLoginActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            MenuResult.Consumed
        } catch (_: ActivityNotFoundException) {
            MenuResult.Message(ctx.strings.text("TXT_ANDROID_NO_HANDLER"), isError = true)
        }
    }

    fun clearCookies(cookies: CookieRepository): MenuHandler = MenuHandler { ctx, node ->
        if (node.value == null) {
            return@MenuHandler MenuResult.Confirm(
                message = ctx.strings.text("TXT_ANDROID_COOKIES_CLEAR_CONFIRM"),
                handler = HandlerIds.COOKIES_CLEAR,
                value = "confirmed",
            )
        }
        cookies.clear()
        MenuResult.Message(ctx.strings.text("TXT_ANDROID_COOKIES_CLEARED"))
    }

    // -- updater and cache ----------------------------------------------------------

    fun updateYtdlp(appContext: Context): MenuHandler = MenuHandler { ctx, _ ->
        if (!YtdlpSupport.BUNDLED) {
            return@MenuHandler MenuResult.Message(
                ctx.strings.text("TXT_ANDROID_EXTRACTOR_FAST_ONLY"),
                isError = true,
            )
        }
        val channel = ctx.config.string(ConfigKeys.YTDLP_CHANNEL, "stable")
        val outcome = withContext(Dispatchers.IO) { YtdlpSupport.update(appContext, channel) }
        MenuResult.Message(
            text = when (outcome) {
                UpdateOutcome.UPDATED -> ctx.strings.format(
                    "TXT_ANDROID_EXTRACTOR_UPDATED_FMT",
                    channel,
                )

                UpdateOutcome.ALREADY_CURRENT -> ctx.strings.text("TXT_ANDROID_EXTRACTOR_CURRENT")
                UpdateOutcome.UNSUPPORTED -> ctx.strings.text("TXT_ANDROID_EXTRACTOR_FAST_ONLY")
                UpdateOutcome.FAILED -> ctx.strings.text("TXT_ANDROID_EXTRACTOR_UPDATE_FAILED")
            },
            isError = outcome == UpdateOutcome.FAILED || outcome == UpdateOutcome.UNSUPPORTED,
        )
    }

    fun checkUpdates(repository: UpdateRepository): MenuHandler = MenuHandler { ctx, _ ->
        repository.check(force = true)
        when (val state = repository.state.value) {
            is UpdateState.Available -> MenuResult.Message(
                ctx.strings.format(
                    "TXT_ANDROID_UPDATE_AVAILABLE_FMT",
                    state.release.tag.removePrefix("v"),
                ),
            )

            is UpdateState.Current -> MenuResult.Message(
                ctx.strings.format("TXT_ANDROID_UPDATE_UP_TO_DATE_FMT", state.version),
            )

            is UpdateState.Failed -> MenuResult.Message(
                ctx.strings.format("TXT_ANDROID_UPDATE_FAILED_FMT", state.message),
                isError = true,
            )

            UpdateState.Checking, UpdateState.Idle -> MenuResult.Consumed
        }
    }

    fun clearCache(appContext: Context): MenuHandler = MenuHandler { ctx, _ ->
        val result = CacheCleaner.clearAll(appContext)
        MenuResult.Message(
            ctx.strings.format(
                "TXT_ANDROID_CACHE_CLEARED_FMT",
                result.files,
                formatBytes(result.bytes),
            ),
        )
    }

    fun selectCacheRetention(
        appContext: Context,
        config: ConfigStore,
    ): MenuHandler = MenuHandler { ctx, node ->
        val days = node.value?.toIntOrNull() ?: return@MenuHandler MenuResult.Unhandled(
            HandlerIds.SELECT_CACHE,
        )
        config.set(ConfigKeys.CACHE_RETENTION_DAYS, days.toString())
        CacheCleanupWorker.schedule(appContext, days)
        if (ctx.menuId == MenuIds.CHOICE) ctx.navigator.back()
        MenuResult.Consumed
    }

    fun openConfigEditor(appContext: Context): MenuHandler = MenuHandler { ctx, _ ->
        try {
            appContext.startActivity(
                Intent(appContext, io.github.iseaprojects.rivo.ui.config.ConfigEditorActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            MenuResult.Consumed
        } catch (_: ActivityNotFoundException) {
            MenuResult.Message(ctx.strings.text("TXT_ANDROID_NO_HANDLER"), isError = true)
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L ->
            String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
