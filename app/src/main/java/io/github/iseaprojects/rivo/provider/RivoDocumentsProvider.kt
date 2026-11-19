package io.github.iseaprojects.rivo.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.util.Base64
import io.github.iseaprojects.rivo.R
import java.io.File
import java.io.FileNotFoundException

/**
 * Exposes the app's `filesDir` over Storage Access Framework.
 *
 * This is what makes "config is a text file, not only a settings screen" true: any
 * file manager, desktop MTP browser or `adb` file pull can read and edit `config`
 * while the GUI writes the same bytes. Cookies live in `noBackupFilesDir` and are
 * therefore structurally out of reach of this provider.
 *
 * MANAGE_DOCUMENTS keeps the provider usable only by the system DocumentsUI (and
 * apps the user explicitly grants through it).
 */
class RivoDocumentsProvider : DocumentsProvider() {

    private val root: File
        get() = requireNotNull(context).filesDir.canonicalFile

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(DEFAULT_ROOT_PROJECTION)
        val row = cursor.newRow()
        row.add(Root.COLUMN_ROOT_ID, ROOT_ID)
        row.add(Root.COLUMN_DOCUMENT_ID, encode(relativeOf(root)))
        row.add(Root.COLUMN_TITLE, requireNotNull(context).getString(R.string.provider_root))
        row.add(
            Root.COLUMN_FLAGS,
            Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD,
        )
        row.add(Root.COLUMN_MIME_TYPES, "*/*")
        row.add(Root.COLUMN_AVAILABLE_BYTES, root.usableSpace)
        row.add(Root.COLUMN_ICON, R.drawable.ic_launcher_foreground)
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val file = resolve(documentId) ?: throw FileNotFoundException(documentId)
        val cursor = MatrixCursor(DEFAULT_DOCUMENT_PROJECTION)
        includeFile(cursor, file)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val parent = resolve(parentDocumentId)
            ?.takeIf { it.isDirectory }
            ?: throw FileNotFoundException(parentDocumentId)
        val cursor = MatrixCursor(DEFAULT_DOCUMENT_PROJECTION)
        parent.listFiles()
            ?.asSequence()
            ?.filter(::isVisible)
            ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            ?.forEach { child -> includeFile(cursor, child) }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val file = resolve(documentId) ?: throw FileNotFoundException(documentId)
        if (file.isDirectory) throw FileNotFoundException("$documentId is a directory")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val parent = resolve(parentDocumentId)
            ?.takeIf { it.isDirectory }
            ?: throw FileNotFoundException(parentDocumentId)
        val file = File(parent, sanitize(displayName))
        if (file.exists()) throw FileNotFoundException(file.absolutePath)
        val created = if (mimeType == Document.MIME_TYPE_DIR) file.mkdir() else file.createNewFile()
        if (!created) throw IllegalStateException("Could not create ${file.absolutePath}")
        return encode(relativeOf(file))
    }

    override fun deleteDocument(documentId: String) {
        val file = resolve(documentId) ?: throw FileNotFoundException(documentId)
        if (file == root) throw FileNotFoundException("root cannot be deleted")
        if (!file.delete()) throw FileNotFoundException("could not delete $documentId")
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        val file = resolve(documentId) ?: throw FileNotFoundException(documentId)
        if (file == root) throw FileNotFoundException("root cannot be renamed")
        val parent = file.parentFile ?: throw FileNotFoundException(documentId)
        val target = File(parent, sanitize(displayName))
        if (!file.renameTo(target)) throw FileNotFoundException("could not rename $documentId")
        return encode(relativeOf(target))
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = resolve(parentDocumentId) ?: return false
        val child = resolve(documentId) ?: return false
        if (child == parent) return false
        return child.path.startsWith(parent.path + File.separator)
    }

    override fun getDocumentType(documentId: String): String {
        val file = resolve(documentId) ?: throw FileNotFoundException(documentId)
        return mimeTypeOf(file)
    }

    private fun includeFile(cursor: MatrixCursor, file: File) {
        val row = cursor.newRow()
        row.add(Document.COLUMN_DOCUMENT_ID, encode(relativeOf(file)))
        row.add(Document.COLUMN_DISPLAY_NAME, if (file == root) ROOT_TITLE else file.name)
        row.add(Document.COLUMN_MIME_TYPE, mimeTypeOf(file))
        val size: Long? = if (file.isDirectory) null else file.length()
        row.add(Document.COLUMN_SIZE, size as Any?)
        row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
        row.add(Document.COLUMN_FLAGS, flagsOf(file))
        val summary: String? = when {
            file.isDirectory -> null
            file.length() < 1024 -> "${file.length()} B"
            file.length() < 1024 * 1024 -> "${file.length() / 1024} KB"
            else -> "${file.length() / (1024 * 1024)} MB"
        }
        row.add(Document.COLUMN_SUMMARY, summary as Any?)
    }

    private fun flagsOf(file: File): Int {
        if (file == root) return Document.FLAG_DIR_SUPPORTS_CREATE
        var flags = 0
        if (file.isDirectory) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        if (file.canWrite()) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
            flags = flags or Document.FLAG_SUPPORTS_DELETE
            flags = flags or Document.FLAG_SUPPORTS_RENAME
        }
        return flags
    }

    private fun mimeTypeOf(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        if (file.name == "config") return "text/plain"
        val extension = file.extension.lowercase()
        return android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    private fun isVisible(file: File): Boolean {
        val name = file.name
        if (name.startsWith(".")) return false
        return name != "cookies" && name != "no_backup"
    }

    private fun relativeOf(file: File): String {
        val canonicalRoot = root
        if (file.path == canonicalRoot.path) return ""
        return file.path
            .removePrefix(canonicalRoot.path)
            .removePrefix(File.separator)
            .replace(File.separatorChar, '/')
    }

    private fun resolve(documentId: String): File? {
        val relative = decode(documentId)
        val candidate = File(root, relative).canonicalFile
        val canonicalRoot = root
        if (candidate.path == canonicalRoot.path) return candidate
        if (!candidate.path.startsWith(canonicalRoot.path + File.separator)) return null
        return candidate
    }

    private fun encode(relativePath: String): String {
        if (relativePath.isEmpty()) return ROOT_DOCUMENT_ID
        return Base64.encodeToString(
            relativePath.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP,
        )
    }

    private fun decode(documentId: String): String {
        if (documentId == ROOT_DOCUMENT_ID) return ""
        return try {
            String(
                Base64.decode(documentId, Base64.URL_SAFE or Base64.NO_WRAP),
                Charsets.UTF_8,
            )
        } catch (_: IllegalArgumentException) {
            ""
        }
    }

    private fun sanitize(displayName: String): String {
        val cleaned = displayName
            .replace('/', '_')
            .replace('\u0000', '_')
            .trim()
        return cleaned.ifBlank { "untitled" }
    }

    private companion object {
        const val ROOT_ID = "rivo-files"
        const val ROOT_TITLE = "yt-x"
        const val ROOT_DOCUMENT_ID = "root"

        val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_AVAILABLE_BYTES,
            Root.COLUMN_ICON,
        )

        val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SUMMARY,
        )
    }
}
