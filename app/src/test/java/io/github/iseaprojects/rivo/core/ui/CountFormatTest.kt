package io.github.iseaprojects.rivo.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CountFormatTest {

    @Test
    fun `small counts are exact`() {
        assertEquals("0", CountFormat.compact(0))
        assertEquals("999", CountFormat.compact(999))
    }

    @Test
    fun `thousands use K`() {
        assertEquals("1K", CountFormat.compact(1_000))
        assertEquals("1.2K", CountFormat.compact(1_240))
        assertEquals("999.4K", CountFormat.compact(999_400))
    }

    @Test
    fun `millions and billions`() {
        assertEquals("1M", CountFormat.compact(1_000_000))
        assertEquals("3.4M", CountFormat.compact(3_400_000))
        assertEquals("1B", CountFormat.compact(1_000_000_000))
    }

    @Test
    fun `rounding does not produce 1000K`() {
        assertEquals("1M", CountFormat.compact(999_500))
        assertEquals("1B", CountFormat.compact(999_500_000))
    }

    @Test
    fun `absent and negative counts are empty`() {
        assertEquals("", CountFormat.compact(null))
        assertEquals("", CountFormat.compact(-5))
    }
}
