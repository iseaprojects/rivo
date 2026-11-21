package io.github.iseaprojects.rivo.data.download

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The queue file is the only thing standing between a reboot and a lost download, so the
 * transitions that survive a process death are pinned here.
 */
class DownloadStoreTest {

    private lateinit var directory: File
    private lateinit var file: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("rivo-queue").toFile()
        file = File(directory, DownloadStore.FILE_NAME)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun task(
        id: String = "abc",
        kind: DownloadKind = DownloadKind.VIDEO,
        state: DownloadState = DownloadState.QUEUED,
        createdAt: Long = 1_000,
    ) = DownloadTask(
        videoId = id,
        url = "https://www.youtube.com/watch?v=$id",
        title = "Video $id",
        kind = kind,
        state = state,
        createdAt = createdAt,
    )

    @Test
    fun `a missing queue file is an empty queue`() = runBlocking {
        val store = DownloadStore(file)
        store.load()
        assertTrue(store.tasks.value.isEmpty())
    }

    @Test
    fun `a running task is requeued on load`() = runBlocking {
        DownloadStore(file).enqueue(
            listOf(task(state = DownloadState.RUNNING), task(id = "def", state = DownloadState.DONE)),
        )
        val reloaded = DownloadStore(file)
        reloaded.load()

        assertEquals(
            DownloadState.QUEUED,
            reloaded.tasks.value.first { it.videoId == "abc" }.state,
        )
        assertEquals(
            DownloadState.DONE,
            reloaded.tasks.value.first { it.videoId == "def" }.state,
        )
    }

    @Test
    fun `enqueueing an active duplicate changes nothing`() = runBlocking {
        val store = DownloadStore(file)
        store.enqueue(listOf(task()))
        val added = store.enqueue(listOf(task()))
        assertTrue(added.isEmpty())
        assertEquals(1, store.tasks.value.size)
    }

    @Test
    fun `enqueueing a finished video revives it in place`() = runBlocking {
        val store = DownloadStore(file)
        store.enqueue(listOf(task(state = DownloadState.DONE)))
        val added = store.enqueue(listOf(task().copy(bytesDone = 10, outputPath = "video/x.mp4")))
        assertEquals(1, added.size)
        assertEquals(1, store.tasks.value.size)
        val revived = store.tasks.value.single()
        assertEquals(DownloadState.QUEUED, revived.state)
        assertEquals(0, revived.bytesDone)
        assertNull(revived.outputPath)
    }

    @Test
    fun `video and audio of the same video are two rows`() = runBlocking {
        val store = DownloadStore(file)
        store.enqueue(listOf(task(), task(kind = DownloadKind.AUDIO)))
        assertEquals(2, store.tasks.value.size)
        assertEquals(setOf("video:abc", "audio:abc"), store.tasks.value.map { it.id }.toSet())
    }

    @Test
    fun `finishing a task writes the final byte count to disk`() = runBlocking {
        val store = DownloadStore(file)
        store.enqueue(listOf(task()))
        store.update("video:abc") {
            it.copy(state = DownloadState.DONE, bytesDone = 4_096, outputPath = "video/a.mp4")
        }

        val reloaded = DownloadStore(file)
        reloaded.load()
        val done = reloaded.tasks.value.single()
        assertEquals(DownloadState.DONE, done.state)
        assertEquals(4_096, done.bytesDone)
        assertEquals("video/a.mp4", done.outputPath)
    }

    @Test
    fun `removing a task also drops its live progress`() = runBlocking {
        val store = DownloadStore(file)
        store.enqueue(listOf(task()))
        store.setProgress("video:abc", 0.5f)
        store.remove("video:abc")
        assertTrue(store.tasks.value.isEmpty())
        assertNull(store.progress.value["video:abc"])
    }

    @Test
    fun `progress is clamped to the zero to one range`() {
        val store = DownloadStore(file)
        store.setProgress("a", 4f)
        store.setProgress("b", -2f)
        assertEquals(1f, store.progress.value["a"])
        assertEquals(0f, store.progress.value["b"])
    }

    @Test
    fun `clear finished keeps queued rows and drops completed ones`() = runBlocking {
        val store = DownloadStore(file)
        store.enqueue(
            listOf(
                task(id = "a", state = DownloadState.DONE),
                task(id = "b", state = DownloadState.CANCELLED),
                task(id = "c"),
                task(id = "d", state = DownloadState.FAILED),
            ),
        )
        store.clearFinished()
        assertEquals(listOf("c", "d"), store.tasks.value.map { it.videoId })
    }

    @Test
    fun `next queued skips running and paused rows`() = runBlocking {
        val store = DownloadStore(file)
        store.enqueue(
            listOf(
                task(id = "a", state = DownloadState.RUNNING),
                task(id = "b", state = DownloadState.PAUSED),
                task(id = "c"),
            ),
        )
        assertEquals("video:c", store.nextQueued()?.id)
    }

    @Test
    fun `history is capped without ever dropping a queued task`() = runBlocking {
        val store = DownloadStore(file, maxHistory = 3)
        store.enqueue(listOf(task(id = "keep")))
        (1..10).forEach { index ->
            store.enqueue(listOf(task(id = "done$index", state = DownloadState.DONE, createdAt = index.toLong())))
        }
        val ids = store.tasks.value.map { it.videoId }
        assertTrue("queued row was dropped: $ids", ids.contains("keep"))
        assertTrue("history exceeded the cap: ${ids.size}", ids.size <= 3)
    }

    @Test
    fun `a corrupt queue file degrades to an empty queue`() = runBlocking {
        file.parentFile?.mkdirs()
        file.writeText("{not json")
        val store = DownloadStore(file)
        store.load()
        assertTrue(store.tasks.value.isEmpty())

        store.enqueue(listOf(task()))
        val recovered = DownloadStore(file)
        recovered.load()
        assertEquals(1, recovered.tasks.value.size)
    }

    @Test
    fun `a corrupt queue file is moved aside instead of overwritten`() = runBlocking {
        file.parentFile?.mkdirs()
        file.writeText("{not json")
        val store = DownloadStore(file)
        store.load()

        // The broken bytes are the only copy of whatever the user had queued, so they are
        // kept for a bug report instead of being replaced by the next persist.
        val quarantined = File(directory, "${file.name}.corrupt")
        assertTrue("the corrupt queue was overwritten", quarantined.exists())
        assertEquals("{not json", quarantined.readText())

        store.enqueue(listOf(task()))
        assertTrue("the store stayed unloaded", store.tasks.value.isNotEmpty())
    }

    @Test
    fun `an unreadable queue file does not strand the store`() = runBlocking {
        // A directory where the file should be: `exists()` is true and `readText()` throws.
        file.mkdirs()
        val store = DownloadStore(file)
        store.load()
        assertTrue(store.tasks.value.isEmpty())

        store.enqueue(listOf(task(id = "after")))
        val recovered = DownloadStore(file)
        recovered.load()
        assertEquals(listOf("after"), recovered.tasks.value.map { it.videoId })
    }

    @Test
    fun `the queue file is valid json on disk`() = runBlocking {
        val store = DownloadStore(file)
        store.enqueue(listOf(task()))
        val text = file.readText()
        assertTrue(text.contains("\"tasks\""))
        assertFalse("a temp file was left behind", File(directory, "${file.name}.tmp").exists())
    }

    @Test
    fun `a task that is not in the queue cannot be updated`() = runBlocking {
        val store = DownloadStore(file)
        store.update("video:missing") { it.copy(state = DownloadState.DONE) }
        assertTrue(store.tasks.value.isEmpty())
    }

    @Test
    fun `queued tasks survive a reload in order`() = runBlocking {
        val store = DownloadStore(file)
        store.enqueue(listOf(task(id = "a"), task(id = "b"), task(id = "c")))
        val reloaded = DownloadStore(file)
        reloaded.load()
        assertEquals(listOf("a", "b", "c"), reloaded.tasks.value.map { it.videoId })
        assertNotNull(reloaded.nextQueued())
    }
}
