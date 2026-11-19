package io.github.iseaprojects.rivo.data.download

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The persistent download queue.
 *
 * JSON rather than a Room table on purpose (D40): the queue is a small, ordering-sensitive
 * list that is replaced wholesale on every transition, and a second schema would mean a
 * migration on the library database — the one thing the first release promised never to
 * risk (`fallbackToDestructiveMigration` is deliberately absent).
 *
 * Progress counters are *not* written per chunk; they live in [progress] for the
 * notification and only the final byte count reaches disk with the `DONE` transition.
 * That keeps a 4 GB download from rewriting the queue file a thousand times.
 */
class DownloadStore(
    private val file: File,
    private val maxHistory: Int = MAX_HISTORY,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val lock = Mutex()
    private val loaded = AtomicBoolean(false)

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    /** Live progress, id -> 0..1. Not persisted: a restart resumes from the part file. */
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    /** Replaces the queue in memory; stale `RUNNING` rows become `QUEUED` again. */
    suspend fun load() {
        if (loaded.get()) return
        lock.withLock {
            if (loaded.get()) return
            loaded.set(true)
            val stored = withContext(Dispatchers.IO) { read() }
            _tasks.value = stored.map { task ->
                if (task.state == DownloadState.RUNNING) task.copy(state = DownloadState.QUEUED) else task
            }
        }
    }

    /**
     * Adds tasks. A queued, running or paused duplicate is left alone; a finished, failed
     * or cancelled one is revived in place, which is what makes "download again" mean
     * "retry" instead of "second row".
     */
    suspend fun enqueue(incoming: List<DownloadTask>): List<DownloadTask> {
        if (incoming.isEmpty()) return emptyList()
        load()
        return lock.withLock {
            val byId = LinkedHashMap<String, DownloadTask>()
            _tasks.value.forEach { byId[it.id] = it }
            val added = mutableListOf<DownloadTask>()
            incoming.forEach { task ->
                when (val existing = byId[task.id]) {
                    null -> {
                        byId[task.id] = task
                        added += task
                    }

                    else -> if (existing.state in REVIVABLE) {
                        val revived = existing.copy(
                            state = DownloadState.QUEUED,
                            error = null,
                            bytesDone = 0,
                            outputPath = null,
                            createdAt = System.currentTimeMillis(),
                        )
                        byId[task.id] = revived
                        added += revived
                    }
                }
            }
            val list = byId.values.toList()
            persist(list)
            added
        }
    }

    suspend fun update(id: String, transform: (DownloadTask) -> DownloadTask) {
        load()
        lock.withLock {
            val current = _tasks.value
            val index = current.indexOfFirst { it.id == id }
            if (index < 0) return
            val list = current.toMutableList()
            list[index] = transform(list[index])
            persist(list)
        }
    }

    suspend fun remove(id: String) {
        load()
        lock.withLock {
            _progress.update { it - id }
            persist(_tasks.value.filterNot { it.id == id })
        }
    }

    suspend fun clearFinished() {
        load()
        lock.withLock {
            persist(_tasks.value.filter { it.state !in FINISHED })
        }
    }

    /** The next task the service may start. */
    fun nextQueued(): DownloadTask? = _tasks.value.firstOrNull { it.state == DownloadState.QUEUED }

    fun isActive(id: String): Boolean = _tasks.value.any { it.id == id && it.isActive }

    fun setProgress(id: String, fraction: Float) {
        _progress.update { it + (id to fraction.coerceIn(0f, 1f)) }
    }

    fun clearProgress(id: String) {
        _progress.update { it - id }
    }

    private suspend fun persist(list: List<DownloadTask>) {
        val capped = capHistory(list)
        _tasks.value = capped
        withContext(Dispatchers.IO) { write(capped) }
    }

    /**
     * Oldest finished rows fall off first. Queue rows are never dropped: a task the user
     * asked for must not disappear because the history is long.
     */
    private fun capHistory(list: List<DownloadTask>): List<DownloadTask> {
        if (list.size <= maxHistory) return list
        val active = list.filter { it.isActive }
        val finished = list.filterNot { it.isActive }.sortedByDescending { it.createdAt }
        val budget = (maxHistory - active.size).coerceAtLeast(0)
        return active + finished.take(budget)
    }

    private fun read(): List<DownloadTask> = runCatching {
        if (!file.exists()) return emptyList()
        json.decodeFromString(QueueFile.serializer(), file.readText()).tasks
    }.getOrDefault(emptyList())

    private fun write(tasks: List<DownloadTask>) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json.encodeToString(QueueFile.serializer(), QueueFile(tasks = tasks)))
            // rename() is atomic within a directory; a half-written queue file would
            // silently drop every pending download on the next start.
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        }
    }

    @Serializable
    private data class QueueFile(val version: Int = 1, val tasks: List<DownloadTask> = emptyList())

    companion object {
        const val MAX_HISTORY = 200
        const val FILE_NAME = "queue.json"

        private val REVIVABLE = setOf(
            DownloadState.DONE,
            DownloadState.FAILED,
            DownloadState.CANCELLED,
        )
        private val FINISHED = setOf(
            DownloadState.DONE,
            DownloadState.CANCELLED,
        )
    }
}
