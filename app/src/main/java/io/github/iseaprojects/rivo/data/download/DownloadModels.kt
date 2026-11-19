package io.github.iseaprojects.rivo.data.download

import kotlinx.serialization.Serializable

/** A download either keeps the picture or keeps only the sound. */
@Serializable
enum class DownloadKind { VIDEO, AUDIO }

/**
 * Where a task sits in the queue.
 *
 * `QUEUED` is the only state the service picks up; `RUNNING` is set when a worker claims
 * it. A task left as `RUNNING` in the queue file (process death, force-stop) is reset to
 * `QUEUED` on load instead of being stranded.
 */
@Serializable
enum class DownloadState { QUEUED, RUNNING, PAUSED, DONE, FAILED, CANCELLED }

/**
 * One item in the persistent download queue.
 *
 * [url] is the page URL, not a stream URL: signed stream URLs expire within hours, so a
 * task that waits overnight has to re-resolve before it can start. Everything else is
 * captured now so the row and the notification keep working offline.
 */
@Serializable
data class DownloadTask(
    val videoId: String,
    val url: String,
    val title: String,
    val kind: DownloadKind = DownloadKind.VIDEO,
    val channel: String? = null,
    val playlist: String? = null,
    val thumbnailUrl: String? = null,
    val durationSeconds: Long? = null,
    val state: DownloadState = DownloadState.QUEUED,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    /** Path relative to the download folder, set once the bytes are in place. */
    val outputPath: String? = null,
    val mimeType: String? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** Stable identity: the same video can be downloaded as a video and as audio. */
    val id: String get() = "${kind.name.lowercase()}:$videoId"

    val isFinished: Boolean get() = state == DownloadState.DONE
    val isActive: Boolean
        get() = state == DownloadState.QUEUED || state == DownloadState.RUNNING ||
            state == DownloadState.PAUSED
}

/** A file that already exists in the download folder. */
data class DownloadedFile(
    val id: String,
    val name: String,
    val relativePath: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val mimeType: String?,
    val uri: android.net.Uri,
    val kind: DownloadKind,
) {
    val isVideo: Boolean get() = kind == DownloadKind.VIDEO
}

/** Renderable state of the downloader: what is queued and what is on disk. */
data class DownloadSnapshot(
    val tasks: List<DownloadTask> = emptyList(),
    val files: List<DownloadedFile> = emptyList(),
    /** Why the folder could not be read; the downloads screen shows it instead of "empty". */
    val folderProblem: String? = null,
) {
    val active: List<DownloadTask>
        get() = tasks.filter { it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING }

    val paused: List<DownloadTask> get() = tasks.filter { it.state == DownloadState.PAUSED }

    val failed: List<DownloadTask> get() = tasks.filter { it.state == DownloadState.FAILED }

    val isEmpty: Boolean get() = tasks.isEmpty() && files.isEmpty()

    companion object {
        val EMPTY = DownloadSnapshot()
    }
}

/**
 * A download that could not be completed, with a sentence fit for a snackbar.
 *
 * [retryable] separates "the link is dead" from "the connection dropped", because only
 * the second one is worth another attempt.
 */
class DownloadException(
    message: String,
    cause: Throwable? = null,
    val retryable: Boolean = true,
) : Exception(message, cause)
