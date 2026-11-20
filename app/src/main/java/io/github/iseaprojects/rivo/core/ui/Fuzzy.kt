package io.github.iseaprojects.rivo.core.ui

import java.text.Normalizer
import java.util.Locale

/**
 * fzf-style matching for the always-focused filter.
 *
 * A query is split on whitespace and every token must match. A substring match scores
 * higher than a subsequence match, and a match at a word boundary or at the start of
 * the text scores higher again, so typing `git` puts "gitignore explained" above a
 * passing mention of "digits". Ties keep the original order, which matters for feeds
 * where the extractor already ordered by relevance.
 *
 * Both sides are folded first: case and diacritics. On a phone keyboard accents cost a
 * long-press, so "cancion" has to find "Canción" and "videoaula" has to find "Videoaula".
 * Folding is done with NFD plus removal of the combining marks, which keeps the base
 * letter; the folded string is therefore shorter than the original, and every index
 * below refers to the folded text.
 */
object Fuzzy {

    fun matches(query: String, text: String): Boolean = score(query, text) != null

    /** Null when the query does not match; higher scores sort first. */
    fun score(query: String, text: String): Int? {
        val needle = fold(query.trim())
        if (needle.isEmpty()) return 0
        val haystack = fold(text)
        var total = 0

        for (token in needle.split(WHITESPACE)) {
            if (token.isEmpty()) continue
            val substring = haystack.indexOf(token)
            total += when {
                substring < 0 -> {
                    val sub = subsequenceScore(token, haystack) ?: return null
                    sub
                }
                substring == 0 -> 1200 - token.length
                substring > 0 && isBoundary(haystack[substring - 1]) -> 900 - substring
                else -> 500 - substring
            }
        }
        return total
    }

    fun <T> filter(items: List<T>, query: String, text: (T) -> String): List<T> {
        if (query.isBlank()) return items
        return items
            .mapNotNull { item -> score(query, text(item))?.let { item to it } }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /** In-order subsequence ("lfg" matches "lofi girl"); contiguous runs score better. */
    private fun subsequenceScore(token: String, text: String): Int? {
        var index = 0
        var gaps = 0
        var last = -1
        for (char in token) {
            val found = text.indexOf(char, index)
            if (found < 0) return null
            if (last >= 0) gaps += found - last - 1
            last = found
            index = found + 1
        }
        return 100 - gaps
    }

    private fun isBoundary(char: Char): Boolean =
        char.isWhitespace() || char == '-' || char == '_' || char == '/' || char == '.' || char == ':'

    /**
     * Case folding for the user's own script, not the JVM default locale: on a Turkish
     * device `I` lowercases to a dotless `ı` and every match would silently break.
     */
    internal fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)

    private val WHITESPACE = Regex("\\s+")
    private val COMBINING_MARKS = Regex("\\p{Mn}+")
}
