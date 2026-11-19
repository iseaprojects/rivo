package io.github.iseaprojects.rivo.data.download

import java.io.File

/**
 * The way out for formats the platform HTTP path cannot fetch.
 *
 * A progressive media URL is a file, so `RangedDownloader` can get it. An HLS or DASH
 * manifest is a playlist of segments, and a separate audio rendition may be Opus in WebM,
 * which the platform muxer refuses. Both cases are exactly what yt-dlp exists for, so the
 * `ytdlp` flavor supplies a real implementation and the `fast` flavor supplies
 * [NoDownloadFallback], whose message names the build that can do it.
 */
interface DownloadFallback {

    val available: Boolean

    /**
     * Downloads [task] into [workDir] and returns the media file it produced. Exactly one
     * file is returned; the caller copies it into the download folder.
     */
    suspend fun download(
        task: DownloadTask,
        workDir: File,
        maxHeight: Int?,
        onProgress: (Float) -> Unit,
    ): File
}

object NoDownloadFallback : DownloadFallback {

    override val available: Boolean = false

    override suspend fun download(
        task: DownloadTask,
        workDir: File,
        maxHeight: Int?,
        onProgress: (Float) -> Unit,
    ): File = throw DownloadException(
        "this stream is delivered as a playlist of segments (HLS/DASH); " +
            "the yt-dlp build can download it",
        retryable = false,
    )
}
