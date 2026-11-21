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
    fun `a match late in the text still beats a scattered one`() {
        // Unbounded position penalties made a verbatim match 400 characters in score lower
        // than a subsequence match, which is the wrong order for a filter.
        val late = "a".repeat(400) + "needle"
        val scattered = "n-e-e-d-l-e"
        val lateScore = Fuzzy.score("needle", late)!!
        assertTrue(lateScore > Fuzzy.score("needle", scattered)!!)
        assertTrue(lateScore > 0)
    }

    @Test
    fun `a long query still outranks a word boundary match`() {
        val long = "x".repeat(400)
        assertEquals(1200 - 200, Fuzzy.score(long, long + " tail")!!)
    }

    @Test
    fun `case is ignored`() {
        assertTrue(Fuzzy.matches("LOFI", "lofi girl"))
        assertTrue(Fuzzy.matches("lofi", "LOFI GIRL"))
    }

    @Test
    fun `accented text is matched without the accent`() {
        assertTrue(Fuzzy.matches("cancion", "Canción de cuna"))
        assertTrue(Fuzzy.matches("coração", "Coracao de mae"))
        assertTrue(Fuzzy.matches("anejo", "Añejo reposado"))
        assertTrue(Fuzzy.matches("uber", "Über alles"))
    }

    @Test
    fun `accented and plain spellings score the same`() {
        assertEquals(
            Fuzzy.score("cancion", "Canción de cuna"),
            Fuzzy.score("canción", "Cancion de cuna"),
        )
    }

    @Test
    fun `folding keeps the prefix bonus for accented titles`() {
        val prefix = Fuzzy.score("cancion", "Canción de cuna")!!
        val inside = Fuzzy.score("cancion", "una cancion triste")!!
        assertTrue(prefix > inside)
    }

    @Test
    fun `folding is locale independent`() {
        assertEquals("cancion", Fuzzy.fold("Canción"))
        // U+0130 decomposes to I plus a combining dot, which folding drops.
        assertEquals("istanbul", Fuzzy.fold("İstanbul"))
    }

    @Test
    fun `filter keeps items and scores`() {
        val items = listOf("orange", "apple", "grape")
        assertEquals(listOf("grape"), Fuzzy.filter(items, "grap") { it })
    }
}
