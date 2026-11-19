package io.github.iseaprojects.rivo.data.download

import android.content.Context
import io.github.iseaprojects.rivo.core.config.ConfigKeys
import io.github.iseaprojects.rivo.core.config.ConfigSnapshot
import io.github.iseaprojects.rivo.core.model.MediaSummary
import io.github.iseaprojects.rivo.core.model.PlaylistSummary
import io.github.iseaprojects.rivo.data.extract.ContinueToken
import io.github.iseaprojects.rivo.data.extract.ExtractorRouter
import io.github.iseaprojects.rivo.download.DownloadService
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What an enqueue actually did; the menu turns this into one sentence. */
data class EnqueueOutcome(
    val added: Int,
    val skipped: Int = 0,
    val problem: String? = null,
)

/**
 * The downloader's public surface: queue, files on disk, and the operations the menu
 * offers.
 *
 * The repository is the only place that resolves the download folder, so the queue, the
 * browser and the pre-flight checks always agree on where files are going. It also owns
 * the cached index: walking a SAF tree on every menu rebuild would be far too slow, so
 * the scan is refreshed on enqueue, on completion and when the user opens the screen.
 */
class DownloadRepository(
    private val context: Context,
    internal val store: DownloadStore,
    internal val archive: DownloadArchive,
    private val router: ExtractorRouter,
    private val config: () -> ConfigSnapshot,
    private val scope: CoroutineScope,
) {

    private val files = MutableStateFlow<List<DownloadedFile>>(emptyList())
    private val folderProblem = MutableStateFlow<String?>(null)

    val snapshot: StateFlow<DownloadSnapshot> = combine(
        store.tasks,
        files,
        folderProblem,
    ) { tasks, downloaded, problem ->
        DownloadSnapshot(tasks = tasks, files = downloaded, folderProblem = problem)
    }.stateIn(scope, SharingStarted.Eagerly, DownloadSnapshot.EMPTY)

    val progress: StateFlow<Map<String, Float>> = store.progress

    init {
        scope.launch {
            store.load()
            // Scratch space outlives the process only for paused transfers; everything
            // else is a leftover and would otherwise sit in the cache forever.
            val paused = store.tasks.value
                .filter { it.state == DownloadState.PAUSED }
                .map { it.id }
                .toSet()
            withContext(Dispatchers.IO) { DownloadEngine.cleanupCache(context, paused) }
            refresh()
            // A queue left behind by process death resumes as soon as the app starts.
            if (store.tasks.value.any { it.state == DownloadState.QUEUED }) {
                DownloadService.start(context)
            }
        }
    }

    suspend fun enqueue(
        items: List<MediaSummary>,
        kind: DownloadKind,
        playlistTitle: String? = null,
    ): EnqueueOutcome {
        if (items.isEmpty()) return EnqueueOutcome(added = 0)
        val tasks = items.map { item -> taskOf(item, kind, playlistTitle) }
        return enqueueTasks(tasks)
    }

    /** Pages the whole playlist (not just the visible window) and queues every item. */
    suspend fun enqueuePlaylist(
        playlist: PlaylistSummary,
        kind: DownloadKind,
    ): EnqueueOutcome {
        if (playlist.url.isBlank()) return EnqueueOutcome(added = 0, problem = "the playlist has no URL")
        val backend = router.forUrl(playlist.url)
        val items = LinkedHashMap<String, MediaSummary>()
        var token: ContinueToken? = null
        try {
            do {
                val page = backend.playlist(playlist.url, token)
                page.page.items.forEach { item ->
                    if (items.size < MAX_PLAYLIST_ITEMS) items.putIfAbsent(item.id.ifBlank { item.url }, item)
                }
                token = page.page.nextToken
            } while (token != null && items.size < MAX_PLAYLIST_ITEMS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return EnqueueOutcome(added = 0, problem = error.message ?: "the playlist could not be read")
        }
        return enqueue(items.values.toList(), kind, playlist.title)
    }

    private suspend fun enqueueTasks(tasks: List<DownloadTask>): EnqueueOutcome {
        var skipped = 0
        val accepted = tasks.filter { task ->
            val known = config().bool(ConfigKeys.DOWNLOAD_ARCHIVE, false) && archive.contains(task)
            if (known) skipped++
            !known
        }
        if (accepted.isEmpty()) {
            return EnqueueOutcome(added = 0, skipped = skipped)
        }
        val added = store.enqueue(accepted)
        if (added.isNotEmpty()) DownloadService.start(context)
        return EnqueueOutcome(added = added.size, skipped = skipped)
    }

    /**
     * Pausing a queued row is just a state change; pausing a running one has to stop the
     * worker first, or its completion would flip the row back to `DONE`.
     */
    suspend fun pause(id: String) {
        val running = store.tasks.value.firstOrNull { it.id == id }?.state == DownloadState.RUNNING
        if (running) {
            DownloadService.pause(context, id)
        } else {
            store.update(id) { it.copy(state = DownloadState.PAUSED) }
        }
        store.clearProgress(id)
    }

    suspend fun resume(id: String) {
        store.update(id) { it.copy(state = DownloadState.QUEUED, error = null) }
        DownloadService.start(context)
    }

    /** Cancelling a running task is cooperative: the service marks it and stops the job. */
    suspend fun cancel(id: String) {
        store.update(id) { it.copy(state = DownloadState.CANCELLED, error = null) }
        store.clearProgress(id)
        DownloadService.cancel(context, id)
    }

    suspend fun retry(id: String) {
        store.update(id) {
            it.copy(state = DownloadState.QUEUED, error = null, bytesDone = 0, outputPath = null)
        }
        DownloadService.start(context)
    }

    suspend fun remove(id: String) {
        store.remove(id)
        store.clearProgress(id)
    }

    suspend fun clearFinished() {
        val finished = store.tasks.value.filter { it.isFinished || it.state == DownloadState.CANCELLED }
        finished.forEach { store.clearProgress(it.id) }
        store.clearFinished()
        refresh()
    }

    suspend fun deleteFile(file: DownloadedFile, forgetArchive: Boolean = true): Boolean {
        val target = resolveTarget() ?: return false
        val deleted = target.delete(file)
        if (deleted) {
            val owner = ownerOf(file)
            if (forgetArchive) {
                val videoId = owner?.videoId
                    ?: file.name.substringAfterLast('[', "").substringBefore(']').substringBefore('.').ifBlank { null }
                videoId?.let { archive.remove(it) }
            }
            owner?.let { task ->
                store.update(task.id) { it.copy(outputPath = null, state = DownloadState.DONE) }
            }
            refresh()
        }
        return deleted
    }

    /**
     * The queue row that produced [file].
     *
     * The stored path is relative to the download folder, the same shape `list()` reports,
     * so an exact match is the common case. Comparisons still ignore a trailing separator,
     * and when the path does not line up (a row written before the layout changed, or a
     * file the queue no longer remembers) a unique file name decides.
     */
    private fun ownerOf(file: DownloadedFile): DownloadTask? {
        val tasks = store.tasks.value
        val path = file.relativePath.trim('/')
        tasks.firstOrNull { it.outputPath?.trim('/') == path }?.let { return it }
        return tasks.filter { it.outputPath?.substringAfterLast('/') == file.name }.singleOrNull()
    }

    /** Deletes by URI, which is what a menu row carries. */
    suspend fun deleteFileById(id: String): Boolean {
        val file = snapshot.value.files.firstOrNull { it.uri.toString() == id } ?: return false
        return deleteFile(file)
    }

    /** Re-reads the folder; called when the screen opens and after every completion. */
    suspend fun refresh(): List<DownloadedFile> {
        val target = try {
            DownloadFolder.resolve(context, config().string(ConfigKeys.DOWNLOAD_DIR))
        } catch (error: Throwable) {
            folderProblem.value = error.message ?: "the download folder is not available"
            files.value = emptyList()
            return emptyList()
        }
        val listed = withContext(Dispatchers.IO) {
            runCatching { target.list() }
        }
        folderProblem.value = listed.exceptionOrNull()?.let { it.message ?: "the download folder could not be read" }
        val value = listed.getOrDefault(emptyList())
        files.value = value
        return value
    }

    /** The folder contents for callers that need them now (an extension's `library` link). */
    suspend fun filesOnce(): List<DownloadedFile> = refresh()

    /** Description of the configured folder for the settings row. */
    fun folderLabel(): String = runCatching {
        DownloadFolder.resolve(context, config().string(ConfigKeys.DOWNLOAD_DIR)).description
    }.getOrElse { config().string(ConfigKeys.DOWNLOAD_DIR).ifBlank { DownloadNaming.DEFAULT_FOLDER } }

    fun isArchiveEnabled(): Boolean = config().bool(ConfigKeys.DOWNLOAD_ARCHIVE, false)

    fun resolveTarget(): DownloadTarget? = runCatching {
        DownloadFolder.resolve(context, config().string(ConfigKeys.DOWNLOAD_DIR))
    }.getOrNull()

    private fun taskOf(item: MediaSummary, kind: DownloadKind, playlistTitle: String?): DownloadTask =
        DownloadTask(
            videoId = item.id.ifBlank { item.url },
            url = item.url,
            title = item.title,
            kind = kind,
            channel = item.channelName,
            playlist = playlistTitle,
            thumbnailUrl = item.thumbnailUrl,
            durationSeconds = item.durationSeconds,
        )

    companion object {
        const val MAX_PLAYLIST_ITEMS = 200

        fun create(context: Context, router: ExtractorRouter, config: () -> ConfigSnapshot, scope: CoroutineScope): DownloadRepository {
            val root = File(context.filesDir, "downloads").apply { mkdirs() }
            return DownloadRepository(
                context = context,
                store = DownloadStore(File(root, DownloadStore.FILE_NAME)),
                archive = DownloadArchive(File(root, DownloadArchive.FILE_NAME)),
                router = router,
                config = config,
                scope = scope,
            )
        }
    }
}
