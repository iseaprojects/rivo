package io.github.iseaprojects.rivo.data.download

import android.content.Context
import io.github.iseaprojects.rivo.core.config.ConfigKeys
import io.github.iseaprojects.rivo.core.config.ConfigSnapshot
import io.github.iseaprojects.rivo.data.extract.ExtractorException
import io.github.iseaprojects.rivo.data.extract.ExtractorRouter
import io.github.iseaprojects.rivo.data.extract.StreamFormat
import io.github.iseaprojects.rivo.data.net.HttpClients
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs one queued task from URL to file.
 *
 * The flow is the same for every backend: re-resolve the page (stream URLs expire), pick
 * a plan, fetch the bytes into the app cache, join if needed, then copy into the download
 * folder. Nothing is written to the target until it is complete, so a half-downloaded
 * video never appears in the gallery and never collides with a retry.
 */
class DownloadEngine(
    private val context: Context,
    private val router: ExtractorRouter,
    private val config: () -> ConfigSnapshot,
    private val fallback: DownloadFallback,
    private val downloader: RangedDownloader = RangedDownloader(HttpClients.transfers),
    private val muxer: Mp4Muxer = Mp4Muxer(),
) {

    /**
     * Downloads [task] and returns the finished task. Failures are returned with
     * `state = FAILED` and a message: the service turns that into a notification, and a
     * thrown exception would lose the reason.
     */
    suspend fun run(task: DownloadTask, onProgress: (Float) -> Unit = {}): DownloadTask {
        val workDir = workDir(context, task.id)
        val finished = try {
            workDir.mkdirs()

            val details = router.video(task.url)
            val settings = config()
            val maxHeight = DownloadPlanner.heightCap(settings.string(ConfigKeys.VIDEO_FORMAT))
            val audioFormat = settings.string(ConfigKeys.AUDIO_FORMAT).ifBlank { "m4a" }
            // The same budget covers every transfer of this task: a merge downloads two
            // renditions, and a truncated video followed by a truncated audio should not
            // multiply the user's wait by four.
            val attempts = settings.int(ConfigKeys.DOWNLOAD_RETRIES, RangedDownloader.DEFAULT_ATTEMPTS)
            val plan = DownloadPlanner.plan(details, task.kind, maxHeight, audioFormat)
                ?: throw DownloadException("this video has no downloadable stream", retryable = false)

            val produced = when (plan) {
                is DownloadPlan.Direct -> direct(plan.format, workDir, attempts, onProgress)
                is DownloadPlan.Merge -> merge(plan, workDir, attempts, onProgress)
                is DownloadPlan.Manifest -> fallback.download(task, workDir, maxHeight, onProgress)
            }

            val target = DownloadFolder.resolve(context, settings.string(ConfigKeys.DOWNLOAD_DIR))
            val directory = DownloadNaming.directory(task)
            val extension = produced.extension.ifBlank {
                DownloadNaming.extensionFor(planMime(plan), null)
            }
            val existing = target.namesIn(directory).toSet()
            val enumerate = if (settings.bool(ConfigKeys.DOWNLOADS_ENUMERATE, false)) {
                existing.size + 1
            } else {
                null
            }
            val fileName = DownloadNaming.uniqueName(
                task = task,
                extension = extension,
                enumerate = enumerate,
                existing = existing,
            )
            val mime = planMime(plan) ?: DownloadNaming.mimeFor(fileName)

            val written = target.write(directory, fileName, produced, mime)
                ?: throw DownloadException("could not write $fileName to the download folder")

            task.copy(
                state = DownloadState.DONE,
                outputPath = written.relativePath,
                bytesTotal = written.sizeBytes,
                bytesDone = written.sizeBytes,
                mimeType = written.mimeType,
                error = null,
            )
        } catch (cancelled: CancellationException) {
            // Pause and cancel both arrive here; the caller decides whether the part
            // files are kept (pause resumes them) or wiped (cancel discards them).
            throw cancelled
        } catch (error: Throwable) {
            val message = when (error) {
                is ExtractorException -> error.message ?: "the video could not be resolved"
                else -> error.message ?: error.javaClass.simpleName
            }
            task.copy(state = DownloadState.FAILED, error = message)
        }
        // A finished or failed run has nothing left to resume from; only a cancellation
        // leaves the work directory behind on purpose.
        withContext(Dispatchers.IO) { workDir.deleteRecursively() }
        return finished
    }

    private suspend fun direct(
        format: StreamFormat,
        workDir: File,
        attempts: Int,
        onProgress: (Float) -> Unit,
    ): File {
        val url = format.url ?: throw DownloadException("the stream has no media URL", retryable = false)
        val extension = DownloadNaming.extensionFor(format.mimeType, format.container)
        val part = File(workDir, partName(format, extension))
        val bytes = downloader.download(url, format.httpHeaders, part, attempts) { done, total ->
            onProgress(fraction(done, total.takeIf { it > 0 } ?: format.approxBytes ?: -1))
        }
        if (bytes <= 0) throw DownloadException("the server sent no data")
        val final = File(workDir, "media.$extension")
        if (!part.renameTo(final)) {
            part.copyTo(final, overwrite = true)
            part.delete()
        }
        return final
    }

    private suspend fun merge(
        plan: DownloadPlan.Merge,
        workDir: File,
        attempts: Int,
        onProgress: (Float) -> Unit,
    ): File {
        val videoExt = DownloadNaming.extensionFor(plan.video.mimeType, plan.video.container)
        val audioExt = DownloadNaming.extensionFor(plan.audio.mimeType, plan.audio.container)
        val video = File(workDir, partName(plan.video, videoExt, prefix = "video"))
        val audio = File(workDir, partName(plan.audio, audioExt, prefix = "audio"))

        // Video is the bulk of the bytes; audio is reported as the last quarter so the
        // bar does not jump backwards when the second transfer starts.
        down(plan.video, video, attempts) { done, total -> onProgress(fraction(done, total) * 0.75f) }
        down(plan.audio, audio, attempts) { done, total -> onProgress(0.75f + fraction(done, total) * 0.2f) }

        val merged = File(workDir, "merged.mp4")
        val ok = muxer.mux(video, audio, merged)
        video.delete()
        audio.delete()
        if (!ok || !merged.exists() || merged.length() == 0L) {
            throw DownloadException(
                "the video and audio tracks could not be joined; " +
                    "lower the video quality or use the yt-dlp build",
                retryable = false,
            )
        }
        onProgress(0.98f)
        return merged
    }

    private suspend fun down(
        format: StreamFormat,
        part: File,
        attempts: Int,
        onProgress: (Long, Long) -> Unit,
    ) {
        val url = format.url ?: throw DownloadException("the stream has no media URL", retryable = false)
        downloader.download(url, format.httpHeaders, part, attempts) { done, total ->
            onProgress(done, total.takeIf { it > 0 } ?: format.approxBytes ?: -1L)
        }
    }

    private fun planMime(plan: DownloadPlan): String? = when (plan) {
        is DownloadPlan.Direct -> plan.format.mimeType
            ?: DownloadNaming.mimeFor("x.${DownloadNaming.extensionFor(null, plan.format.container)}")

        is DownloadPlan.Merge -> "video/mp4"
        is DownloadPlan.Manifest -> plan.mimeType
    }

    /**
     * Part files are named after the rendition, so a paused transfer resumes the same
     * bytes while a quality change starts a new file instead of appending to the old one.
     */
    private fun partName(format: StreamFormat, extension: String, prefix: String = "media"): String {
        val key = format.formatId.ifBlank { "stream" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(48)
        return "$prefix.$key.$extension.part"
    }

    private fun fraction(done: Long, total: Long): Float =
        if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f

    companion object {
        /** Per-task scratch directory; shared with the service so it can discard a cancel. */
        fun workDir(context: Context, taskId: String): File =
            File(context.cacheDir, "downloads/${taskId.replace(':', '_')}")

        /**
         * Removes leftovers from an interrupted process, except the ones a paused task
         * still needs: pause keeps its part file precisely so Resume continues it.
         */
        fun cleanupCache(context: Context, keep: Set<String> = emptySet()) {
            val root = File(context.cacheDir, "downloads")
            val keepDirs = keep.map { it.replace(':', '_') }.toSet()
            root.listFiles()?.forEach { entry ->
                if (entry.name !in keepDirs) runCatching { entry.deleteRecursively() }
            }
        }
    }
}
