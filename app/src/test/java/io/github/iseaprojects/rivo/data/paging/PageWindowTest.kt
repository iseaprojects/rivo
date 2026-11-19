package io.github.iseaprojects.rivo.data.paging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageWindowTest {

    private fun window(size: Int = 3) = PageWindow<String>(size) { it }

    @Test
    fun `issue chunks into exact pages`() {
        val window = window()
        window.append(listOf("a", "b", "c", "d", "e"))
        assertEquals(listOf("a", "b", "c"), window.issue())
        assertEquals(listOf("d", "e"), window.issue())
        assertEquals(2, window.pageCount)
    }

    @Test
    fun `duplicates are dropped by key`() {
        val window = window(2)
        window.append(listOf("a", "b", "a"))
        assertEquals(listOf("a", "b"), window.issue())
        window.append(listOf("b", "c"))
        assertEquals(listOf("c"), window.issue())
    }

    @Test
    fun `previous never refetches`() {
        val window = window(2)
        window.append(listOf("a", "b", "c", "d"))
        window.issue()
        window.issue()
        assertEquals(1, window.pageIndex)
        assertTrue(window.hasPrevious)
        assertEquals(listOf("a", "b"), window.move(-1))
        assertEquals(0, window.pageIndex)
        assertFalse(window.hasPrevious)
        assertNull(window.move(-1))
    }

    @Test
    fun `move forward past the issued window returns null`() {
        val window = window(2)
        window.append(listOf("a", "b"))
        window.issue()
        assertNull(window.move(1))
    }

    @Test
    fun `issue on an empty buffer issues nothing`() {
        val window = window()
        assertTrue(window.issue().isEmpty())
        assertEquals(0, window.pageCount)
        assertEquals(0, window.pageIndex)
    }

    @Test
    fun `canAdvance knows about buffered and fetchable items`() {
        val window = window(2)
        window.append(listOf("a", "b", "c"))
        window.issue()
        assertTrue(window.bufferedCount() == 1)
        assertTrue(window.canAdvance(feedExhausted = true))
        assertTrue(window.canAdvance(feedExhausted = false))
        window.append(listOf("d"))
        window.issue()
        assertEquals(0, window.bufferedCount())
        assertFalse(window.canAdvance(feedExhausted = true))
        assertTrue(window.canAdvance(feedExhausted = false))
    }

    @Test
    fun `a short page is still a page`() {
        val window = window(30)
        window.append((1..7).map { "v$it" })
        assertEquals(7, window.issue().size)
        assertEquals(1, window.pageCount)
    }
}
