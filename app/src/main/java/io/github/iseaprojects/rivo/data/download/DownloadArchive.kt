package io.github.iseaprojects.rivo.data.download

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `CONFIG_DOWNLOAD_ARCHIVE`: never download the same video twice.
 *
 * One line per finished rendition, `kind<TAB>videoId`, in the app's private files. yt-x
 * keys the archive by "kind + playlist URL"; the playlist is not part of our key because
 * the bytes are identical and re-downloading a video because it appeared in a second
 * playlist is exactly what the archive exists to prevent.
 */
class DownloadArchive(private val file: File) {

    private val cache = LinkedHashSet<String>()
    private var loaded = false

    suspend fun contains(task: DownloadTask): Boolean {
        ensureLoaded()
        return task.archiveKey in cache
    }

    suspend fun add(task: DownloadTask) {
        ensureLoaded()
        if (!cache.add(task.archiveKey)) return
        withContext(Dispatchers.IO) {
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(task.archiveKey + "\n")
            }
        }
    }

    /** Forgets a video so deleting its file and downloading it again works as expected. */
    suspend fun remove(videoId: String) {
        ensureLoaded()
        val removed = cache.removeAll { it.endsWith("\t$videoId") }
        if (!removed) return
        withContext(Dispatchers.IO) {
            runCatching { file.writeText(cache.joinToString("\n", postfix = "\n")) }
        }
    }

    fun size(): Int = cache.size

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
