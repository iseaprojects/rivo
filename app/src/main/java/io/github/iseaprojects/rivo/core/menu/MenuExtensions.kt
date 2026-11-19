package io.github.iseaprojects.rivo.core.menu

/**
 * Extension state as the menu layer sees it.
 *
 * Same rule as [MenuLibrary] and [MenuDownloads]: immutable, built by `AppGraph`, and read
 * by builders without touching the filesystem or a SAF provider. Loading happens on IO;
 * a menu rebuild only reads this snapshot, so opening Miscellaneous never blocks on a
 * cloud folder.
 */
data class MenuExtensions(
    /** `CONFIG_EXTENSIONS_ENABLED`: SAF extensions are ignored when false. */
    val enabled: Boolean = true,
    /** Human description of the selected folder; blank when no folder is configured. */
    val folderLabel: String = "",
    /** `CONFIG_AUTOLOADED_EXTENSIONS`, verbatim, for the settings row. */
    val autoloaded: String = "",
    val commands: List<MenuCommand> = emptyList(),
    val sites: List<MenuSite> = emptyList(),
    /**
     * Every file that was accepted, so the settings screen can say what is loaded instead
     * of just "yes". [kind] is `theme`, `lang`, `ui`, `sites` or `cmds`.
     */
    val entries: List<MenuExtensionEntry> = emptyList(),
    /** Parse/read problems, one sentence each. Never blocks loading the other files. */
    val issues: List<String> = emptyList(),
) {
    val hasFolder: Boolean get() = folderLabel.isNotBlank()

    val loadedCount: Int get() = entries.size

    fun commandsOf(source: MenuCommandSource): List<MenuCommand> =
        commands.filter { it.source == source }

    fun findCommand(id: String): MenuCommand? = commands.firstOrNull { it.id == id }

    fun findSiteAction(siteId: String, actionId: String): Pair<MenuSite, MenuSiteAction>? {
        val site = sites.firstOrNull { it.id == siteId } ?: return null
        val action = site.actions.firstOrNull { it.id == actionId } ?: return null
        return site to action
    }

    companion object {
        val EMPTY = MenuExtensions()
    }
}

enum class MenuCommandSource {
    /** Created in the app; can be edited and deleted. */
    LOCAL,

    /** Brought in by a `.cmds` extension; read-only, so reloading the folder is enough. */
    EXTENSION,
}

/**
 * A custom command. yt-x stores `name`, `url` and extra `yt-dlp` options; the Android port
 * keeps exactly those three fields so a command file round-trips, and uses the options
 * when the command is handed to yt-dlp (Termux shell handoff).
 */
data class MenuCommand(
    val id: String,
    val name: String,
    val url: String,
    val options: String = "",
    val source: MenuCommandSource = MenuCommandSource.LOCAL,
)

/** A `.sites` extension: a named group of prompt-driven URL actions. */
data class MenuSite(
    val id: String,
    val name: String,
    val icon: String? = null,
    val actions: List<MenuSiteAction> = emptyList(),
)

/**
 * One site action. The template may contain `{query}` (URL-encoded prompt answer) and is
 * otherwise treated as an opaque URL: extensions cannot execute code, only name a
 * destination. That is the sandbox boundary of the port.
 */
data class MenuSiteAction(
    val id: String,
    val label: String,
    val prompt: String? = null,
    val promptHint: String? = null,
    val urlTemplate: String,
)

data class MenuExtensionEntry(
    val name: String,
    val kind: String,
    val detail: String = "",
)
