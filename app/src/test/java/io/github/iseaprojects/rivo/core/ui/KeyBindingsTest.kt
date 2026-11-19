package io.github.iseaprojects.rivo.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyBindingsTest {

    private val defaults = KeyBindings.DEFAULT

    @Test
    fun `default bindings cover the fzf keys`() {
        assertEquals(KeyAction.DOWN, defaults.actionFor("j", false, false, false))
        assertEquals(KeyAction.UP, defaults.actionFor("k", false, false, false))
        assertEquals(KeyAction.HALF_PAGE_DOWN, defaults.actionFor("d", true, false, false))
        assertEquals(KeyAction.HALF_PAGE_UP, defaults.actionFor("u", true, false, false))
        assertEquals(KeyAction.SELECT, defaults.actionFor("enter", false, false, false))
        assertEquals(KeyAction.BACK, defaults.actionFor("esc", false, false, false))
        assertEquals(KeyAction.SEARCH, defaults.actionFor("/", false, false, false))
        assertEquals(KeyAction.PREVIEW, defaults.actionFor("space", false, false, false))
    }

    @Test
    fun `ctrl and plain letters are distinct`() {
        assertEquals(KeyAction.HALF_PAGE_DOWN, defaults.actionFor("d", true, false, false))
        assertNull(defaults.actionFor("d", false, false, false))
    }

    @Test
    fun `unknown keys and actions are dropped`() {
        val parsed = KeyBindings.parse("j:down,nope:down,x:teleport")
        assertEquals(listOf("j"), parsed.bindings().map { it.key })
    }

    @Test
    fun `arrow names are canonicalised`() {
        val parsed = KeyBindings.parse("arrow-down:down,pgup:half-page-up,return:select")
        assertEquals(KeyAction.DOWN, parsed.actionFor("down", false, false, false))
        assertEquals(KeyAction.HALF_PAGE_UP, parsed.actionFor("page-up", false, false, false))
        assertEquals(KeyAction.SELECT, parsed.actionFor("enter", false, false, false))
    }

    @Test
    fun `modifier order does not matter`() {
        val parsed = KeyBindings.parse("ctrl-alt-shift-x:next-page")
        assertEquals(KeyAction.NEXT_PAGE, parsed.actionFor("x", true, true, true))
        assertEquals(KeyAction.NEXT_PAGE, KeyBindings.parse("shift-ctrl-alt-x:next-page")
            .actionFor("x", true, true, true))
    }

    @Test
    fun `plain characters are flagged so they never eat typing`() {
        assertTrue(defaults.isPlainCharacter(KeyAction.DOWN))
        assertTrue(defaults.isPlainCharacter(KeyAction.PREVIEW))
        assertFalse(defaults.isPlainCharacter(KeyAction.SELECT))
        assertFalse(defaults.isPlainCharacter(KeyAction.HALF_PAGE_DOWN))
    }

    @Test
    fun `duplicate bindings collapse`() {
        val parsed = KeyBindings.parse("j:down,j:down,k:up")
        assertEquals(2, parsed.bindings().size)
    }

    @Test
    fun `malformed entries do not throw`() {
        val parsed = KeyBindings.parse(",,,,:down,j:,j")
        assertTrue(parsed.bindings().isEmpty())
    }
}
