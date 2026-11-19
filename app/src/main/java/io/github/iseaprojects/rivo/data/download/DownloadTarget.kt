package io.github.iseaprojects.rivo.data.download

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where finished downloads land.
 *
 * Three writers, one interface, because Android has three answers to "a folder":
 *
 *  - API 29+: `MediaStore` with a relative path. No permission, the file is visible in
 *    Gallery/Files, and the default `Movies/Rivo` needs no picker at all.
 *  - API 26-28: a real directory under shared storage (`WRITE_EXTERNAL_STORAGE`).
 *  - A user-picked `content://` tree: `DocumentFile`, which is also the only way to write
 *    to an SD card or a cloud folder.
 *
 * `CONFIG_DOWNLOAD_DIR` selects between them by shape: a tree URI means SAF, anything
 * else is a path relative to shared storage (D42).
 */
interface DownloadTarget {

    /** Human description of the folder for the config row and error messages. */
    val description: String

    /** The folder itself, for "open folder" actions; null when it has no browsable URI. */
    fun folderUri(): Uri?

    /** Names already in [directory], relative to the download root. */
    suspend fun namesIn(directory: String): List<String>

    suspend fun write(
        directory: String,
        fileName: String,
        source: File,
        mimeType: String?,
    ): DownloadedFile?

    suspend fun list(): List<DownloadedFile>

    suspend fun delete(file: DownloadedFile): Boolean
}

object DownloadFolder {

    const val DEFAULT_ROOT = DownloadNaming.DEFAULT_FOLDER

    /** Resolves `CONFIG_DOWNLOAD_DIR` into a writer. */
    fun resolve(context: Context, configured: String): DownloadTarget {
        val value = configured.trim()
        return when {
            value.startsWith("content://") -> SafTarget(context, value.toUri())

            value.startsWith("file://") -> {
                val path = runCatching { value.toUri().path }.getOrNull()
                    ?: throw DownloadException("download folder '$value' is not a path", retryable = false)
                FileTarget(context, File(path))
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> MediaStoreTarget(context, normalise(value))

            else -> FileTarget(context, legacyDirectory(normalise(value)))
        }
    }

    /** `Movies/Rivo`, a `Download/…` path, or the configured blank default. */
    fun normalise(value: String): String {
        val cleaned = value.trim().trim('/')
        if (cleaned.isEmpty()) return DEFAULT_ROOT
        return if (cleaned.startsWith("/")) cleaned.trimStart('/') else cleaned
    }

    /**
     * A browsable URI for a folder under shared storage.
     *
     * MediaStore rows and plain files have no folder URI of their own, but the platform
     * storage provider does: `content://com.android.externalstorage.documents/document/primary%3AMovies%2Fyt-x`
     * opens in Files/Amaze/Material Files. Documents outside shared storage (a SAF tree,
     * an app-private directory) return null and the caller falls back to the path string.
     */
    fun sharedFolderUri(absolutePath: String): Uri? {
        val storage = Environment.getExternalStorageDirectory()?.absolutePath ?: return null
        if (!absolutePath.startsWith(storage)) return null
        val relative = absolutePath.removePrefix(storage).trim('/')
        if (relative.isEmpty()) return null
        return runCatching {
            DocumentsContract.buildDocumentUri(STORAGE_AUTHORITY, "primary:$relative")
        }.getOrNull()
    }

    /** Absolute folder for a storage-relative root such as `Movies/Rivo`. */
    fun absoluteOf(relative: String): String =
        File(Environment.getExternalStorageDirectory(), relative).absolutePath

    private const val STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    private fun legacyDirectory(relative: String): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), relative.removePrefix("Movies/").ifBlank { "yt-x" })
}

/**
 * MediaStore writer.
 *
 * On API 29+ the database, not a directory, is the destination, and every collection
 * enforces where its rows may live: videos under `Movies/`, `DCIM/` or `Pictures/`,
 * audio under `Music/`, generic files under `Download/`. One configured root therefore
 * maps to one root per collection — `Movies/Rivo` for video, `Music/Rivo` for audio — and
 * the same relative layout lives underneath both (D42).
 */
@RequiresApi(Build.VERSION_CODES.Q)
private class MediaStoreTarget(
    private val context: Context,
    private val root: String,
) : DownloadTarget {

    private val segments = root.split('/').filter { it.isNotBlank() }

    /** Configured root without its primary directory: `Movies/Rivo` -> `yt-x`. */
    private val tail = segments.drop(1)

    private val videoRoot = storeRoot(MEDIA_VIDEO, "Movies")
    private val audioRoot = storeRoot(MEDIA_AUDIO, "Music")
    private val downloadRoot = storeRoot(MEDIA_DOWNLOAD, "Download")

    override val description: String
        get() = if (audioRoot == root) root else "$root · $audioRoot"

    override fun folderUri(): Uri? =
        DownloadFolder.sharedFolderUri(DownloadFolder.absoluteOf(videoRoot))

    override suspend fun namesIn(directory: String): List<String> = withContext(Dispatchers.IO) {
        STORE_COLLECTIONS.flatMap { collection -> query(collection, storePath(collection, directory)) }
    }

    override suspend fun write(
        directory: String,
        fileName: String,
        source: File,
        mimeType: String?,
    ): DownloadedFile? = withContext(Dispatchers.IO) {
        val mime = mimeType ?: DownloadNaming.mimeFor(fileName) ?: "application/octet-stream"
        val preferred = collectionFor(mime)
        var rejected: Throwable? = null
        // The audio and video collections validate MIME types as well as paths (a WebM
        // audio track is not "audio" to the music library); the downloads collection
        // takes anything and keeps the file reachable in Files.
        for (collection in listOf(preferred, MEDIA_DOWNLOAD).distinct()) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, storePath(collection, directory))
                put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = runCatching { context.contentResolver.insert(collection, values) }
                .onFailure { rejected = it }
                .getOrNull() ?: continue
            try {
                val copied = context.contentResolver.openOutputStream(uri)?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output, 1 shl 16) }
                } ?: 0L
                if (copied <= 0) throw DownloadException("could not write $fileName")
                context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
                return@withContext DownloadedFile(
                    id = uri.toString(),
                    name = fileName,
                    relativePath = "$directory/$fileName".trim('/'),
                    sizeBytes = source.length(),
                    lastModified = System.currentTimeMillis(),
                    mimeType = mime,
                    uri = uri,
                    kind = if (mime.startsWith("audio/")) DownloadKind.AUDIO else DownloadKind.VIDEO,
                )
            } catch (error: Throwable) {
                // A pending row is invisible but still consumes a database slot; remove
                // it when the copy fails so a retry does not accumulate ghosts.
                runCatching { context.contentResolver.delete(uri, null, null) }
                throw error
            }
        }
        val reason = rejected?.message?.substringBefore('\n')
        throw DownloadException(
            if (reason == null) {
                "could not write $fileName"
            } else {
                "the media library refused $fileName: $reason"
            },
            retryable = false,
        )
    }

    override suspend fun list(): List<DownloadedFile> = withContext(Dispatchers.IO) {
        val columns = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )
        val result = mutableListOf<DownloadedFile>()
        STORE_COLLECTIONS.forEach { collection ->
            val inCollection = storeRootFor(collection)
            val cursor = runCatching {
                context.contentResolver.query(
                    collection,
                    columns,
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                    arrayOf("$inCollection/%"),
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
                )
            }.getOrNull() ?: return@forEach
            cursor.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    val name = it.getString(1) ?: continue
                    val mime = it.getString(4) ?: DownloadNaming.mimeFor(name)
                    val absolute = (it.getString(5) ?: "").trimEnd('/')
                    val relative = absolute.removePrefix("$inCollection/")
                    result += DownloadedFile(
                        id = ContentUris.withAppendedId(collection, id).toString(),
                        name = name,
                        relativePath = "$relative/$name".trim('/'),
                        sizeBytes = it.getLong(2),
                        lastModified = it.getLong(3) * 1000,
                        mimeType = mime,
                        uri = ContentUris.withAppendedId(collection, id),
                        kind = if (mime?.startsWith("audio/") == true) {
                            DownloadKind.AUDIO
                        } else {
                            DownloadKind.VIDEO
                        },
                    )
                }
            }
        }
        result.sortedByDescending { it.lastModified }
    }

    override suspend fun delete(file: DownloadedFile): Boolean = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.delete(file.uri, null, null) }.getOrDefault(0) > 0
    }

    private fun query(collection: Uri, path: String): List<String> {
        val cursor = runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(path),
                null,
            )
        }.getOrNull() ?: return emptyList()
        return cursor.use {
            buildList {
                while (it.moveToNext()) it.getString(0)?.let(::add)
            }
        }
    }

    /** `Movies/Rivo` for video, `Music/Rivo` for audio: the configured tail is shared. */
    private fun storeRoot(collection: Uri, fallback: String): String {
        val allowed = ALLOWED_PRIMARIES[collection].orEmpty()
        val primary = segments.firstOrNull()?.takeIf { it in allowed } ?: fallback
        return (listOf(primary) + tail).joinToString("/").ifBlank { primary }
    }

    private fun storeRootFor(collection: Uri): String = when (collection) {
        MEDIA_AUDIO -> audioRoot
        MEDIA_DOWNLOAD -> downloadRoot
        else -> videoRoot
    }

    private fun storePath(collection: Uri, directory: String): String {
        val leaf = directory.trim('/')
        val base = storeRootFor(collection)
        return if (leaf.isEmpty()) "$base/" else "$base/$leaf/"
    }

    private fun collectionFor(mime: String): Uri = when {
        mime.startsWith("audio/") -> MEDIA_AUDIO
        mime.startsWith("video/") -> MEDIA_VIDEO
        else -> MEDIA_DOWNLOAD
    }

    companion object {
        private val MEDIA_VIDEO = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        private val MEDIA_AUDIO = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        private val MEDIA_DOWNLOAD = MediaStore.Downloads.EXTERNAL_CONTENT_URI

        private val STORE_COLLECTIONS = listOf(MEDIA_VIDEO, MEDIA_AUDIO, MEDIA_DOWNLOAD)

        /** What MediaProvider accepts as the first segment of RELATIVE_PATH per collection. */
        private val ALLOWED_PRIMARIES: Map<Uri, List<String>> = mapOf(
            MEDIA_VIDEO to listOf("Movies", "DCIM", "Pictures"),
            MEDIA_AUDIO to listOf(
                "Alarms",
                "Audiobooks",
                "Music",
                "Notifications",
                "Podcasts",
                "Recordings",
                "Ringtones",
            ),
            MEDIA_DOWNLOAD to listOf("Download"),
        )
    }
}

private class SafTarget(
    private val context: Context,
    private val treeUri: Uri,
) : DownloadTarget {

    override val description: String
        get() = treeUri.lastPathSegment
            ?.substringAfter(':')
            ?.takeIf { it.isNotBlank() }
            ?: treeUri.toString()

    override fun folderUri(): Uri = treeUri

    private val root: DocumentFile? by lazy {
        runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull()
    }

    override suspend fun namesIn(directory: String): List<String> = withContext(Dispatchers.IO) {
        val dir = resolveDirectory(directory, create = false) ?: return@withContext emptyList()
        dir.listFiles().map { it.name.orEmpty() }.filter { it.isNotEmpty() }
    }

    override suspend fun write(
        directory: String,
        fileName: String,
        source: File,
        mimeType: String?,
    ): DownloadedFile? = withContext(Dispatchers.IO) {
        val dir = resolveDirectory(directory, create = true) ?: return@withContext null
        val existing = dir.findFile(fileName)
        existing?.delete()
        val mime = mimeType ?: DownloadNaming.mimeFor(fileName) ?: "application/octet-stream"
        val document = dir.createFile(mime, fileName) ?: return@withContext null
        val wrote = runCatching {
            context.contentResolver.openOutputStream(document.uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output, 1 shl 16) }
            } != null
        }.getOrDefault(false)
        if (!wrote) {
            runCatching { document.delete() }
            return@withContext null
        }
        DownloadedFile(
            id = document.uri.toString(),
            name = fileName,
            relativePath = "$directory/$fileName".trim('/'),
            sizeBytes = source.length(),
            lastModified = System.currentTimeMillis(),
            mimeType = mime,
            uri = document.uri,
            kind = if (mime.startsWith("audio/")) DownloadKind.AUDIO else DownloadKind.VIDEO,
        )
    }

    override suspend fun list(): List<DownloadedFile> = withContext(Dispatchers.IO) {
        val base = root ?: return@withContext emptyList()
        buildList {
            walk(base, "", this)
        }.sortedByDescending { it.lastModified }
    }

    override suspend fun delete(file: DownloadedFile): Boolean = withContext(Dispatchers.IO) {
        val document = DocumentFile.fromSingleUri(context, file.uri)
        runCatching { document?.delete() == true }.getOrDefault(false)
    }

    private fun walk(dir: DocumentFile, path: String, out: MutableList<DownloadedFile>) {
        dir.listFiles().forEach { child ->
            val name = child.name.orEmpty()
            if (name.isBlank()) return@forEach
            val relative = if (path.isEmpty()) name else "$path/$name"
            if (child.isDirectory) {
                walk(child, relative, out)
            } else {
                val mime = child.type ?: DownloadNaming.mimeFor(name)
                out += DownloadedFile(
                    id = child.uri.toString(),
                    name = name,
                    relativePath = relative,
                    sizeBytes = child.length(),
                    lastModified = child.lastModified(),
                    mimeType = mime,
                    uri = child.uri,
                    kind = if (mime?.startsWith("audio/") == true) DownloadKind.AUDIO else DownloadKind.VIDEO,
                )
            }
        }
    }

    private fun resolveDirectory(directory: String, create: Boolean): DocumentFile? {
        var current = root ?: return null
        val segments = directory.split('/').filter { it.isNotBlank() }
        segments.forEach { segment ->
            val next = current.findFile(segment)
            current = when {
                next?.isDirectory == true -> next
                create -> current.createDirectory(segment) ?: return null
                else -> return null
            }
        }
        return current
    }
}

private class FileTarget(
    private val context: Context,
    private val base: File,
) : DownloadTarget {

    override val description: String get() = base.absolutePath

    // A file manager wants the storage provider's document URI, not a FileProvider grant
    // for a directory (`ACTION_VIEW` on a FileProvider dir uri has no handler).
    override fun folderUri(): Uri? = DownloadFolder.sharedFolderUri(base.absolutePath)

    override suspend fun namesIn(directory: String): List<String> = withContext(Dispatchers.IO) {
        File(base, directory).listFiles()?.map { it.name } ?: emptyList()
    }

    override suspend fun write(
        directory: String,
        fileName: String,
        source: File,
        mimeType: String?,
    ): DownloadedFile? = withContext(Dispatchers.IO) {
        val dir = File(base, directory)
        if (!dir.exists() && !dir.mkdirs()) return@withContext null
        val target = File(dir, fileName)
        runCatching {
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
            }
        }.getOrDefault(false)
        if (!target.exists() || target.length() == 0L) {
            target.delete()
            return@withContext null
        }
        scan(target)
        val mime = mimeType ?: DownloadNaming.mimeFor(fileName)
        DownloadedFile(
            id = target.absolutePath,
            name = fileName,
            relativePath = "$directory/$fileName".trim('/'),
            sizeBytes = target.length(),
            lastModified = target.lastModified(),
            mimeType = mime,
            uri = uriFor(target),
            kind = if (mime?.startsWith("audio/") == true) DownloadKind.AUDIO else DownloadKind.VIDEO,
        )
    }

    override suspend fun list(): List<DownloadedFile> = withContext(Dispatchers.IO) {
        buildList {
            base.walkTopDown().filter { it.isFile }.forEach { file ->
                val mime = DownloadNaming.mimeFor(file.name)
                add(
                    DownloadedFile(
                        id = file.absolutePath,
                        name = file.name,
                        relativePath = file.relativeTo(base).invariantSeparatorsPath,
                        sizeBytes = file.length(),
                        lastModified = file.lastModified(),
                        mimeType = mime,
                        uri = uriFor(file),
                        kind = DownloadNaming.kindFor(file.name),
                    ),
                )
            }
        }.sortedByDescending { it.lastModified }
    }

    override suspend fun delete(file: DownloadedFile): Boolean = withContext(Dispatchers.IO) {
        val target = File(file.id)
        runCatching { target.delete() }.getOrDefault(false)
    }

    private fun uriFor(file: File): Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.files",
        file,
    )

    private fun scan(file: File) {
        runCatching {
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                null,
                null,
            )
        }
    }
}

