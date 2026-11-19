package io.github.iseaprojects.rivo.data.extract.ytdlp

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import io.github.iseaprojects.rivo.core.config.ConfigKeys
import io.github.iseaprojects.rivo.core.config.ConfigSnapshot
import io.github.iseaprojects.rivo.data.download.DownloadException
import io.github.iseaprojects.rivo.data.download.DownloadFallback
import io.github.iseaprojects.rivo.data.download.DownloadKind
import io.github.iseaprojects.rivo.data.download.DownloadTask
import io.github.iseaprojects.rivo.data.net.CookieProvider
import io.github.iseaprojects.rivo.data.net.PoTokenConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * yt-dlp as the downloader of last resort.
 *
 * The platform path (`RangedDownloader`) can fetch any progressive URL, which covers
 * YouTube, Vimeo, SoundCloud and a direct file. It cannot reassemble an HLS/DASH segment
 * list, and it cannot merge an Opus/WebM audio track into the MP4 muxer. yt-dlp already
 * knows how to do both, so this class runs the bundled binary with a single-format
 * selector: `b[height<=H]` for video, `ba/b` for audio. No `+` merge is requested because
 * the APK ships no FFmpeg; the goal is one playable file, not the highest bitrate.
 */
class YtdlpDownloads(
    private val context: Context,
    private val cookies: CookieProvider,
    private val config: () -> ConfigSnapshot,
) : DownloadFallback {

    override val available: Boolean = true

    override suspend fun download(
        task: DownloadTask,
        workDir: File,
        maxHeight: Int?,
        onProgress: (Float) -> Unit,
    ): File {
        withContext(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().init(context)
            } catch (error: YoutubeDLException) {
                throw DownloadException("yt-dlp failed to start: ${error.message}", error, retryable = false)
            }
        }

        val template = File(workDir, "download.%(ext)s").absolutePath
        val request = YoutubeDLRequest(task.url).apply {
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--newline")
            addOption("--retries", "3")
            addOption("--socket-timeout", "20")
            addOption("-o", template)
            val height = maxHeight?.let { "[height<=$it]" }.orEmpty()
            when (task.kind) {
                DownloadKind.AUDIO -> addOption("-f", "ba/b")
                DownloadKind.VIDEO -> addOption("-f", "b$height/b")
            }
            if (cookies.enabled && cookies.count > 0) {
                cookiesFile()?.let { addOption("--cookies", it.absolutePath) }
            }
            PoTokenConfig.parse(config().string(ConfigKeys.PO_TOKEN))
                ?.let(PoTokenConfig::extractorArgs)
                ?.let { addOption("--extractor-args", it) }
            applyUserOptions()
        }

        val response = try {
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().execute(request, "rivo-download") { progress, _, _ ->
                    if (progress >= 0f) onProgress((progress / 100f).coerceIn(0f, 1f))
                }
            }
        } catch (cancelled: YoutubeDL.CanceledException) {
            throw kotlinx.coroutines.CancellationException("download cancelled")
        } catch (error: YoutubeDLException) {
            throw DownloadException(error.message ?: "yt-dlp could not download this item", error, retryable = false)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw DownloadException("yt-dlp was interrupted", interrupted)
        }

        if (response.exitCode != 0) {
            throw DownloadException(
                response.err?.lineSequence()?.lastOrNull { it.isNotBlank() }
                    ?: "yt-dlp exited with ${response.exitCode}",
                retryable = false,
            )
        }
        val produced = workDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?.maxByOrNull { it.length() }
        return produced ?: throw DownloadException("yt-dlp reported success but wrote no file")
    }

    private fun cookiesFile(): File? {
        val text = cookies.exportNetscape(null) ?: return null
        val file = File(context.cacheDir, "rivo-cookies.txt")
        return runCatching {
            file.writeText(text)
            file
        }.getOrNull()
    }

    /** Same pairing rule as [YtDlpBackend]: `--opt value` stays two arguments. */
    private fun YoutubeDLRequest.applyUserOptions() {
        val tokens = CommandLine.split(config().string(ConfigKeys.YT_DLP_OPTS))
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (!token.startsWith("-")) {
                index++
                continue
            }
            val next = tokens.getOrNull(index + 1)
            if (next != null && !next.startsWith("-")) {
                runCatching { addOption(token, next) }
                index += 2
            } else {
                runCatching { addOption(token) }
                index++
            }
        }
    }
}
