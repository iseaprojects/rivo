package io.github.iseaprojects.rivo.platform

import android.content.Context
import io.github.iseaprojects.rivo.core.cache.CacheRetention
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a cleanup actually removed, for the snackbar in the config screen. */
data class CacheCleanResult(
    val files: Int = 0,
    val directories: Int = 0,
    val bytes: Long = 0,
)

/**
 * Removes cache entries older than the configured retention.
 *
 * `cache/downloads` is deliberately skipped: those are part files a paused download still
 * needs, and deleting them would silently turn "Resume" into "start over". The same applies
 * to the yt-dlp cookie export only in the sense that it is regenerated on demand, so it is
 * cleaned with the rest.
 */
object CacheCleaner {

    suspend fun clean(context: Context, days: Int, now: Long = System.currentTimeMillis()): CacheCleanResult =
        withContext(Dispatchers.IO) {
            sweep(context.cacheDir, CacheRetention.cutoff(now, days), keep = setOf("downloads"))
        }

    private fun sweep(root: File?, cutoff: Long, keep: Set<String>): CacheCleanResult {
        if (root == null || !root.isDirectory) return CacheCleanResult()
        var files = 0
        var directories = 0
        var bytes = 0L
        root.listFiles()?.forEach { entry ->
            if (entry.name in keep) return@forEach
            if (entry.isDirectory) {
                // Read the timestamp *before* recursing. Deleting a child bumps the
                // directory's own mtime to now, so the old check against `cutoff` after the
                // sweep never matched and empty directories were left behind for good.
                val modified = entry.lastModified()
                val nested = sweep(entry, cutoff, emptySet())
                files += nested.files
                directories += nested.directories
                bytes += nested.bytes
                // An empty directory left behind by deleted files is itself clutter.
                if (entry.listFiles()?.isEmpty() == true && modified < cutoff) {
                    if (runCatching { entry.delete() }.getOrDefault(false)) directories++
                }
            } else if (entry.lastModified() < cutoff) {
                val size = entry.length()
                if (runCatching { entry.delete() }.getOrDefault(false)) {
                    files++
                    bytes += size
                }
            }
        }
        return CacheCleanResult(files, directories, bytes)
    }

    /** The manual "clear cache now" action: everything stale regardless of the setting. */
    suspend fun clearAll(context: Context): CacheCleanResult = withContext(Dispatchers.IO) {
        sweep(context.cacheDir, Long.MAX_VALUE, keep = setOf("downloads"))
    }
}
