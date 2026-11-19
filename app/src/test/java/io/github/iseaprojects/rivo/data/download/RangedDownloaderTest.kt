package io.github.iseaprojects.rivo.data.download

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Resume behaviour, against a real socket. A YouTube CDN URL is an ordinary HTTPS file,
 * so a `Range` request is the whole of "resumable download" — as long as a re-signed URL
 * answering `200` still restarts cleanly instead of appending garbage.
 */
class RangedDownloaderTest {

    private lateinit var server: HttpServer
    private lateinit var directory: File
    private lateinit var destination: File

    private val payload = ByteArray(4_096) { (it % 251).toByte() }
    private var rangeRequests = mutableListOf<String>()

    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .build()

    private val downloader = RangedDownloader(client)

    @Before
    fun setUp() {
        rangeRequests = mutableListOf()
        directory = Files.createTempDirectory("rivo-ranged").toFile()
        destination = File(directory, "part.bin")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
        directory.deleteRecursively()
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    private fun respond(exchange: HttpExchange, code: Int, body: ByteArray) {
        exchange.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    /** Honours `Range` the way a signed CDN URL does. */
    private fun ranged() {
        server.createContext("/file") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            rangeRequests += range ?: ""
            if (range == null) {
                respond(exchange, 200, payload)
                return@createContext
            }
            val start = range.removePrefix("bytes=").substringBefore('-').toIntOrNull() ?: 0
            if (start >= payload.size) {
                exchange.sendResponseHeaders(416, -1)
                exchange.close()
                return@createContext
            }
            exchange.responseHeaders.add(
                "Content-Range",
                "bytes $start-${payload.size - 1}/${payload.size}",
            )
            respond(exchange, 206, payload.copyOfRange(start, payload.size))
        }
    }

    @Test
    fun `a fresh download writes every byte`() = runBlocking {
        ranged()
        var total = -1L
        var last = 0L
        val written = downloader.download(
            url = url("/file"),
            headers = emptyMap(),
            destination = destination,
        ) { done, size ->
            last = done
            total = size
        }

        assertEquals(payload.size.toLong(), written)
        assertArrayEquals(payload, destination.readBytes())
        assertEquals(payload.size.toLong(), last)
        assertEquals(payload.size.toLong(), total)
        assertEquals(listOf(""), rangeRequests)
    }

    @Test
    fun `an interrupted transfer resumes from the part file`() = runBlocking {
        ranged()
        destination.writeBytes(payload.copyOfRange(0, 1_024))

        val written = downloader.download(url("/file"), emptyMap(), destination)

        assertEquals(payload.size.toLong(), written)
        assertArrayEquals(payload, destination.readBytes())
        assertEquals(listOf("bytes=1024-"), rangeRequests)
    }

    @Test
    fun `a server answering 200 to a range restarts instead of appending`() = runBlocking {
        // A re-resolved URL is a new signature; a CDN answering 200 means the old offset
        // is meaningless, so the part file must be thrown away rather than kept.
        server.createContext("/file") { exchange ->
            rangeRequests += exchange.requestHeaders.getFirst("Range") ?: ""
            respond(exchange, 200, payload)
        }
        destination.writeBytes(ByteArray(1_024) { 'x'.code.toByte() })

        val written = downloader.download(url("/file"), emptyMap(), destination)

        assertEquals(payload.size.toLong(), written)
        assertArrayEquals(payload, destination.readBytes())
    }

    @Test
    fun `a complete part file answered with 416 is reported as written`() = runBlocking {
        ranged()
        destination.writeBytes(payload.copyOfRange(0, payload.size))

        val written = downloader.download(url("/file"), emptyMap(), destination)

        assertEquals(payload.size.toLong(), written)
        assertArrayEquals(payload, destination.readBytes())
    }

    @Test
    fun `a missing file fails without retrying`() = runBlocking {
        var hits = 0
        server.createContext("/file") { exchange ->
            hits++
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }

        try {
            downloader.download(url("/file"), emptyMap(), destination)
            fail("a 404 should fail")
        } catch (error: DownloadException) {
            assertFalse(error.retryable)
            assertTrue(error.message.orEmpty().contains("404"))
        }
        // One request: a definitive verdict is not worth a second attempt.
        assertEquals(1, hits)
    }

    @Test
    fun `a forbidden URL is not retried either`() = runBlocking {
        server.createContext("/file") { exchange ->
            exchange.sendResponseHeaders(403, -1)
            exchange.close()
        }
        try {
            downloader.download(url("/file"), emptyMap(), destination)
            fail("a 403 should fail")
        } catch (error: DownloadException) {
            assertFalse(error.retryable)
        }
    }

    @Test
    fun `a truncated body fails after the retry budget`() = runBlocking {
        var hits = 0
        server.createContext("/file") { exchange ->
            hits++
            // Promise a full file, send half, hang up: the byte count is the only proof.
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload.copyOfRange(0, payload.size / 2)) }
        }

        try {
            RangedDownloader(client, attempts = 2).download(url("/file"), emptyMap(), destination)
            fail("a truncated body should fail")
        } catch (error: DownloadException) {
            assertTrue(error.retryable)
            assertTrue(error.message.orEmpty().contains("attempts"))
        }
        assertEquals(2, hits)
    }

    @Test
    fun `request headers are forwarded`() = runBlocking {
        var userAgent: String? = null
        var range: String? = null
        server.createContext("/file") { exchange ->
            userAgent = exchange.requestHeaders.getFirst("User-Agent")
            range = exchange.requestHeaders.getFirst("Range")
            respond(exchange, 200, payload)
        }
        downloader.download(
            url = url("/file"),
            headers = mapOf("User-Agent" to "rivo-test", "Range" to "bytes=99-"),
            destination = destination,
        )
        assertEquals("rivo-test", userAgent)
        // The caller's stale Range would fight the resume logic.
        assertEquals(null, range)
    }

    @Test
    fun `an empty body is still a successful empty file`() = runBlocking {
        server.createContext("/file") { exchange ->
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        val written = downloader.download(url("/file"), emptyMap(), destination)
        assertEquals(0L, written)
        assertTrue(destination.exists())
    }
}
