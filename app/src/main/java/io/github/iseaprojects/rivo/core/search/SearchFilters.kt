package io.github.iseaprojects.rivo.core.search

import io.github.iseaprojects.rivo.core.model.SearchKind

enum class SearchFilterGroup {
    DATE,
    TYPE,
    FEATURE,
    SORT,
}

/**
 * One YouTube search filter. [sp] is the value yt-x also sends verbatim: the
 * double-encoded protobuf that the results URL carries (`%253D` for `=`), because both
 * NewPipeExtractor and yt-dlp parse the URL/argument in that form.
 */
data class SearchFilter(
    val token: String,
    val group: SearchFilterGroup,
    val labelKey: String,
    val sp: String,
)

/** A raw prompt split into the query text and at most one active filter. */
data class ParsedSearch(
    val query: String,
    val filter: SearchFilter?,
)

/**
 * The `sp` filter vocabulary of yt-x 0.8.7.
 *
 * YouTube's `sp` is one protobuf message, so filters cannot be combined without
 * encoding a new message; upstream therefore accepts exactly one token at a time and
 * so does the port. Selecting a chip replaces the previous filter, matching the
 * `:token` grammar.
 */
object SearchFilters {

    val DATE: List<SearchFilter> = listOf(
        filter("hour", SearchFilterGroup.DATE, "TXT_ANDROID_FILTER_HOUR", "EgIIAQ%253D%253D"),
        filter("today", SearchFilterGroup.DATE, "TXT_ANDROID_FILTER_TODAY", "EgIIAg%253D%253D"),
        filter("week", SearchFilterGroup.DATE, "TXT_ANDROID_FILTER_WEEK", "EgIIAw%253D%253D"),
        filter("month", SearchFilterGroup.DATE, "TXT_ANDROID_FILTER_MONTH", "EgIIBA%253D%253D"),
        filter("year", SearchFilterGroup.DATE, "TXT_ANDROID_FILTER_YEAR", "EgIIBQ%253D%253D"),
    )

    val TYPE: List<SearchFilter> = listOf(
        filter("video", SearchFilterGroup.TYPE, "TXT_ANDROID_FILTER_VIDEO", "EgIQAQ%253D%253D"),
        filter("movie", SearchFilterGroup.TYPE, "TXT_ANDROID_FILTER_MOVIE", "EgIQBA%253D%253D"),
        filter("live", SearchFilterGroup.TYPE, "TXT_ANDROID_FILTER_LIVE", "EgJAAQ%253D%253D"),
        filter("short", SearchFilterGroup.TYPE, "TXT_ANDROID_FILTER_SHORT", "EgQQARgB"),
        filter("long", SearchFilterGroup.TYPE, "TXT_ANDROID_FILTER_LONG", "EgQQARgC"),
    )

    val FEATURE: List<SearchFilter> = listOf(
        filter("4k", SearchFilterGroup.FEATURE, "TXT_ANDROID_FILTER_4K", "EgJwAQ%253D%253D"),
        filter("hd", SearchFilterGroup.FEATURE, "TXT_ANDROID_FILTER_HD", "EgIgAQ%253D%253D"),
        filter("hdr", SearchFilterGroup.FEATURE, "TXT_ANDROID_FILTER_HDR", "EgPIAQ%253D%253D"),
        filter("subtitles", SearchFilterGroup.FEATURE, "TXT_ANDROID_FILTER_SUBTITLES", "EgIoAQ%253D%253D"),
        filter("360", SearchFilterGroup.FEATURE, "TXT_ANDROID_FILTER_360", "EgJ4AQ%253D%253D"),
        filter("vr", SearchFilterGroup.FEATURE, "TXT_ANDROID_FILTER_VR", "EgLIAQ%253D%253D"),
        filter("3d", SearchFilterGroup.FEATURE, "TXT_ANDROID_FILTER_3D", "EgI4AQ%253D%253D"),
        filter("local", SearchFilterGroup.FEATURE, "TXT_ANDROID_FILTER_LOCAL", "EgO4AQ%253D%253D"),
    )

    val SORT: List<SearchFilter> = listOf(
        filter("newest", SearchFilterGroup.SORT, "TXT_ANDROID_FILTER_NEWEST", "CAISAhAB"),
        filter("views", SearchFilterGroup.SORT, "TXT_ANDROID_FILTER_VIEWS", "CAMSAhAB"),
        filter("rating", SearchFilterGroup.SORT, "TXT_ANDROID_FILTER_RATING", "CAESAhAB"),
    )

    val all: List<SearchFilter> = DATE + TYPE + FEATURE + SORT

    private val byToken: Map<String, SearchFilter> = all.associateBy { it.token }

    fun byToken(token: String?): SearchFilter? =
        token?.trim()?.trimStart(':')?.lowercase()?.let(byToken::get)

    fun bySp(sp: String?): SearchFilter? = all.firstOrNull { it.sp == sp }

    /** Default `sp` for a search scope, before any chip is applied. */
    fun kindSp(kind: SearchKind): String = when (kind) {
        SearchKind.VIDEOS -> "EgIQAQ%253D%253D"
        SearchKind.PLAYLISTS -> "EgIQAw%253D%253D"
        SearchKind.CHANNELS -> "EgIQAg%253D%253D"
        SearchKind.SHORTS -> "EgIQCQ%253D%253D"
        SearchKind.MOVIES -> "EgIQBA%253D%253D"
    }

    /**
     * Splits a prompt using upstream's grammar: a leading `:token` or a trailing
     * `:token`, never both. Only one filter can be active, so a contradictory prompt
     * degrades to plain text.
     */
    fun parse(raw: String): ParsedSearch {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ParsedSearch("", null)

        val parts = trimmed.split(WHITESPACE)
        val first = parts.firstOrNull()
        if (first != null && first.length > 1 && first[0] == ':') {
            byToken(first.substring(1))?.let { filter ->
                return ParsedSearch(parts.drop(1).joinToString(" "), filter)
            }
        }

        val last = parts.lastOrNull()
        if (parts.size > 1 && last != null && last.length > 1 && last[0] == ':') {
            byToken(last.substring(1))?.let { filter ->
                return ParsedSearch(parts.dropLast(1).joinToString(" "), filter)
            }
        }

        return ParsedSearch(trimmed, null)
    }

    private val WHITESPACE = Regex("\\s+")

    private fun filter(token: String, group: SearchFilterGroup, labelKey: String, sp: String) =
        SearchFilter(token, group, labelKey, sp)
}
