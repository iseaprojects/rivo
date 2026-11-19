package io.github.iseaprojects.rivo.core.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheRetentionTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `days are clamped to a sane range`() {
        assertEquals(0, CacheRetention.normaliseDays(-4))
        assertEquals(365, CacheRetention.normaliseDays(10_000))
        assertEquals(7, CacheRetention.normaliseDays(7))
    }

    @Test
    fun `zero keeps everything`() {
        assertEquals(Long.MIN_VALUE, CacheRetention.cutoff(now, 0))
        assertFalse(CacheRetention.isExpired(lastModified = 0, now = now, days = 0))
    }

    @Test
    fun `cutoff is a whole number of days back`() {
        assertEquals(now - 3 * CacheRetention.DAY_MS, CacheRetention.cutoff(now, 3))
    }

    @Test
    fun `a file exactly at the cutoff is still kept`() {
        val cutoff = CacheRetention.cutoff(now, 2)
        assertFalse(CacheRetention.isExpired(cutoff, now, 2))
        assertTrue(CacheRetention.isExpired(cutoff - 1, now, 2))
    }
}
