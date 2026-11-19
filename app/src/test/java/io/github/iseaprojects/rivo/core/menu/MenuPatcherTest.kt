package io.github.iseaprojects.rivo.core.menu

import io.github.iseaprojects.rivo.core.menu.extension.UiExtensionLoader
import io.github.iseaprojects.rivo.core.menu.extension.UiMenuPatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuPatcherTest {

    private fun nodes(vararg ids: String): List<MenuNode> =
        ids.map { id -> MenuDsl.action(id = id, labelKey = "TXT_$id", handler = "test.$id") }

    @Test
    fun `explicit sort wins and unlisted nodes keep their order at the end`() {
        val sorted = MenuPatcher.sort(nodes("a", "b", "c", "d"), listOf("c", "a"), dropUnlisted = false)
        assertEquals(listOf("c", "a", "b", "d"), sorted.map { it.id })
    }

    @Test
    fun `dropUnlisted removes whatever the patch did not name`() {
        val sorted = MenuPatcher.sort(nodes("a", "b", "c"), listOf("b"), dropUnlisted = true)
        assertEquals(listOf("b"), sorted.map { it.id })
    }

    @Test
    fun `sort accepts 1-based indexes like yt-x sort strings`() {
        val sorted = MenuPatcher.sort(nodes("a", "b", "c"), listOf("3", "1"), dropUnlisted = true)
        assertEquals(listOf("c", "a"), sorted.map { it.id })
    }

    @Test
    fun `filter removes by id or label key, not by translated label`() {
        val patched = MenuPatcher.apply(
            nodes("keep", "legacy.one", "other"),
            UiMenuPatch(filter = Regex("^legacy\\.")),
        )
        assertEquals(listOf("keep", "other"), patched.map { it.id })
    }

    @Test
    fun `injection honours start, end, before, after and numeric positions`() {
        val injected = listOf(
            MenuNode(id = "first", label = "First", position = "start"),
            MenuNode(id = "afterA", label = "After A", position = "after:a"),
            MenuNode(id = "beforeC", label = "Before C", position = "before:c"),
            MenuNode(id = "last", label = "Last"),
        )

        val result = MenuPatcher.apply(nodes("a", "b", "c"), UiMenuPatch(inject = injected))

        assertEquals(
            listOf("first", "a", "afterA", "b", "beforeC", "c", "last"),
            result.map { it.id },
        )
    }

    @Test
    fun `extension json parses sort, filter, dropUnlisted and nested children`() {
        val json = """
            {
              "schema": "rivo.ui/1",
              "menus": {
                "main": {
                  "sort": ["search", "feed.home"],
                  "filter": "^deprecated\\.",
                  "dropUnlisted": true,
                  "inject": [
                    {
                      "id": "sites",
                      "label": "Sites",
                      "icon": "\uE5FF",
                      "position": "after:search",
                      "children": [
                        { "id": "sites.dailymotion", "label": "Dailymotion", "handler": "custom.dailymotion" }
                      ]
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val extension = UiExtensionLoader().parse("test.ui.json", json)
        val patch = extension.patches.getValue("main")

        assertTrue(extension.issues.isEmpty())
        assertEquals(listOf("search", "feed.home"), patch.sort)
        assertTrue(patch.dropUnlisted)
        assertNotNull(patch.filter)
        assertEquals(1, patch.inject.size)

        val sites = patch.inject.single()
        assertEquals("sites", sites.id)
        assertEquals(MenuSource.EXTENSION, sites.source)
        assertTrue(sites.isSubmenu)
        assertEquals("custom.dailymotion", sites.children.single().handler)
    }

    @Test
    fun `a broken extension is reported and never throws`() {
        val extension = UiExtensionLoader().parse("broken.ui.json", "{ not json")
        assertTrue(extension.patches.isEmpty())
        assertEquals(1, extension.issues.size)
    }

    @Test
    fun `invalid filter regex drops the filter but keeps the menu patch`() {
        val json = """
            { "menus": { "main": { "filter": "([", "sort": ["search"] } } }
        """.trimIndent()

        val extension = UiExtensionLoader().parse("regex.ui.json", json)
        val patch = extension.patches.getValue("main")

        assertEquals(null, patch.filter)
        assertEquals(listOf("search"), patch.sort)
        assertEquals(1, extension.issues.size)
    }

    @Test
    fun `nodes without label are rejected during injection`() {
        val json = """
            { "menus": { "main": { "inject": [ { "id": "nameless" } ] } } }
        """.trimIndent()

        val extension = UiExtensionLoader().parse("label.ui.json", json)
        assertTrue(extension.patches.getValue("main").inject.isEmpty())
        assertEquals(1, extension.issues.size)
    }
}
