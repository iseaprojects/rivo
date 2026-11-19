package io.github.iseaprojects.rivo.data.extract.ytdlp

/**
 * Splits the user-editable `CONFIG_YT_DLP_OPTS` string into argv tokens.
 *
 * Upstream yt-x splices this value into a shell command, so users write things like
 * `--limit-rate 2M --cookies-from-browser firefox`. Android has no shell, so the app
 * parses the same text itself. Quoting and backslash escaping follow POSIX rules closely
 * enough for the flags people actually use, and nothing here ever reaches a shell.
 */
object CommandLine {

    fun split(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        var started = false

        for (char in raw) {
            when {
                escaped -> {
                    current.append(char)
                    escaped = false
                    started = true
                }
                char == '\\' && quote != '\'' -> escaped = true
                quote != null -> {
                    if (char == quote) {
                        quote = null
                    } else {
                        current.append(char)
                    }
                    started = true
                }
                char == '\'' || char == '"' -> {
                    quote = char
                    started = true
                }
                char.isWhitespace() -> {
                    if (started) {
                        tokens += current.toString()
                        current.clear()
                        started = false
                    }
                }
                else -> {
                    current.append(char)
                    started = true
                }
            }
        }

        if (escaped) current.append('\\')
        if (started) tokens += current.toString()
        return tokens
    }
}
