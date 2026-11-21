package io.github.iseaprojects.rivo.data.download

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * `CONFIG_DOWNLOAD_ARCHIVE`: never download the same video twice.
 *
 * One line per finished rendition, `kind<TAB>videoId`, in the app's private files. yt-x
 * keys the archive by "kind + playlist URL"; the playlist is not part of our key because
 * the bytes are identical and re-downloading a video because it appeared in a second
 * playlist is exactly what the archive exists to prevent.
 *
 * Every method takes [lock]: the service adds to the archive from its own coroutine while
 * the library screens query and remove from it, and an unlocked `LinkedHashSet` plus a
 * lazily loaded file meant two callers could both load, or one could rehash while another
 * iterated.
 */
class DownloadArchive(private val file: File) {

    private val lock = Mutex()
    private val cache = LinkedHashSet<String>()
    private var loaded = false

    suspend fun contains(task: DownloadTask): Boolean = lock.withLock {
        ensureLoaded()
        task.archiveKey in cache
    }

    suspend fun add(task: DownloadTask): Boolean = lock.withLock {
        ensureLoaded()
        if (!cache.add(task.archiveKey)) return@withLock false
        withContext(Dispatchers.IO) {
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(task.archiveKey + "\n")
            }
        }
        true
    }

    /** Forgets a video so deleting its file and downloading it again works as expected. */
    suspend fun remove(videoId: String): Boolean = lock.withLock {
        ensureLoaded()
        val removed = cache.removeAll { it.endsWith("\t$videoId") }
        if (!removed) return@withLock false
        withContext(Dispatchers.IO) {
            runCatching { file.writeText(cache.joinToString("\n", postfix = "\n")) }
        }
        true
    }

    suspend fun size(): Int = lock.withLock {
        ensureLoaded()
        cache.size
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        val text = withContext(Dispatchers.IO) { runCatching { file.readText() }.getOrDefault("") }
        cache.clear()
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach(cache::add)
        loaded = true
    }

    companion object {
        const val FILE_NAME = "archive.txt"
    }
}

/** Identity used by [DownloadArchive]; kind + id, never the URL (which expires). */
internal val DownloadTask.archiveKey: String
    get() = "${kind.name.lowercase()}\t$videoId"
