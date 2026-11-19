package io.github.iseaprojects.rivo.core.menu.extension

import io.github.iseaprojects.rivo.core.menu.MenuCommandSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The declarative extension format is the security boundary of F7: it can name a URL and
 * a prompt, never a command. These tests pin the accepted shape and the diagnostics that
 * keep a broken file from taking the menu down with it.
 */
class ExtensionFileParserTest {

    private val parser = ExtensionFileParser()

    @Test
    fun `parses a site with a prompt-driven action`() {
        val result = parser.parseSites(
            sourceName = "dailymotion.sites.json",
            sourceKey = "dailymotion",
            text = """
                {
                  "schema": "rivo.sites/1",
                  "sites": [
                    {
                      "id": "dailymotion",
                      "name": "Dailymotion",
                      "icon": "\uF0AC",
                      "actions": [
                        {
                          "id": "search",
                          "label": "Search Dailymotion",
                          "prompt": "Search query",
                          "promptHint": "cats",
                          "url": "https://www.dailymotion.com/search/{query}/videos"
                        }
                      ]
                    }
                  ]
                }
            """.trimIndent(),
        )

        assertTrue(result.issues.isEmpty())
        val site = result.sites.single()
        assertEquals("dailymotion:dailymotion", site.id)
        assertEquals("Dailymotion", site.name)
        val action = site.actions.single()
        assertEquals("search", action.id)
        assertEquals("Search query", action.prompt)
        assertEquals("cats", action.promptHint)
        assertEquals("https://www.dailymotion.com/search/{query}/videos", action.urlTemplate)
    }

    @Test
    fun `a query token without a prompt is reported but the action is kept`() {
        val result = parser.parseSites(
            "sites.json",
            "file",
            """
                {"sites":[{"name":"PeerTube","actions":[
                  {"label":"Search","url":"https://tube.example/search?q={query}"}
                ]}]}
            """.trimIndent(),
        )

        assertEquals(1, result.sites.size)
        assertTrue(result.issues.single().contains("without a prompt"))
    }

    @Test
    fun `broken json becomes an issue instead of an exception`() {
        val result = parser.parseSites("broken.sites.json", "broken", "{ not json")

        assertTrue(result.sites.isEmpty())
        assertTrue(result.issues.single().startsWith("broken.sites.json:"))
    }

    @Test
    fun `actions without a label or url are dropped`() {
        val result = parser.parseSites(
            "sites.json",
            "file",
            """
                {"sites":[{"name":"Site","actions":[
                  {"label":"","url":"https://example.com"},
                  {"label":"Open","url":""}
                ]}]}
            """.trimIndent(),
        )

        assertTrue(result.sites.isEmpty())
        assertEquals(3, result.issues.size)
    }

    @Test
    fun `parses commands in the same shape the editor writes`() {
        val result = parser.parseCommands(
            sourceName = "news.cmds.json",
            sourceKey = "news",
            text = """
                {
                  "schema": "rivo.cmds/1",
                  "commands": [
                    {
                      "name": "Dailymotion",
                      "url": "https://www.dailymotion.com",
                      "yt_dlp_opts": "--no-flat-playlist"
                    }
                  ]
                }
            """.trimIndent(),
        )

        val command = result.commands.single()
        assertEquals("Dailymotion", command.name)
        assertEquals("https://www.dailymotion.com", command.url)
        assertEquals("--no-flat-playlist", command.options)
        assertEquals(MenuCommandSource.EXTENSION, command.source)
        assertEquals("ext:news:dailymotion", command.id)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `duplicate site ids keep the first declaration`() {
        val result = parser.parseSites(
            "sites.json",
            "file",
            """
                {"sites":[
                  {"id":"one","name":"One","actions":[{"label":"A","url":"https://a.example"}]},
                  {"id":"one","name":"Two","actions":[{"label":"B","url":"https://b.example"}]}
                ]}
            """.trimIndent(),
        )

        assertEquals(1, result.sites.size)
        assertEquals("One", result.sites.single().name)
    }

    @Test
    fun `template expansion encodes the query and leaves other text alone`() {
        assertEquals(
            "https://example.com/search/a%20b%26c%2Fd",
            SiteTemplate.expand("https://example.com/search/{query}", "a b&c/d"),
        )
        assertEquals(
            "https://example.com/plain",
            SiteTemplate.expand("https://example.com/plain", "ignored"),
        )
    }

    @Test
    fun `an empty declared id falls back to a slug`() {
        val result = parser.parseSites(
            "sites.json",
            "my-file",
            """{"sites":[{"name":"My Site","actions":[{"label":"Go","url":"https://e.example"}]}]}""",
        )

        assertEquals("my-file:my-site", result.sites.single().id)
        assertNull(result.sites.single().icon)
    }
}
