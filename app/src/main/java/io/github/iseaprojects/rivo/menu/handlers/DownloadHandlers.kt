package io.github.iseaprojects.rivo.menu.handlers

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.core.net.toUri
import io.github.iseaprojects.rivo.core.config.ConfigKeys
import io.github.iseaprojects.rivo.core.config.ConfigStore
import io.github.iseaprojects.rivo.core.menu.HandlerIds
import io.github.iseaprojects.rivo.core.menu.MenuContext
import io.github.iseaprojects.rivo.core.menu.MenuDownloadState
import io.github.iseaprojects.rivo.core.menu.MenuHandler
import io.github.iseaprojects.rivo.core.menu.MenuNode
import io.github.iseaprojects.rivo.core.menu.MenuPayload
import io.github.iseaprojects.rivo.core.menu.MenuResult
import io.github.iseaprojects.rivo.data.download.DownloadFolder
import io.github.iseaprojects.rivo.data.download.DownloadKind
import io.github.iseaprojects.rivo.data.download.DownloadNaming
import io.github.iseaprojects.rivo.data.download.DownloadRepository
import io.github.iseaprojects.rivo.data.download.EnqueueOutcome
import io.github.iseaprojects.rivo.data.extract.ExtractorRouter
import io.github.iseaprojects.rivo.player.LaunchOutcome
import io.github.iseaprojects.rivo.player.MediaLinkResolver
import io.github.iseaprojects.rivo.player.PlayMode
import io.github.iseaprojects.rivo.player.PlaybackActions
import io.github.iseaprojects.rivo.player.ResolvedLink
import io.github.iseaprojects.rivo.player.describe

/**
 * Downloader actions: enqueue, queue management, files and the folder itself.
 *
 * Enqueueing never waits for the download. The handler resolves only what is needed to
 * name the row (a pasted playlist link pages its items, because "Download all" means all
 * of it), persists the queue, starts the foreground service and answers with a count. The
 * actual transfer is the service's business, which is what keeps a 200-item playlist from
 * holding the menu for an hour.
 */
object DownloadHandlers {

    fun enqueue(
        appContext: Context,
        repository: DownloadRepository,
        router: ExtractorRouter,
        kind: DownloadKind,
    ): MenuHandler = MenuHandler { ctx, node ->
        when (val payload = node.payload) {
            is MenuPayload.Media -> report(
                ctx,
                repository.enqueue(
                    items = listOf(payload.item),
                    kind = kind,
                    playlistTitle = payload.item.playlistTitle ?: payload.collectionTitle,
                ),
            )

            is MenuPayload.Playlist -> report(ctx, repository.enqueuePlaylist(payload.summary, kind))

            is MenuPayload.Url -> when (val target = MediaLinkResolver.resolve(router, payload.url)) {
                is ResolvedLink.Video -> report(
                    ctx,
                    repository.enqueue(listOf(target.item), kind),
                )

                is ResolvedLink.Playlist -> report(ctx, repository.enqueuePlaylist(target.summary, kind))
                is ResolvedLink.Channel -> MenuResult.Message(
                    ctx.strings.text("TXT_ANDROID_DOWNLOAD_NOT_MEDIA"),
                    isError = true,
                )

                is ResolvedLink.Unreadable -> MenuResult.Message(
                    target.error.message ?: ctx.strings.text("TXT_ANDROID_LINK_UNREADABLE"),
                    isError = true,
                )

                null -> MenuResult.Message(
                    ctx.strings.text("TXT_ANDROID_DOWNLOAD_NOT_MEDIA"),
                    isError = true,
                )
            }

            else -> MenuResult.Unhandled(HandlerIds.DOWNLOADS_ENQUEUE)
        }
    }

    fun pause(repository: DownloadRepository): MenuHandler = MenuHandler { ctx, node ->
        val task = taskOf(ctx, node) ?: return@MenuHandler missingTask(ctx)
        if (task.state == MenuDownloadState.RUNNING || task.state == MenuDownloadState.QUEUED) {
            repository.pause(task.id)
        }
        MenuResult.Consumed
    }

    fun resume(repository: DownloadRepository): MenuHandler = MenuHandler { ctx, node ->
        val task = taskOf(ctx, node) ?: return@MenuHandler missingTask(ctx)
        repository.resume(task.id)
        MenuResult.Consumed
    }

    fun cancel(repository: DownloadRepository): MenuHandler = MenuHandler { ctx, node ->
        val task = taskOf(ctx, node) ?: return@MenuHandler missingTask(ctx)
        repository.cancel(task.id)
        MenuResult.Consumed
    }

    fun retry(repository: DownloadRepository): MenuHandler = MenuHandler { ctx, node ->
        val task = taskOf(ctx, node) ?: return@MenuHandler missingTask(ctx)
        repository.retry(task.id)
        MenuResult.Consumed
    }

    /** Removing a row is destructive only to the queue: a finished file stays on disk. */
    fun remove(repository: DownloadRepository): MenuHandler = MenuHandler { ctx, node ->
        val task = taskOf(ctx, node) ?: return@MenuHandler missingTask(ctx)
        if (node.value != CONFIRMED) {
            return@MenuHandler MenuResult.Confirm(
                message = ctx.strings.format("TXT_ANDROID_DOWNLOAD_REMOVE_CONFIRM_FMT", task.title),
                handler = HandlerIds.DOWNLOAD_TASK_REMOVE,
                payload = node.payload,
                value = CONFIRMED,
            )
        }
        repository.remove(task.id)
        MenuResult.Message(
            ctx.strings.format("TXT_ANDROID_DOWNLOAD_REMOVED_FMT", task.title),
            pop = true,
        )
    }

    fun play(
        appContext: Context,
        repository: DownloadRepository,
        playback: PlaybackActions,
    ): MenuHandler = MenuHandler { ctx, node ->
        val file = fileOf(ctx, node) ?: return@MenuHandler missingFile(ctx)
        val mode = if (file.isVideo) PlayMode.VIDEO else PlayMode.AUDIO
        when (val outcome = playback.playLocal(appContext, file.id, titleOf(file.name), mode)) {
            LaunchOutcome.Started -> MenuResult.Consumed
            is LaunchOutcome.Failed -> MenuResult.Message(outcome.describe(ctx.strings), isError = true)
        }
    }

    fun openFile(appContext: Context): MenuHandler = MenuHandler { ctx, node ->
        val file = fileOf(ctx, node) ?: return@MenuHandler missingFile(ctx)
        val mime = DownloadNaming.mimeFor(file.name) ?: "*/*"
        launch(appContext, ctx, choose = false) {
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(file.id.toUri(), mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun shareFile(appContext: Context): MenuHandler = MenuHandler { ctx, node ->
        val file = fileOf(ctx, node) ?: return@MenuHandler missingFile(ctx)
        val mime = DownloadNaming.mimeFor(file.name) ?: "application/octet-stream"
        launch(appContext, ctx) {
            Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, file.id.toUri())
                .putExtra(Intent.EXTRA_TITLE, titleOf(file.name))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun deleteFile(
        appContext: Context,
        repository: DownloadRepository,
    ): MenuHandler = MenuHandler { ctx, node ->
        val file = fileOf(ctx, node) ?: return@MenuHandler missingFile(ctx)
        if (node.value != CONFIRMED) {
            return@MenuHandler MenuResult.Confirm(
                message = ctx.strings.format(
                    "TXT_ANDROID_DOWNLOAD_DELETE_CONFIRM_FMT",
                    titleOf(file.name),
                ),
                handler = HandlerIds.DOWNLOAD_FILE_DELETE,
                payload = node.payload,
                value = CONFIRMED,
            )
        }
        if (repository.deleteFileById(file.id)) {
            MenuResult.Message(
                ctx.strings.format("TXT_ANDROID_DOWNLOAD_DELETED_FMT", titleOf(file.name)),
                pop = true,
            )
        } else {
            MenuResult.Message(
                ctx.strings.text("TXT_ANDROID_DOWNLOAD_DELETE_FAILED"),
                isError = true,
            )
        }
    }

    fun clearFinished(repository: DownloadRepository): MenuHandler = MenuHandler { _, _ ->
        repository.clearFinished()
        MenuResult.Consumed
    }

    fun refresh(repository: DownloadRepository): MenuHandler = MenuHandler { ctx, _ ->
        val files = repository.refresh()
        MenuResult.Message(
            ctx.strings.format("TXT_ANDROID_DOWNLOAD_REFRESHED_FMT", files.size),
        )
    }

    fun openFolder(appContext: Context, repository: DownloadRepository): MenuHandler =
        MenuHandler { ctx, _ ->
            val target = repository.resolveTarget()
                ?: return@MenuHandler MenuResult.Message(
                    ctx.strings.text("TXT_ANDROID_DOWNLOAD_FOLDER_UNAVAILABLE"),
                    isError = true,
                )
            val uri = target.folderUri()
                ?: return@MenuHandler MenuResult.Message(
                    ctx.strings.format(
                        "TXT_ANDROID_DOWNLOAD_FOLDER_PATH_FMT",
                        target.description,
                    ),
                )
            // DocumentsUI opens a *document* URI; a tree URI selected here is stored as a
            // tree, so map it to the document it names before asking anyone to view it.
            val folderUri = if (DocumentsContract.isTreeUri(uri)) {
                DocumentsContract.buildDocumentUriUsingTree(
                    uri,
                    DocumentsContract.getTreeDocumentId(uri),
                )
            } else {
                uri
            }
            launch(appContext, ctx, choose = false) {
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(folderUri, DocumentsContract.Document.MIME_TYPE_DIR)
            }
        }

    /**
     * Both folder rows land here: "Choose folder" opens the system tree picker, while
     * "Set folder path" takes a typed relative path (`Movies/Rivo`, `Download/Rivo`).
     * The picker's answer arrives as `node.value` exactly like the prompt's text, so one
     * handler validates both.
     */
    fun setFolder(
        appContext: Context,
        config: ConfigStore,
        repository: DownloadRepository,
    ): MenuHandler = MenuHandler { ctx, node ->
        val picked = node.value
        if (picked.isNullOrBlank()) {
            return@MenuHandler if (node.handler == HandlerIds.DOWNLOADS_PICK_FOLDER) {
                MenuResult.PickFolder(handler = HandlerIds.DOWNLOADS_PICK_FOLDER)
            } else {
                MenuResult.Prompt(
                    title = ctx.strings.text("TXT_ANDROID_CFG_DOWNLOAD_DIR"),
                    hint = DownloadNaming.DEFAULT_FOLDER,
                    initial = ctx.config.string(ConfigKeys.DOWNLOAD_DIR, DownloadNaming.DEFAULT_FOLDER),
                    handler = HandlerIds.DOWNLOADS_SET_FOLDER,
                )
            }
        }

        val value = picked.trim()
        if (value.startsWith("content://")) {
            // A tree grant lasts until reboot unless it is persisted; a download queued
            // tomorrow must still be able to write.
            runCatching {
                appContext.contentResolver.takePersistableUriPermission(
                    value.toUri(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        val target = try {
            DownloadFolder.resolve(appContext, value)
        } catch (error: Throwable) {
            return@MenuHandler MenuResult.Message(
                error.message ?: ctx.strings.text("TXT_ANDROID_DOWNLOAD_FOLDER_UNAVAILABLE"),
                isError = true,
            )
        }
        config.set(ConfigKeys.DOWNLOAD_DIR, value)
        repository.refresh()
        MenuResult.Message(
            ctx.strings.format("TXT_ANDROID_DOWNLOAD_FOLDER_SET_FMT", target.description),
        )
    }

    // -- helpers --------------------------------------------------------------------

    private fun report(ctx: MenuContext, outcome: EnqueueOutcome): MenuResult {
        outcome.problem?.let { return MenuResult.Message(it, isError = true) }
        return when {
            outcome.added == 0 && outcome.skipped > 0 -> MenuResult.Message(
                ctx.strings.format("TXT_ANDROID_DOWNLOAD_ARCHIVED_FMT", outcome.skipped),
            )

            outcome.added == 0 -> MenuResult.Message(
                ctx.strings.text("TXT_ANDROID_DOWNLOAD_NOTHING"),
            )

            outcome.added == 1 && outcome.skipped == 0 -> MenuResult.Message(
                ctx.strings.text("TXT_ANDROID_DOWNLOAD_QUEUED_ONE"),
            )

            outcome.skipped > 0 -> MenuResult.Message(
                ctx.strings.format("TXT_ANDROID_DOWNLOAD_QUEUED_FMT", outcome.added) + " · " +
                    ctx.strings.format("TXT_ANDROID_DOWNLOAD_ARCHIVED_FMT", outcome.skipped),
            )

            else -> MenuResult.Message(
                ctx.strings.format("TXT_ANDROID_DOWNLOAD_QUEUED_FMT", outcome.added),
            )
        }
    }

    private fun taskOf(ctx: MenuContext, node: MenuNode) =
        (node.payload as? MenuPayload.Download)?.let { payload ->
            ctx.downloads.tasks.firstOrNull { it.id == payload.id }
        }

    private fun fileOf(ctx: MenuContext, node: MenuNode) =
        (node.payload as? MenuPayload.DownloadFile)?.let { payload ->
            ctx.downloads.files.firstOrNull { it.id == payload.id }
        }

    private fun missingTask(ctx: MenuContext): MenuResult = MenuResult.Message(
        ctx.strings.text("TXT_ANDROID_DOWNLOAD_MISSING"),
        isError = true,
    )

    private fun missingFile(ctx: MenuContext): MenuResult = MenuResult.Message(
        ctx.strings.text("TXT_ANDROID_DOWNLOAD_FILE_MISSING"),
        isError = true,
    )

    private fun titleOf(fileName: String): String = fileName.substringBeforeLast('.', fileName)

    /** One chooser around every handoff, so a device with no handler gets a sentence. */
    private inline fun launch(
        appContext: Context,
        ctx: MenuContext,
        choose: Boolean = true,
        intent: () -> Intent,
    ): MenuResult = try {
        val built = intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // The chooser is a separate intent: it needs the flag too, because this context is
        // the application, not an Activity.
        val launchIntent = if (choose) {
            Intent.createChooser(built, ctx.strings.text("TXT_ANDROID_SHARE"))
        } else {
            built
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(launchIntent)
        MenuResult.Consumed
    } catch (_: ActivityNotFoundException) {
        MenuResult.Message(ctx.strings.text("TXT_ANDROID_NO_HANDLER"), isError = true)
    }

    private const val CONFIRMED = "confirmed"
}

