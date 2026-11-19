package io.github.iseaprojects.rivo.menu

import io.github.iseaprojects.rivo.core.config.ConfigKeys
import io.github.iseaprojects.rivo.core.menu.HandlerIds
import io.github.iseaprojects.rivo.core.menu.MenuContext
import io.github.iseaprojects.rivo.core.menu.MenuDefinition
import io.github.iseaprojects.rivo.core.menu.MenuDsl
import io.github.iseaprojects.rivo.core.menu.MenuDownloadFile
import io.github.iseaprojects.rivo.core.menu.MenuDownloadState
import io.github.iseaprojects.rivo.core.menu.MenuDownloadTask
import io.github.iseaprojects.rivo.core.menu.MenuIds
import io.github.iseaprojects.rivo.core.menu.MenuNode
import io.github.iseaprojects.rivo.core.menu.MenuPayload
import io.github.iseaprojects.rivo.core.menu.MenuTone
import io.github.iseaprojects.rivo.core.ui.CountFormat
import io.github.iseaprojects.rivo.data.download.DownloadNaming

/**
 * The downloads screen.
 *
 * yt-x prints the same three things in one place: what is still queued, what is already
 * on disk, and the handful of maintenance actions. The list is a normal catalog menu
 * rather than a data screen so every row can be a submenu of its own actions — a paused
 * task needs Resume, a failed one needs Retry, and a file needs Play, Share and Delete.
 */
object DownloadMenus {

    fun downloads(): MenuDefinition = MenuDefinition(
        id = MenuIds.DOWNLOADS,
        titleKey = "TXT_ANDROID_MENU_DOWNLOADS",
        build = { ctx -> buildDownloads(ctx) },
    )

    fun taskActions(): MenuDefinition = MenuDefinition(
        id = MenuIds.DOWNLOAD_TASK_ACTIONS,
        // Named after the task it acts on, which is the row label from the queue list.
        titleKey = null,
        build = { ctx ->
            val task = (ctx.payload as? MenuPayload.Download)
                ?.let { payload -> ctx.downloads.tasks.firstOrNull { it.id == payload.id } }
            if (task == null) {
                listOf(missing(ctx, "TXT_ANDROID_DOWNLOAD_MISSING"), MenuDsl.back(), MenuDsl.exit())
            } else {
                taskRows(ctx, task)
            }
        },
    )

    fun fileActions(): MenuDefinition = MenuDefinition(
        id = MenuIds.DOWNLOAD_FILE_ACTIONS,
        titleKey = null,
        build = { ctx ->
            val file = (ctx.payload as? MenuPayload.DownloadFile)
                ?.let { payload -> ctx.downloads.files.firstOrNull { it.id == payload.id } }
            if (file == null) {
                listOf(missing(ctx, "TXT_ANDROID_DOWNLOAD_FILE_MISSING"), MenuDsl.back(), MenuDsl.exit())
            } else {
                fileRows(file)
            }
        },
    )

    private fun buildDownloads(ctx: MenuContext): List<MenuNode> {
        val state = ctx.downloads
        return buildList {
            add(
                MenuDsl.info(
                    id = "downloads.folder",
                    labelKey = "TXT_ANDROID_DOWNLOAD_FOLDER",
                    detail = ctx.downloads.folderLabel.ifBlank { DownloadNaming.DEFAULT_FOLDER },
                ),
            )
            state.problem?.let { problem ->
                add(
                    MenuDsl.action(
                        id = "downloads.problem",
                        labelKey = "TXT_ANDROID_DOWNLOAD_FOLDER_UNAVAILABLE",
                        icon = Glyphs.TRASH,
                        handler = HandlerIds.DOWNLOADS_REFRESH,
                        detail = problem,
                        tone = MenuTone.DESTRUCTIVE,
                    ),
                )
            }

            val pending = state.pending
            if (pending.isNotEmpty()) {
                add(
                    MenuDsl.info(
                        id = "downloads.queue",
                        labelKey = "TXT_ANDROID_DOWNLOADS_QUEUE",
                        detail = queueSummary(ctx, pending),
                    ),
                )
                pending.forEach { task ->
                    add(
                        MenuNode(
                            id = "downloads.task.${task.id}",
                            label = task.title,
                            detail = taskDetail(ctx, task),
                            iconKey = "TXT_ICON_MENU_MEDIA_ACTIONS_DOWNLOAD",
                            targetMenuId = MenuIds.DOWNLOAD_TASK_ACTIONS,
                            payload = MenuPayload.Download(task.id),
                            tone = when (task.state) {
                                MenuDownloadState.FAILED -> MenuTone.DESTRUCTIVE
                                MenuDownloadState.RUNNING -> MenuTone.ACCENT
                                else -> MenuTone.NORMAL
                            },
                        ),
                    )
                }
            }

            if (state.files.isNotEmpty()) {
                add(
                    MenuDsl.info(
                        id = "downloads.files",
                        labelKey = "TXT_ANDROID_DOWNLOADS_FILES",
                        detail = if (state.files.size == 1) {
                            ctx.strings.text("TXT_ANDROID_DOWNLOADS_FILES_ONE")
                        } else {
                            ctx.strings.format("TXT_ANDROID_DOWNLOADS_FILES_FMT", state.files.size)
                        },
                    ),
                )
                state.files.forEach { file -> add(fileRow(file)) }
            }

            if (state.empty) {
                add(
                    MenuDsl.info(
                        id = "downloads.empty",
                        labelKey = "TXT_ANDROID_DOWNLOADS_EMPTY",
                        detail = ctx.strings.text("TXT_ANDROID_DOWNLOADS_EMPTY_HINT"),
                    ),
                )
            }

            add(
                MenuDsl.action(
                    id = "downloads.clear",
                    labelKey = "TXT_ANDROID_DOWNLOADS_CLEAR_FINISHED",
                    icon = Glyphs.TRASH,
                    handler = HandlerIds.DOWNLOADS_CLEAR_FINISHED,
                    detail = if (state.finishedCount > 0) state.finishedCount.toString() else null,
                    enabled = state.finishedCount > 0,
                    tone = MenuTone.DESTRUCTIVE,
                ),
            )
            add(
                MenuDsl.action(
                    id = "downloads.refresh",
                    labelKey = "TXT_ANDROID_DOWNLOADS_REFRESH",
                    icon = Glyphs.REFRESH,
                    handler = HandlerIds.DOWNLOADS_REFRESH,
                ),
            )
            add(
                MenuDsl.action(
                    id = "downloads.open_folder",
                    labelKey = "TXT_ANDROID_DOWNLOADS_OPEN_FOLDER",
                    icon = Glyphs.FOLDER,
                    handler = HandlerIds.DOWNLOADS_OPEN_FOLDER,
                ),
            )
            add(
                MenuDsl.action(
                    id = "downloads.choose_folder",
                    labelKey = "TXT_ANDROID_DOWNLOADS_CHOOSE_FOLDER",
                    icon = Glyphs.FOLDER,
                    handler = HandlerIds.DOWNLOADS_PICK_FOLDER,
                ),
            )
            add(MenuDsl.back())
            add(MenuDsl.exit())
        }
    }

    private fun taskRows(ctx: MenuContext, task: MenuDownloadTask): List<MenuNode> = buildList {
        add(
            MenuDsl.info(
                id = "downloads.task.state",
                labelKey = "TXT_ANDROID_DOWNLOAD_STATE",
                detail = taskDetail(ctx, task),
            ),
        )
        when (task.state) {
            MenuDownloadState.QUEUED, MenuDownloadState.RUNNING -> add(
                action(
                    id = "downloads.task.pause",
                    labelKey = "TXT_ANDROID_DOWNLOAD_PAUSE",
                    handler = HandlerIds.DOWNLOAD_TASK_PAUSE,
                    task = task,
                    icon = Glyphs.MINUS,
                ),
            )

            MenuDownloadState.PAUSED -> add(
                action(
                    id = "downloads.task.resume",
                    labelKey = "TXT_ANDROID_DOWNLOAD_RESUME",
                    handler = HandlerIds.DOWNLOAD_TASK_RESUME,
                    task = task,
                    icon = Glyphs.PLAY,
                    tone = MenuTone.ACCENT,
                ),
            )

            MenuDownloadState.FAILED -> add(
                action(
                    id = "downloads.task.retry",
                    labelKey = "TXT_ANDROID_DOWNLOAD_RETRY",
                    handler = HandlerIds.DOWNLOAD_TASK_RETRY,
                    task = task,
                    icon = Glyphs.REFRESH,
                    tone = MenuTone.ACCENT,
                ),
            )

            MenuDownloadState.CANCELLED -> add(
                action(
                    id = "downloads.task.retry",
                    labelKey = "TXT_ANDROID_DOWNLOAD_RETRY",
                    handler = HandlerIds.DOWNLOAD_TASK_RETRY,
                    task = task,
                    icon = Glyphs.REFRESH,
                ),
            )

            MenuDownloadState.DONE -> Unit
        }
        if (task.state == MenuDownloadState.QUEUED || task.state == MenuDownloadState.RUNNING ||
            task.state == MenuDownloadState.PAUSED
        ) {
            add(
                action(
                    id = "downloads.task.cancel",
                    labelKey = "TXT_ANDROID_DOWNLOAD_CANCEL",
                    handler = HandlerIds.DOWNLOAD_TASK_CANCEL,
                    task = task,
                    icon = Glyphs.TRASH,
                    tone = MenuTone.DESTRUCTIVE,
                ),
            )
        }
        add(
            action(
                id = "downloads.task.remove",
                labelKey = "TXT_ANDROID_DOWNLOAD_REMOVE",
                handler = HandlerIds.DOWNLOAD_TASK_REMOVE,
                task = task,
                icon = Glyphs.TRASH,
                tone = MenuTone.DESTRUCTIVE,
            ),
        )
        add(MenuDsl.back())
        add(MenuDsl.exit())
    }

    private fun fileRows(file: MenuDownloadFile): List<MenuNode> = listOf(
        MenuDsl.action(
            id = "downloads.file.play",
            labelKey = if (file.isVideo) {
                "TXT_ANDROID_DOWNLOAD_PLAY"
            } else {
                "TXT_ANDROID_DOWNLOAD_LISTEN"
            },
            icon = Glyphs.PLAY,
            handler = HandlerIds.DOWNLOAD_FILE_PLAY,
            payload = MenuPayload.DownloadFile(file.id),
            tone = MenuTone.ACCENT,
        ),
        MenuDsl.action(
            id = "downloads.file.open",
            labelKey = "TXT_ANDROID_DOWNLOAD_OPEN_WITH",
            icon = Glyphs.FOLDER,
            handler = HandlerIds.DOWNLOAD_FILE_OPEN,
            payload = MenuPayload.DownloadFile(file.id),
        ),
        MenuDsl.action(
            id = "downloads.file.share",
            labelKey = "TXT_ANDROID_SHARE",
            icon = Glyphs.LIST,
            handler = HandlerIds.DOWNLOAD_FILE_SHARE,
            payload = MenuPayload.DownloadFile(file.id),
        ),
        MenuDsl.info(
            id = "downloads.file.path",
            labelKey = "TXT_ANDROID_DOWNLOAD_PATH",
            detail = file.relativePath,
        ),
        MenuDsl.action(
            id = "downloads.file.delete",
            labelKey = "TXT_ANDROID_DOWNLOAD_DELETE",
            icon = Glyphs.TRASH,
            handler = HandlerIds.DOWNLOAD_FILE_DELETE,
            payload = MenuPayload.DownloadFile(file.id),
            tone = MenuTone.DESTRUCTIVE,
        ),
        MenuDsl.back(),
        MenuDsl.exit(),
    )

    private fun action(
        id: String,
        labelKey: String,
        handler: String,
        task: MenuDownloadTask,
        icon: String,
        tone: MenuTone = MenuTone.NORMAL,
    ): MenuNode = MenuDsl.action(
        id = id,
        labelKey = labelKey,
        icon = icon,
        handler = handler,
        payload = MenuPayload.Download(task.id),
        detail = task.title,
        tone = tone,
    )

    private fun fileRow(file: MenuDownloadFile): MenuNode = MenuNode(
        id = "downloads.file.${file.id}",
        label = file.name.substringBeforeLast('.', file.name),
        detail = listOfNotNull(
            CountFormat.bytes(file.sizeBytes).ifBlank { null },
            file.relativePath,
        ).joinToString(" · "),
        iconKey = if (file.isVideo) {
            "TXT_ICON_MENU_MEDIA_ACTIONS_WATCH"
        } else {
            "TXT_ICON_MENU_MEDIA_ACTIONS_LISTEN"
        },
        targetMenuId = MenuIds.DOWNLOAD_FILE_ACTIONS,
        payload = MenuPayload.DownloadFile(file.id),
    )

    private fun missing(ctx: MenuContext, key: String): MenuNode = MenuDsl.info(
        id = "downloads.missing",
        labelKey = key,
        detail = ctx.downloads.folderLabel.ifBlank { DownloadNaming.DEFAULT_FOLDER },
    )

    /**
     * One line under the queue header: what is happening now, or what went wrong. yt-x
     * prints the same three counters, and they answer "did my tap do anything?" without
     * opening a single row.
     */
    private fun queueSummary(ctx: MenuContext, pending: List<MenuDownloadTask>): String {
        val running = pending.count { it.state == MenuDownloadState.RUNNING }
        val queued = pending.count { it.state == MenuDownloadState.QUEUED }
        val paused = pending.count { it.state == MenuDownloadState.PAUSED }
        val failed = pending.count { it.state == MenuDownloadState.FAILED }
        return buildList {
            if (running > 0) add(ctx.strings.format("TXT_ANDROID_DOWNLOAD_SUMMARY_RUNNING_FMT", running))
            if (queued > 0) add(ctx.strings.format("TXT_ANDROID_DOWNLOAD_SUMMARY_QUEUED_FMT", queued))
            if (paused > 0) add(ctx.strings.format("TXT_ANDROID_DOWNLOAD_SUMMARY_PAUSED_FMT", paused))
            if (failed > 0) add(ctx.strings.format("TXT_ANDROID_DOWNLOAD_SUMMARY_FAILED_FMT", failed))
        }.joinToString(" · ")
    }

    private fun taskDetail(ctx: MenuContext, task: MenuDownloadTask): String = buildList {
        add(
            ctx.strings.text(
                when (task.state) {
                    MenuDownloadState.QUEUED -> "TXT_ANDROID_DOWNLOAD_STATE_QUEUED"
                    MenuDownloadState.RUNNING -> "TXT_ANDROID_DOWNLOAD_STATE_RUNNING"
                    MenuDownloadState.PAUSED -> "TXT_ANDROID_DOWNLOAD_STATE_PAUSED"
                    MenuDownloadState.DONE -> "TXT_ANDROID_DOWNLOAD_STATE_DONE"
                    MenuDownloadState.FAILED -> "TXT_ANDROID_DOWNLOAD_STATE_FAILED"
                    MenuDownloadState.CANCELLED -> "TXT_ANDROID_DOWNLOAD_STATE_CANCELLED"
                },
            ),
        )
        if (task.state == MenuDownloadState.RUNNING && task.progress != null) {
            add("${(task.progress * 100).toInt()}%")
        }
        CountFormat.bytes(task.bytesTotal).takeIf { it.isNotBlank() }?.let(::add)
        if (task.state == MenuDownloadState.FAILED) {
            task.error?.takeIf { it.isNotBlank() }?.let(::add)
        } else {
            task.channel?.takeIf { it.isNotBlank() }?.let(::add)
        }
    }.joinToString(" · ")
}
