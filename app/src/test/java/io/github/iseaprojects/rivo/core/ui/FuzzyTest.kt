package io.github.iseaprojects.rivo.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyTest {

    @Test
    fun `empty query matches everything in order`() {
        val items = listOf("alpha", "beta")
        assertEquals(items, Fuzzy.filter(items, "") { it })
        assertEquals(items, Fuzzy.filter(items, "   ") { it })
    }

    @Test
    fun `every token must match`() {
        assertTrue(Fuzzy.matches("lofi girl", "Lofi Girl - 24/7 radio"))
        assertFalse(Fuzzy.matches("lofi jazz", "Lofi Girl - 24/7 radio"))
    }

    @Test
    fun `substring beats subsequence`() {
        val items = listOf("digits explained", "git explained")
        val filtered = Fuzzy.filter(items, "git") { it }
        assertEquals("git explained", filtered.first())
    }

    @Test
    fun `subsequence matches when no substring does`() {
        assertTrue(Fuzzy.matches("lfg", "lofi girl"))
        assertNull(Fuzzy.score("xyz", "lofi girl"))
    }

    @Test
    fun `prefix scores higher than word boundary`() {
        val prefix = Fuzzy.score("git", "gitignore explained")!!
        val boundary = Fuzzy.score("git", "using git daily")!!
        val inside = Fuzzy.score("git", "digits explained")!!
        assertTrue(prefix > boundary)
        assertTrue(boundary > inside)
    }

    @Test
    fun `ties keep the original order`() {
        val items = listOf("mix one", "mix two", "mix three")
        assertEquals(items, Fuzzy.filter(items, "mix") { it })
    }

    @Test
    fun `case is ignored`() {
        assertTrue(Fuzzy.matches("LOFI", "lofi girl"))
        assertTrue(Fuzzy.matches("lofi", "LOFI GIRL"))
    }

    @Test
    fun `filter keeps items and scores`() {
        val items = listOf("orange", "apple", "grape")
        assertEquals(listOf("grape"), Fuzzy.filter(items, "grap") { it })
    }
}
