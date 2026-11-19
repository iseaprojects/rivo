package io.github.iseaprojects.rivo.core.menu

import io.github.iseaprojects.rivo.core.config.ConfigSnapshot
import io.github.iseaprojects.rivo.core.i18n.LangInfo
import io.github.iseaprojects.rivo.core.i18n.Strings
import io.github.iseaprojects.rivo.core.theme.ThemeSpec

/**
 * Everything a menu builder or handler may read. Deliberately immutable per
 * resolution: when config, language or theme changes the UI rebuilds the context and
 * re-resolves the visible frame, which is cheaper and far more predictable than
 * mutable singletons.
 */
class MenuContext(
    val menuId: String,
    val payload: MenuPayload,
    val strings: Strings,
    val config: ConfigSnapshot,
    val theme: ThemeSpec,
    val themes: List<ThemeSpec>,
    val languages: List<LangInfo>,
    val navigator: MenuNavigator,
    /**
     * False when the APK ships no Nerd Font. The icon set then degrades to ASCII even
     * if `CONFIG_NERD_FONTS=true`, because the patched glyphs would render as tofu.
     */
    val nerdFontAvailable: Boolean = true,
    /**
     * Local library snapshot: saved videos, subscriptions, custom and saved playlists,
     * search history. Menus read it to label rows ("UnSave", "Unsubscribe") and to list
     * what the user owns without touching Room from a builder.
     */
    val library: MenuLibrary = MenuLibrary.EMPTY,
    /**
     * Downloader snapshot: queue rows and files already on disk. Read by the downloads
     * menu without touching the filesystem from a builder.
     */
    val downloads: MenuDownloads = MenuDownloads.EMPTY,
    /**
     * Extensions loaded from assets and the SAF folder: custom commands, site actions and
     * the settings summary. Kept as a snapshot for the same reason as [library].
     */
    val extensions: MenuExtensions = MenuExtensions.EMPTY,
    /** Latest GitHub release known to the update checker. */
    val updates: MenuUpdates = MenuUpdates.EMPTY,
) {
    fun rebuild(
        menuId: String = this.menuId,
        payload: MenuPayload = this.payload,
        strings: Strings = this.strings,
        config: ConfigSnapshot = this.config,
        theme: ThemeSpec = this.theme,
        themes: List<ThemeSpec> = this.themes,
        languages: List<LangInfo> = this.languages,
        navigator: MenuNavigator = this.navigator,
        library: MenuLibrary = this.library,
        downloads: MenuDownloads = this.downloads,
        extensions: MenuExtensions = this.extensions,
        updates: MenuUpdates = this.updates,
    ): MenuContext = MenuContext(
        menuId = menuId,
        payload = payload,
        strings = strings,
        config = config,
        theme = theme,
        themes = themes,
        languages = languages,
        navigator = navigator,
        nerdFontAvailable = nerdFontAvailable,
        library = library,
        downloads = downloads,
        extensions = extensions,
        updates = updates,
    )
}
