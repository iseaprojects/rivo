package io.github.iseaprojects.rivo.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigParserTest {

    @Test
    fun `parses plain, exported and quoted values`() {
        val text = """
            # comment
            CONFIG_PER_PAGE=30
            export CONFIG_THEME="tokyo night"
            CONFIG_MPV_ARGS='--no-config --vo=gpu'
            CONFIG_EMPTY=
            not a key
            1INVALID=value
        """.trimIndent()

        val parsed = ConfigParser.parse(text)

        assertEquals("30", parsed[ConfigKeys.PER_PAGE])
        assertEquals("tokyo night", parsed[ConfigKeys.THEME])
        assertEquals("--no-config --vo=gpu", parsed[ConfigKeys.MPV_ARGS])
        assertEquals("", parsed["CONFIG_EMPTY"])
        assertFalse(parsed.containsKey("not a key"))
        assertFalse(parsed.containsKey("1INVALID"))
    }

    @Test
    fun `duplicate keys resolve last wins like a shell script`() {
        val parsed = ConfigParser.parse("CONFIG_PER_PAGE=10\nCONFIG_PER_PAGE=50\n")
        assertEquals("50", parsed[ConfigKeys.PER_PAGE])
    }

    @Test
    fun `document edits one line and preserves comments and order`() {
        val original = """
            # Rivo config
            CONFIG_THEME=tokyo-night

            # paging
            CONFIG_PER_PAGE=30
        """.trimIndent()

        val document = ConfigDocument.parse(original)
        document.set(ConfigKeys.PER_PAGE, "60")
        val rendered = document.render()

        assertTrue(rendered.startsWith("# Rivo config"))
        assertTrue(rendered.contains("# paging"))
        assertTrue(rendered.contains("CONFIG_PER_PAGE=60"))
        assertFalse(rendered.contains("CONFIG_PER_PAGE=30"))
        assertEquals("60", ConfigDocument.parse(rendered).get(ConfigKeys.PER_PAGE))
    }

    @Test
    fun `set appends an unknown key and keeps an export prefix`() {
        val document = ConfigDocument.parse("export CONFIG_THEME=catppuccin-mocha\n")
        document.set(ConfigKeys.THEME, "tokyo-night")
        document.set(ConfigKeys.NERD_FONTS, "false")

        val rendered = document.render()

        assertTrue(rendered.contains("export CONFIG_THEME=tokyo-night"))
        assertTrue(rendered.contains("CONFIG_NERD_FONTS=false"))
    }

    @Test
    fun `values with spaces or hashes are quoted so a shell can still source the file`() {
        val document = ConfigDocument.parse("")
        document.set(ConfigKeys.DOWNLOAD_DIR, "Movies/Rivo #1")
        document.set(ConfigKeys.MPV_ARGS, "--profile=fast --hwdec=auto")

        val rendered = document.render()

        assertTrue(rendered.contains("CONFIG_DOWNLOAD_DIR=\"Movies/Rivo #1\""))
        assertTrue(rendered.contains("CONFIG_MPV_ARGS=\"--profile=fast --hwdec=auto\""))
        assertEquals("Movies/Rivo #1", ConfigDocument.parse(rendered).get(ConfigKeys.DOWNLOAD_DIR))
        assertEquals("--profile=fast --hwdec=auto", ConfigDocument.parse(rendered).get(ConfigKeys.MPV_ARGS))
    }

    @Test
    fun `override names map onto canonical config keys`() {
        assertEquals(ConfigKeys.PER_PAGE, ConfigKeys.keyForOverride("RIVO_PER_PAGE"))
        assertEquals(ConfigKeys.PER_PAGE, ConfigKeys.keyForOverride("YT_X_PER_PAGE"))
        assertEquals(ConfigKeys.DOWNLOADS_ENUMERATE, ConfigKeys.keyForOverride("RIVO_ENUMERATE_DOWNLOADS"))
        assertNull(ConfigKeys.keyForOverride("RIVO_NOT_A_SETTING"))
        assertNull(ConfigKeys.keyForOverride("PER_PAGE"))
        assertNull(ConfigKeys.keyForOverride("RIVO_"))
    }

    @Test
    fun `snapshot accessors degrade to defaults instead of throwing`() {
        val snapshot = ConfigSnapshot(
            mapOf(
                ConfigKeys.PER_PAGE to "not a number",
                ConfigKeys.ENABLE_PREVIEW to "no",
                ConfigKeys.NERD_FONTS to "yes",
                ConfigKeys.AUTOLOADED_EXTENSIONS to "themes, langs ,, ui",
            ),
        )

        assertEquals(30, snapshot.int(ConfigKeys.PER_PAGE, 30))
        assertFalse(snapshot.bool(ConfigKeys.ENABLE_PREVIEW, true))
        assertTrue(snapshot.bool(ConfigKeys.NERD_FONTS, false))
        assertEquals(listOf("themes", "langs", "ui"), snapshot.list(ConfigKeys.AUTOLOADED_EXTENSIONS))
        assertEquals("fallback", snapshot.string("CONFIG_MISSING", "fallback"))
    }
}
