package io.github.iseaprojects.rivo.core.menu

/**
 * Download state as the menu layer sees it.
 *
 * Like [MenuLibrary], this is a plain snapshot with no dependency on the downloader: the
 * builder stays declarative, the frame is immutable per resolution, and the data layer can
 * change its storage without touching the menu code. The mapping lives in `AppGraph`.
 */
data class MenuDownloads(
    val tasks: List<MenuDownloadTask> = emptyList(),
    val files: List<MenuDownloadFile> = emptyList(),
    /**
     * Human description of the configured folder (`Movies/Rivo`, `Download/Rivo-saf`).
     * A SAF tree is stored as a `content://` URI, which is not something to show a user.
     */
    val folderLabel: String = "",
    /** Set when the folder itself cannot be read (missing SD card, revoked permission). */
    val problem: String? = null,
) {
    val empty: Boolean get() = tasks.isEmpty() && files.isEmpty()

    /** Rows the user still has to act on; finished tasks are represented by [files]. */
    val pending: List<MenuDownloadTask>
        get() = tasks.filter {
            it.state != MenuDownloadState.DONE && it.state != MenuDownloadState.CANCELLED
        }

    val finishedCount: Int
        get() = tasks.count {
            it.state == MenuDownloadState.DONE || it.state == MenuDownloadState.CANCELLED
        }

    companion object {
        val EMPTY = MenuDownloads()
    }
}

data class MenuDownloadTask(
    val id: String,
    val title: String,
    val state: MenuDownloadState,
    val channel: String? = null,
    val error: String? = null,
    val outputPath: String? = null,
    val bytesTotal: Long = 0,
    val bytesDone: Long = 0,
    /** Live 0..1 fraction while [state] is [MenuDownloadState.RUNNING]. */
    val progress: Float? = null,
)

data class MenuDownloadFile(
    /** The URI, which is also what identifies the file to the handlers. */
    val id: String,
    val name: String,
    val relativePath: String,
    val sizeBytes: Long,
    val isVideo: Boolean,
)

enum class MenuDownloadState {
    QUEUED,
    RUNNING,
    PAUSED,
    DONE,
    FAILED,
    CANCELLED,
}
