package io.github.iseaprojects.rivo.data.download

/**
 * Where a downloaded file goes and what it is called.
 *
 * The layout is yt-x's:
 *
 * ```
 * video/individual/<Channel>/<Title>.<ext>
 * video/<Playlist>/<Channel>/<Title>.<ext>
 * audio/<Playlist>/<Channel>/<Title>.<ext>
 * ```
 *
 * with `CONFIG_DOWNLOADS_ENUMERATE` prefixing `01 - `, `02 - ` inside one folder. Names
 * are sanitised for every target at once: SAF and MediaStore reject `/`, NUL and control
 * characters, and a Windows-visible folder (through a share) rejects the rest.
 */
object DownloadNaming {

    const val DEFAULT_FOLDER = "Movies/Rivo"

    /** Folder part of the layout, relative to the download root. */
    fun directory(task: DownloadTask): String {
        val bucket = if (task.kind == DownloadKind.AUDIO) "audio" else "video"
        val playlist = task.playlist?.let(::sanitize)
        val channel = task.channel?.let(::sanitize)?.takeIf { it.isNotBlank() } ?: UNKNOWN_CHANNEL
        return if (playlist.isNullOrBlank()) {
            "$bucket/individual/$channel"
        } else {
            "$bucket/$playlist/$channel"
        }
    }

    /**
     * File name for [task]. [enumerate] is the 1-based position inside the folder, or null
     * when enumeration is off. [suffix] is added before the extension when a file with
     * that name already exists (normally ` [<video id>]`).
     */
    fun fileName(
        task: DownloadTask,
        extension: String,
        enumerate: Int? = null,
        suffix: String = "",
    ): String {
        val prefix = if (enumerate != null) "%02d - ".format(enumerate) else ""
        val base = sanitize(task.title).ifBlank { task.videoId }
        return "$prefix$base$suffix.${extension.trimStart('.').lowercase()}"
    }

    /**
     * A name not already in [existing]: the plain one, then ` [<video id>]`, then
     * ` (2)`, ` (3)` and so on. The loop matters: a second download of the same video
     * with the archive off used to land on the same `[id]` name and overwrite the file
     * that was already there.
     */
    fun uniqueName(
        task: DownloadTask,
        extension: String,
        enumerate: Int?,
        existing: Set<String>,
    ): String {
        val plain = fileName(task, extension, enumerate)
        if (plain !in existing) return plain
        val tagged = fileName(task, extension, enumerate, suffix = " [${task.videoId}]")
        if (tagged !in existing) return tagged
        val stem = tagged.substringBeforeLast('.')
        val suffix = tagged.substringAfterLast('.')
        var copy = 2
        while (true) {
            val candidate = "$stem ($copy).$suffix"
            if (candidate !in existing) return candidate
            copy++
        }
    }

    /** Extension for a stream, from its MIME type then its container. */
    fun extensionFor(mimeType: String?, container: String?, fallback: String = "mp4"): String =
        when {
            mimeType != null && mimeType in MIME_EXTENSIONS -> MIME_EXTENSIONS.getValue(mimeType)
            !container.isNullOrBlank() -> sanitizeContainer(container)
            else -> fallback
        }

    fun mimeFor(fileName: String): String? = when (fileName.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "opus", "ogg", "oga" -> "audio/ogg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        else -> null
    }

    fun kindFor(fileName: String): DownloadKind {
        val mime = mimeFor(fileName)
        return if (mime?.startsWith("audio/") == true) DownloadKind.AUDIO else DownloadKind.VIDEO
    }

    /**
     * A name every filesystem accepts.
     *
     * Reserved names (`.`/`..`) and trailing dots are the two that actually break a
     * `DocumentFile.createFile`. The cap is in UTF-8 bytes, not characters: Android and
     * Linux allow 255 bytes per name, and a 100-character title in Japanese is already
     * 300 bytes, which is exactly the case a character cap misses.
     */
    fun sanitize(raw: String): String {
        val cleaned = buildString(raw.length) {
            raw.forEach { char ->
                when {
                    char.code < 0x20 || char.code == 0x7f -> append(' ')
                    char in FORBIDDEN -> append('_')
                    else -> append(char)
                }
            }
        }
        val collapsed = cleaned.replace(Regex("\\s+"), " ").trim(' ', '.')
        val capped = capToBytes(collapsed).trim(' ', '.')
        return capped.takeUnless { it.isBlank() || it == "." || it == ".." } ?: ""
    }

    /** Longest prefix of [text] that fits in [MAX_NAME_BYTES] without cutting a code point. */
    private fun capToBytes(text: String): String {
        var used = 0
        var end = 0
        while (end < text.length) {
            val char = text[end]
            val pair = char.isHighSurrogate() && end + 1 < text.length
            val size = when {
                pair -> 4
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                else -> 3
            }
            if (used + size > MAX_NAME_BYTES) break
            used += size
            end += if (pair) 2 else 1
        }
        return text.substring(0, end)
    }

    /** Relative folder for the queue row and the snackbar ("video/individual/Channel"). */
    fun describe(task: DownloadTask): String = directory(task)

    private fun sanitizeContainer(container: String): String {
        val value = container.substringBefore(';').trim().lowercase().removePrefix(".")
        return when (value) {
            "m4a", "mp4", "m4v", "webm", "mkv", "mov", "mp3", "opus", "ogg", "flac", "wav" -> value
            else -> "mp4"
        }
    }

    private const val UNKNOWN_CHANNEL = "Unknown channel"

    /**
     * 180 bytes leaves room for `01 - `, a ` [<video id>]` suffix and `.mp4` inside the
     * 255-byte limit every Android filesystem enforces.
     */
    private const val MAX_NAME_BYTES = 180

    private val FORBIDDEN = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|').toSet()

    private val MIME_EXTENSIONS = mapOf(
        "video/mp4" to "mp4",
        "video/webm" to "webm",
        "video/x-matroska" to "mkv",
        "video/quicktime" to "mov",
        "audio/mp4" to "m4a",
        "audio/mpeg" to "mp3",
        "audio/ogg" to "opus",
        "audio/webm" to "weba",
        "audio/flac" to "flac",
        "audio/wav" to "wav",
    )
}
