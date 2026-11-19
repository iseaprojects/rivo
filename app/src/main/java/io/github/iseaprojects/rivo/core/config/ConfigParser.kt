package io.github.iseaprojects.rivo.core.config

/**
 * Line-preserving reader for the plain-text `config` file.
 *
 * Upstream yt-x sources this file as a shell script; here it is parsed, which is safer
 * but must stay compatible: comments, `export KEY=...`, quoted values and unknown keys
 * all survive a round-trip. [ConfigDocument.set] rewrites only the affected line, so
 * hand-written comments and ordering are never destroyed by the GUI settings screen.
 */
object ConfigParser {

    private val KEY_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun parse(text: String): LinkedHashMap<String, String> {
        val values = LinkedHashMap<String, String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val body = if (line.startsWith("export ")) line.removePrefix("export ").trim() else line
            val separator = body.indexOf('=')
            if (separator <= 0) return@forEach
            val key = body.substring(0, separator).trim()
            if (!KEY_PATTERN.matches(key)) return@forEach
            values[key] = unquote(body.substring(separator + 1).trim())
        }
        return values
    }

    fun unquote(raw: String): String {
        if (raw.length >= 2) {
            val first = raw.first()
            val last = raw.last()
            if (first == '"' && last == '"') {
                return raw.substring(1, raw.length - 1)
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
            }
            if (first == '\'' && last == '\'') {
                return raw.substring(1, raw.length - 1)
            }
        }
        return raw
    }

    fun renderValue(value: String): String {
        val needsQuotes = value.isEmpty() ||
            value.any { it.isWhitespace() || it == '"' || it == '\'' || it == '#' }
        if (!needsQuotes) return value
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    fun keyOf(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
        val body = if (trimmed.startsWith("export ")) trimmed.removePrefix("export ").trim() else trimmed
        val separator = body.indexOf('=')
        if (separator <= 0) return null
        val key = body.substring(0, separator).trim()
        return key.takeIf { KEY_PATTERN.matches(it) }
    }
}

/**
 * Mutable, comment-preserving view over the config text.
 */
class ConfigDocument private constructor(private val lines: MutableList<String>) {

    fun get(key: String): String? {
        val index = lastIndexOfKey(key) ?: return null
        val line = lines[index]
        val separator = line.indexOf('=')
        if (separator < 0) return null
        return ConfigParser.unquote(line.substring(separator + 1).trim())
    }

    fun toMap(): LinkedHashMap<String, String> = ConfigParser.parse(render())

    fun set(key: String, value: String): ConfigDocument {
        val rendered = "$key=${ConfigParser.renderValue(value)}"
        val index = lastIndexOfKey(key)
        if (index == null) {
            append(key, value)
        } else {
            val previous = lines[index]
            lines[index] = if (previous.trimStart().startsWith("export ")) "export $rendered" else rendered
        }
        return this
    }

    fun remove(key: String): ConfigDocument {
        val index = lastIndexOfKey(key) ?: return this
        lines.removeAt(index)
        return this
    }

    fun append(key: String, value: String): ConfigDocument {
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
        lines += "$key=${ConfigParser.renderValue(value)}"
        lines += ""
        return this
    }

    fun appendComment(comment: String): ConfigDocument {
        lines += comment
        return this
    }

    fun render(): String {
        val body = lines.joinToString("\n").trimEnd('\n')
        return if (body.isEmpty()) "" else "$body\n"
    }

    private fun lastIndexOfKey(key: String): Int? {
        for (index in lines.indices.reversed()) {
            if (ConfigParser.keyOf(lines[index]) == key) return index
        }
        return null
    }

    companion object {
        fun parse(text: String): ConfigDocument {
            val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
            return ConfigDocument(normalized.split('\n').toMutableList())
        }

        fun empty(): ConfigDocument = ConfigDocument(mutableListOf())
    }
}
