package io.github.iseaprojects.rivo.core.update

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.iseaprojects.rivo.core.config.ConfigSnapshot
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The six-hour window is only real if it survives process death: the repository's state
 * object starts empty on every launch, so the throttle also needs the persisted stamp the
 * graph passes in. These tests cover the hand-off between the two.
 */
class UpdateRepositoryTest {

    private lateinit var server: HttpServer
    private val requests = AtomicInteger()
    private var body = "[]"

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/releases") { exchange -> respond(exchange) }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun respond(exchange: HttpExchange) {
        requests.incrementAndGet()
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private fun repository(
        scope: CoroutineScope,
        now: Long,
        stored: Long,
        store: (Long) -> Unit = {},
        enabled: Boolean = true,
    ) = UpdateRepository(
        config = {
            ConfigSnapshot(
                mapOf(
                    "CONFIG_CHECK_FOR_UPDATES" to enabled.toString(),
                    "CONFIG_UPDATE_CHANNEL" to "stable",
                ),
            )
        },
        scope = scope,
        currentVersion = "0.8.7",
        endpoint = "http://127.0.0.1:${server.address.port}/releases",
        clock = { now },
        lastUpToDateCheck = { stored },
        rememberUpToDateCheck = store,
    )

    /**
     * The repository checks once from its constructor. These tests drive `check()` by hand,
     * so the constructor's job gets a dispatcher that parks instead of running: otherwise
     * the request count would depend on a race.
     */
    private fun parkedScope(): CoroutineScope =
        CoroutineScope(Job() + ParkedDispatcher)

    private object ParkedDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
    }

    @Test
    fun `a fresh persisted check skips the request and reports it`() {
        val scope = parkedScope()
        try {
            var stored = 0L
            val repo = repository(scope, now = TEN_AM, stored = TEN_AM - HOUR, store = { stored = it })

            runBlocking { repo.check(force = false) }

            assertEquals(0, requests.get())
            val state = repo.state.value
            assertTrue(state is UpdateState.Current)
            assertEquals(TEN_AM - HOUR, (state as UpdateState.Current).checkedAt)
            assertEquals(0L, stored)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a stale persisted check asks GitHub once and remembers the answer`() {
        val scope = parkedScope()
        try {
            var stored = 0L
            val repo = repository(scope, now = TEN_AM, stored = TEN_AM - DAY, store = { stored = it })

            runBlocking { repo.check(force = false) }

            assertEquals(1, requests.get())
            assertEquals(UpdateState.Current("0.8.7", TEN_AM), repo.state.value)
            assertEquals(TEN_AM, stored)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `an available release is never remembered as checked`() {
        body = """
            [
              {
                "tag_name": "v0.9.0",
                "name": "0.9.0",
                "body": "notes",
                "html_url": "https://example.invalid/0.9.0",
                "prerelease": false,
                "draft": false,
                "published_at": "2026-01-01T00:00:00Z"
              }
            ]
        """.trimIndent()
        val scope = parkedScope()
        try {
            var stored = 1234L
            val repo = repository(scope, now = TEN_AM, stored = 0L, store = { stored = it })

            runBlocking { repo.check(force = false) }

            assertTrue(repo.state.value is UpdateState.Available)
            // 0 means "nothing to skip next time": a pending update is worth re-checking.
            assertEquals(0L, stored)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `force ignores the persisted stamp`() {
        val scope = parkedScope()
        try {
            val repo = repository(scope, now = TEN_AM, stored = TEN_AM - MINUTE)

            runBlocking { repo.check(force = true) }

            assertEquals(1, requests.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a failure is not cached and clears the stamp`() {
        body = "not json at all"
        val scope = parkedScope()
        try {
            var stored = 999L
            val repo = repository(scope, now = TEN_AM, stored = TEN_AM - DAY, store = { stored = it })

            runBlocking { repo.check(force = false) }

            assertTrue(repo.state.value is UpdateState.Failed)
            assertEquals(0L, stored)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the setting turns the check off without a request`() {
        val scope = parkedScope()
        try {
            val repo = repository(scope, now = TEN_AM, stored = 0L, enabled = false)

            runBlocking { repo.check(force = true) }

            assertEquals(0, requests.get())
            assertEquals(UpdateState.Idle, repo.state.value)
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
        const val TEN_AM = 1_800_000_000_000L
    }
}
