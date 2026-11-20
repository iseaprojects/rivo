package io.github.iseaprojects.rivo.data.download

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Resumable HTTP transfer for one rendition.
 *
 * A YouTube CDN URL is a plain HTTPS file once resolved, so a ranged request is all the
 * "yt-dlp resumable transfer" parity needs: the bytes already on disk are kept and the
 * next attempt asks for the rest. Two details make that safe:
 *
 *  - `If-Range` is not used, because a re-resolved URL has a different signature; the
 *    server answering `200` instead of `206` means the resume target is invalid, so the
 *    part file is truncated and the transfer restarts from zero.
 *  - the destination is a `.part` file renamed only after the byte count matches, so an
 *    interrupted transfer can never be mistaken for a finished download.
 */
class RangedDownloader(
    private val client: OkHttpClient,
    private val defaultAttempts: Int = DEFAULT_ATTEMPTS,
) {

    /**
     * Downloads [url] into [destination], resuming when a part file exists.
     *
     * [attempts] is the budget for this transfer, normally `CONFIG_DOWNLOAD_RETRIES`;
     * it is clamped so a hand-edited config cannot hang a queued item for hours.
     * [onProgress] receives (bytes on disk, total bytes or -1 when the server does not
     * say). It is called on a background thread and throttled by the caller.
     */
    suspend fun download(
        url: String,
        headers: Map<String, String>,
        destination: File,
        attempts: Int = defaultAttempts,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Long = withContext(Dispatchers.IO) {
        destination.parentFile?.mkdirs()
        val budget = attempts.coerceIn(1, MAX_ATTEMPTS)
        var lastError: Throwable? = null
        var attempt = 0
        while (attempt < budget) {
            attempt++
            try {
                return@withContext transfer(url, headers, destination, onProgress)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: DownloadException) {
                // A definitive HTTP verdict (404, 410) is not worth a retry.
                if (!error.retryable) throw error
                lastError = error
            } catch (error: IOException) {
                lastError = error
            }
            if (attempt < budget) delay(backoff(attempt))
        }
        throw DownloadException(
            "transfer failed after $budget attempts: ${lastError?.message ?: "unknown error"}",
            lastError,
        )
    }

    /** 1 s, 2 s, 4 s, then 8 s: enough for a flaky link, short enough to stay tolerable. */
    private fun backoff(attempt: Int): Long =
        (1_000L shl (attempt - 1).coerceIn(0, 3)).coerceAtMost(MAX_BACKOFF_MS)

    private suspend fun transfer(
        url: String,
        headers: Map<String, String>,
        destination: File,
        onProgress: (Long, Long) -> Unit,
    ): Long {
        val existing = destination.length()
        val builder = Request.Builder().url(url).get()
        headers.forEach { (name, value) ->
            // Host/Content-Length are owned by the client; a stale Range would fight the
            // resume logic below.
            if (!name.equals("Host", true) && !name.equals("Range", true)) {
                runCatching { builder.header(name, value) }
            }
        }
        if (existing > 0) builder.header("Range", "bytes=$existing-")

        client.newCall(builder.build()).execute().use { response ->
            coroutineContext.ensureActive()
            val code = response.code
            if (code == 416 && existing > 0) {
                // The part file is already the whole file (or the server's idea of the
                // resource is smaller than it is). The engine verifies the size.
                return existing
            }
            if (!response.isSuccessful) {
                throw httpFailure(code)
            }

            val body = response.body
            val resuming = code == 206 && existing > 0
            val total = when {
                resuming -> response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L
                else -> body.contentLength().takeIf { it > 0 } ?: -1L
            }
            if (!resuming && existing > 0) destination.delete()

            RandomAccessFile(destination, "rw").use { file ->
                if (resuming) file.seek(existing) else file.setLength(0)
                val buffer = ByteArray(BUFFER_BYTES)
                var written = if (resuming) existing else 0L
                val source = body.byteStream()
                while (true) {
                    coroutineContext.ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    file.write(buffer, 0, read)
                    written += read
                    onProgress(written, total)
                }
                file.fd.sync()
                if (total > 0 && written != total) {
                    // A truncated response is indistinguishable from a finished one at
                    // the socket level; the byte count is the only proof.
                    throw DownloadException(
                        "incomplete transfer: $written of $total bytes",
                        retryable = true,
                    )
                }
                return written
            }
        }
    }

    private fun httpFailure(code: Int): DownloadException = when (code) {
        401, 403 -> DownloadException("the media server refused the request (HTTP $code)", retryable = false)
        404, 410 -> DownloadException("the file is no longer on the server (HTTP $code)", retryable = false)
        429 -> DownloadException("the media server is rate limiting (HTTP $code)", retryable = true)
        in 500..599 -> DownloadException("the media server failed (HTTP $code)", retryable = true)
        else -> DownloadException("unexpected HTTP $code", retryable = false)
    }

    companion object {
        /** Same default as `CONFIG_DOWNLOAD_RETRIES` in `config.default`. */
        const val DEFAULT_ATTEMPTS = 3

        private const val BUFFER_BYTES = 64 * 1024

        /** A hand-edited config can ask for anything; ten tries is already ~15 s of waiting. */
        private const val MAX_ATTEMPTS = 10

        private const val MAX_BACKOFF_MS = 8_000L
    }
}
