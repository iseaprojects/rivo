package io.github.iseaprojects.rivo.core.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorHexTest {

    @Test
    fun `six digit colors become opaque argb`() {
        assertEquals(0xFF7AA2F7.toInt(), ColorHex.parseArgb("#7aa2f7"))
        assertEquals(0xFF7AA2F7.toInt(), ColorHex.parseArgb("7aa2f7"))
    }

    @Test
    fun `three digit shorthand is expanded`() {
        assertEquals(0xFFFFFFFF.toInt(), ColorHex.parseArgb("#fff"))
        assertEquals(0xFF000000.toInt(), ColorHex.parseArgb("#000"))
    }

    @Test
    fun `eight digit values keep their alpha`() {
        assertEquals(0x8016161E.toInt(), ColorHex.parseArgb("#8016161e"))
    }

    @Test
    fun `invalid input returns null instead of throwing`() {
        assertNull(ColorHex.parseArgb(""))
        assertNull(ColorHex.parseArgb("#12"))
        assertNull(ColorHex.parseArgb("#zzzzzz"))
        assertNull(ColorHex.parseArgb("rgb(1,2,3)"))
    }

    @Test
    fun `terminal tokens fall back to the icon primary color`() {
        val spec = ThemeSpec(
            id = "test",
            name = "Test",
            terminal = mapOf("iconPrimary" to "#2ac3de", "iconError" to "#f7768e"),
        )
        assertEquals(0xFF2AC3DE.toInt(), spec.terminalArgb("previewKey", fallbackToken = "iconPrimary"))
        assertEquals(0xFFF7768E.toInt(), spec.terminalArgb("iconError"))
        assertNull(spec.terminalArgb("missing"))
    }
}
