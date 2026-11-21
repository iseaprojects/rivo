package io.github.iseaprojects.rivo.data.net

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The difference between the two clients is the whole point: `callTimeout` bounds the
 * complete call, response body included, so the shared client is fine for an extractor
 * request and wrong for a file transfer that legitimately takes minutes.
 */
class HttpClientsTest {

    private lateinit var server: HttpServer

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/slow") { exchange -> dribble(exchange) }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun url() = "http://127.0.0.1:${server.address.port}/slow"

    /** Six bytes, one every [SLEEP_MS]: long enough to outlive a short call deadline. */
    private fun dribble(exchange: HttpExchange) {
        try {
            exchange.sendResponseHeaders(200, PAYLOAD.size.toLong())
            val body = exchange.responseBody
            for (byte in PAYLOAD) {
                body.write(byte.toInt())
                body.flush()
                Thread.sleep(SLEEP_MS)
            }
        } finally {
            exchange.close()
        }
    }

    private fun readAll(client: OkHttpClient): ByteArray =
        client.newCall(
            okhttp3.Request.Builder().url(url()).build(),
        ).execute().use { it.body.bytes() }

    @Test
    fun `a whole-call deadline cuts a slow body short`() {
        assertEquals(45_000, HttpClients.shared.callTimeoutMillis)
        val short = HttpClients.shared.newBuilder()
            .callTimeout(300, TimeUnit.MILLISECONDS)
            .build()
        val failed = runCatching { readAll(short) }
        assertTrue(failed.exceptionOrNull() is IOException)
    }

    @Test
    fun `the transfer client reads a slow body to the end`() {
        assertEquals(0, HttpClients.transfers.callTimeoutMillis)
        assertTrue(readAll(HttpClients.transfers).contentEquals(PAYLOAD))
    }

    private companion object {
        val PAYLOAD = byteArrayOf(1, 2, 3, 4, 5, 6)
        const val SLEEP_MS = 120L
    }
}
