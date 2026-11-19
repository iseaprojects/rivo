package io.github.iseaprojects.rivo.data.net

import java.io.IOException
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/**
 * NewPipeExtractor's HTTP layer, backed by the shared OkHttp client.
 *
 * Two things matter here:
 *
 *  1. Cookies. NewPipe has no cookie storage of its own, so the only way to browse as a
 *     logged-in user is to attach the stored cookies to every request. [CookieProvider]
 *     scopes them by host, so the YouTube `SID` never travels to `i.ytimg.com` or to any
 *     other host the extractor talks to.
 *  2. Failure shape. NewPipe expects raw `IOException` for transport problems and
 *     `ReCaptchaException` for 429 (its "you are being rate limited" signal); everything
 *     else becomes an HTTP status that the backend maps to a typed failure.
 */
class NewPipeTransport(
    private val cookies: CookieProvider,
    private val client: okhttp3.OkHttpClient = HttpClients.shared,
) : Downloader() {

    override fun execute(request: Request): Response {
        val url = request.url()
        val builder = okhttp3.Request.Builder().url(url)

        val headers = request.headers()
        headers.forEach { (name, values) ->
            values.forEach { value -> builder.addHeader(name, value) }
        }

        val hasCookieHeader = headers.keys.any { it.equals("Cookie", true) }
        if (!hasCookieHeader) {
            cookies.cookieHeader(url)?.let { builder.header("Cookie", it) }
        }

        // NewPipe's own Downloader implementations always send a browser User-Agent; the
        // extractor's HTML paths (search, kiosks, channel pages) rely on it, because
        // YouTube serves a stripped page without `ytInitialData` to unknown clients.
        if (headers.keys.none { it.equals("User-Agent", true) }) {
            builder.header("User-Agent", DEFAULT_USER_AGENT)
        }

        val method = request.httpMethod().uppercase()
        val payload = request.dataToSend()
        when (method) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            else -> builder.method(method, payload?.toRequestBody(null) ?: EMPTY_BODY)
        }

        val response = try {
            client.newCall(builder.build()).execute()
        } catch (error: IOException) {
            throw error
        }

        response.use { http ->
            val code = http.code
            if (code == 429) {
                throw ReCaptchaException("HTTP 429 from $url", url)
            }
            val body = if (method == "HEAD") "" else http.body.readTextOrEmpty()
            return Response(
                code,
                http.message,
                http.headers.toMultimap(),
                body,
                http.request.url.toString(),
            )
        }
    }

    private fun ResponseBody.readTextOrEmpty(): String = try {
        string()
    } catch (_: IOException) {
        ""
    }

    private companion object {
        val EMPTY_BODY = ByteArray(0).toRequestBody(null)

        /** Chromium on Windows: the same shape of client YouTube serves the web app to. */
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
