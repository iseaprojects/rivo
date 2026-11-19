package io.github.iseaprojects.rivo.core.search

import io.github.iseaprojects.rivo.core.model.SearchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchFiltersTest {

    @Test
    fun `upstream sp codes are preserved verbatim`() {
        assertEquals("EgIIAQ%253D%253D", SearchFilters.byToken("hour")?.sp)
        assertEquals("EgIIBQ%253D%253D", SearchFilters.byToken("year")?.sp)
        assertEquals("EgQQARgB", SearchFilters.byToken("short")?.sp)
        assertEquals("EgQQARgC", SearchFilters.byToken("long")?.sp)
        assertEquals("CAISAhAB", SearchFilters.byToken("newest")?.sp)
        assertEquals("CAESAhAB", SearchFilters.byToken("rating")?.sp)
    }

    @Test
    fun `every upstream token exists exactly once`() {
        val tokens = SearchFilters.all.map { it.token }
        assertEquals(21, tokens.size)
        assertEquals(tokens.size, tokens.distinct().size)
        listOf(
            "hour", "today", "week", "month", "year",
            "video", "movie", "live", "short", "long",
            "4k", "hd", "subtitles", "360", "vr", "3d", "hdr", "local",
            "newest", "views", "rating",
        ).forEach { assertNotNull("missing $it", SearchFilters.byToken(it)) }
    }

    @Test
    fun `each token belongs to one group`() {
        SearchFilterGroup.entries.forEach { group ->
            assertTrue("empty group $group", SearchFilters.all.any { it.group == group })
        }
        assertEquals(
            listOf("hour", "today", "week", "month", "year"),
            SearchFilters.DATE.map { it.token },
        )
        assertEquals(
            listOf("newest", "views", "rating"),
            SearchFilters.SORT.map { it.token },
        )
    }

    @Test
    fun `trailing token becomes the active filter`() {
        val parsed = SearchFilters.parse("lofi girl :4k")
        assertEquals("lofi girl", parsed.query)
        assertEquals("4k", parsed.filter?.token)
    }

    @Test
    fun `leading token becomes the active filter`() {
        val parsed = SearchFilters.parse(":today lofi girl")
        assertEquals("lofi girl", parsed.query)
        assertEquals("today", parsed.filter?.token)
    }

    @Test
    fun `plain text has no filter`() {
        val parsed = SearchFilters.parse("  lofi girl  ")
        assertEquals("lofi girl", parsed.query)
        assertNull(parsed.filter)
    }

    @Test
    fun `unknown tokens stay in the query`() {
        val parsed = SearchFilters.parse("cats :teleport")
        assertEquals("cats :teleport", parsed.query)
        assertNull(parsed.filter)
    }

    @Test
    fun `byToken tolerates colons and case`() {
        assertEquals("4k", SearchFilters.byToken(":4K")?.token)
        assertEquals("hd", SearchFilters.byToken(" HD ")?.token)
        assertNull(SearchFilters.byToken(null))
    }

    @Test
    fun `kind defaults match the upstream scoped searches`() {
        assertEquals("EgIQAQ%253D%253D", SearchFilters.kindSp(SearchKind.VIDEOS))
        assertEquals("EgIQAg%253D%253D", SearchFilters.kindSp(SearchKind.CHANNELS))
        assertEquals("EgIQAw%253D%253D", SearchFilters.kindSp(SearchKind.PLAYLISTS))
        assertEquals("EgIQCQ%253D%253D", SearchFilters.kindSp(SearchKind.SHORTS))
        assertEquals("EgIQBA%253D%253D", SearchFilters.kindSp(SearchKind.MOVIES))
    }

    @Test
    fun `bySp round-trips every filter`() {
        SearchFilters.all.forEach { filter ->
            assertEquals(filter.token, SearchFilters.bySp(filter.sp)?.token)
        }
        assertNull(SearchFilters.bySp("nonsense"))
    }
}
