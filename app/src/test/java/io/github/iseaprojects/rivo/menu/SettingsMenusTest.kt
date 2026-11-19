package io.github.iseaprojects.rivo.menu

import io.github.iseaprojects.rivo.core.config.ConfigSnapshot
import io.github.iseaprojects.rivo.core.i18n.MapStrings
import io.github.iseaprojects.rivo.core.menu.HandlerIds
import io.github.iseaprojects.rivo.core.menu.MenuCommand
import io.github.iseaprojects.rivo.core.menu.MenuCommandSource
import io.github.iseaprojects.rivo.core.menu.MenuContext
import io.github.iseaprojects.rivo.core.menu.MenuDsl
import io.github.iseaprojects.rivo.core.menu.MenuEngine
import io.github.iseaprojects.rivo.core.menu.MenuExtensions
import io.github.iseaprojects.rivo.core.menu.MenuHandlerRegistry
import io.github.iseaprojects.rivo.core.menu.MenuIds
import io.github.iseaprojects.rivo.core.menu.MenuNavigator
import io.github.iseaprojects.rivo.core.menu.MenuNode
import io.github.iseaprojects.rivo.core.menu.MenuPayload
import io.github.iseaprojects.rivo.core.menu.MenuSite
import io.github.iseaprojects.rivo.core.menu.MenuSiteAction
import io.github.iseaprojects.rivo.core.menu.MenuUpdateState
import io.github.iseaprojects.rivo.core.menu.MenuUpdates
import io.github.iseaprojects.rivo.core.menu.extension.UiExtensionSource
import io.github.iseaprojects.rivo.core.theme.ThemeSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F7 added five screens whose rows depend on extension/update state. They are catalog
 * menus like every other screen, so the tests pin the rows and their handlers instead of
 * letting a missing menu id degrade to a placeholder at runtime.
 */
class SettingsMenusTest {

    private val labels = mapOf(
        "TXT_ANDROID_EXTENSIONS_EMPTY_HINT" to "add a file",
        "TXT_ANDROID_EXTENSIONS_NONE" to "no folder",
        "TXT_ANDROID_EXTENSIONS_AUTOLOAD_ALL" to "everything",
        "TXT_ANDROID_ENABLED" to "on",
        "TXT_ANDROID_DISABLED" to "off",
        "TXT_ANDROID_EXTENSIONS_LOADED_ONE" to "one loaded",
        "TXT_ANDROID_EXTRACTOR_AUTO" to "automatic",
        "TXT_ANDROID_EXTRACTOR_FAST_ONLY" to "fast only",
        "TXT_ANDROID_OFF" to "off",
        "TXT_ANDROID_UPDATE_IDLE" to "idle",
        "TXT_ANDROID_EXTENSION" to "extension",
        "TXT_ANDROID_COMMAND_MISSING" to "missing",
        "TXT_ANDROID_COMMANDS_EMPTY_HINT" to "create one",
        "TXT_ANDROID_SITES_EMPTY" to "no sites",
        "TXT_ANDROID_SITES_EMPTY_HINT" to "add a site",
    )

    private val engine = MenuEngine(
        catalog = BuiltInMenus.catalog(),
        handlers = MenuHandlerRegistry.empty(),
        extensions = UiExtensionSource { emptyList() },
    )

    @Test
    fun `custom commands screen lists local and extension commands`() {
        val command = MenuCommand(
            id = "abc12345",
            name = "Dailymotion",
            url = "https://www.dailymotion.com",
        )
        val extension = MenuCommand(
            id = "ext:news:dailymotion",
            name = "News",
            url = "https://example.com/news",
            source = MenuCommandSource.EXTENSION,
        )
        val nodes = nodes(
            MenuIds.CUSTOM_COMMANDS,
            extensions = MenuExtensions(commands = listOf(command, extension)),
        )

        assertEquals("commands.new", nodes.first().id)
        assertEquals(HandlerIds.CUSTOM_CMD_NEW, nodes.first().handler)
        val rows = nodes.filter { it.targetMenuId == MenuIds.CUSTOM_COMMAND_ACTIONS }
        assertEquals(2, rows.size)
        assertEquals(MenuPayload.Command("abc12345"), rows[0].payload)
    }

    @Test
    fun `an empty command list explains itself`() {
        val nodes = nodes(MenuIds.CUSTOM_COMMANDS, extensions = MenuExtensions.EMPTY)
        assertNotNull(nodes.firstOrNull { it.id == "commands.empty" })
    }

    @Test
    fun `a command action screen offers run edit and delete for local commands`() {
        val command = MenuCommand("abc", "Dailymotion", "https://www.dailymotion.com")
        val nodes = nodes(
            MenuIds.CUSTOM_COMMAND_ACTIONS,
            payload = MenuPayload.Command("abc"),
            extensions = MenuExtensions(commands = listOf(command)),
        )

        assertEquals(
            listOf("commands.run", "commands.shell", "commands.url", "commands.edit", "commands.delete", "app.back", "app.exit"),
            nodes.map { it.id },
        )
        assertTrue(nodes.first { it.id == "commands.delete" }.tone == io.github.iseaprojects.rivo.core.menu.MenuTone.DESTRUCTIVE)
    }

    @Test
    fun `extension commands are read only`() {
        val command = MenuCommand(
            "ext:x:y",
            "Remote",
            "https://example.com",
            source = MenuCommandSource.EXTENSION,
        )
        val nodes = nodes(
            MenuIds.CUSTOM_COMMAND_ACTIONS,
            payload = MenuPayload.Command("ext:x:y"),
            extensions = MenuExtensions(commands = listOf(command)),
        )

        assertTrue(nodes.none { it.id == "commands.edit" || it.id == "commands.delete" })
    }

    @Test
    fun `a missing command does not leave a blank screen`() {
        val nodes = nodes(
            MenuIds.CUSTOM_COMMAND_ACTIONS,
            payload = MenuPayload.Command("gone"),
        )

        assertEquals("commands.missing", nodes.first().id)
        assertTrue(nodes.any { it.id == "app.back" })
    }

    @Test
    fun `sites screen turns one row per action and prompts when needed`() {
        val site = MenuSite(
            id = "dailymotion",
            name = "Dailymotion",
            actions = listOf(
                MenuSiteAction("search", "Search", "Query", "cats", "https://d.example/{query}"),
                MenuSiteAction("home", "Home", null, null, "https://d.example"),
            ),
        )
        val nodes = nodes(MenuIds.SITES, extensions = MenuExtensions(sites = listOf(site)))

        val actionRows = nodes.filter { it.handler == HandlerIds.SITE_RUN }
        assertEquals(2, actionRows.size)
        assertEquals(MenuPayload.SiteActionRef("dailymotion", "search"), actionRows[0].payload)
        assertEquals("Query", actionRows[0].detail)
    }

    @Test
    fun `extension settings expose the folder lifecycle`() {
        val nodes = nodes(MenuIds.EXTENSIONS, extensions = MenuExtensions(folderLabel = "Download/ext"))

        assertEquals(HandlerIds.EXTENSIONS_TOGGLE, nodes.first().handler)
        assertTrue(nodes.any { it.id == "extensions.pick" })
        assertTrue(nodes.any { it.id == "extensions.autoload" })
        assertTrue(nodes.any { it.id == "extensions.reload" })
        assertTrue(nodes.any { it.id == "extensions.clear" })
    }

    @Test
    fun `cookies screen always offers import login and clear`() {
        val nodes = nodes(MenuIds.COOKIES)
        assertEquals(HandlerIds.COOKIES_IMPORT, nodes.first { it.id == "cookies.import" }.handler)
        assertEquals(HandlerIds.COOKIES_LOGIN, nodes.first { it.id == "cookies.login" }.handler)
        assertEquals(HandlerIds.COOKIES_CLEAR, nodes.first { it.id == "cookies.clear" }.handler)
    }

    @Test
    fun `update screen renders the state it was given`() {
        val available = nodes(
            MenuIds.UPDATE,
            updates = MenuUpdates(
                state = MenuUpdateState.AVAILABLE,
                currentVersion = "0.8.7",
                latestVersion = "0.9.0",
                releaseName = "Rivo 0.9.0",
                notes = "notes",
                url = "https://example.com/release",
            ),
        )

        assertEquals("0.9.0", available.first { it.id == "update.available" }.detail)
        assertEquals("https://example.com/release", (available.first { it.id == "update.open" }.payload as MenuPayload.Url).url)
        assertTrue(available.any { it.handler == HandlerIds.UPDATE_CHECK })
    }

    private fun nodes(
        menuId: String,
        payload: MenuPayload = MenuPayload.None,
        extensions: MenuExtensions = MenuExtensions.EMPTY,
        updates: MenuUpdates = MenuUpdates(currentVersion = "0.8.7"),
    ): List<MenuNode> {
        val context = MenuContext(
            menuId = menuId,
            payload = payload,
            strings = MapStrings("en", labels),
            config = ConfigSnapshot(emptyMap()),
            theme = ThemeSpec.FALLBACK,
            themes = emptyList(),
            languages = emptyList(),
            navigator = object : MenuNavigator {
                override fun open(
                    menuId: String,
                    payload: MenuPayload,
                    title: String,
                    nodes: List<MenuNode>?,
                ) = Unit

                override fun back(): Boolean = false
                override fun exit() = Unit
            },
            extensions = extensions,
            updates = updates,
        )
        return engine.nodes(menuId, context)
    }
}
