package io.github.iseaprojects.rivo.menu

import io.github.iseaprojects.rivo.BuildConfig
import io.github.iseaprojects.rivo.core.config.ConfigKeys
import io.github.iseaprojects.rivo.core.menu.HandlerIds
import io.github.iseaprojects.rivo.core.menu.MenuCatalog
import io.github.iseaprojects.rivo.core.menu.MenuCommand
import io.github.iseaprojects.rivo.core.menu.MenuCommandSource
import io.github.iseaprojects.rivo.core.menu.MenuDefinition
import io.github.iseaprojects.rivo.core.menu.MenuDsl
import io.github.iseaprojects.rivo.core.menu.MenuIds
import io.github.iseaprojects.rivo.core.menu.MenuNode
import io.github.iseaprojects.rivo.core.menu.MenuNodeKind
import io.github.iseaprojects.rivo.core.menu.MenuPayload
import io.github.iseaprojects.rivo.core.menu.MenuTone
import io.github.iseaprojects.rivo.core.menu.MenuUpdateState
import io.github.iseaprojects.rivo.core.menu.MenuContext
import io.github.iseaprojects.rivo.core.model.FeedKind
import io.github.iseaprojects.rivo.core.model.LibraryKind
import io.github.iseaprojects.rivo.core.model.SearchKind
import io.github.iseaprojects.rivo.core.theme.ThemeSpec
import io.github.iseaprojects.rivo.data.download.DownloadNaming
import io.github.iseaprojects.rivo.data.download.RangedDownloader
import io.github.iseaprojects.rivo.data.extract.YtdlpSupport
import java.util.Locale

/**
 * Built-in menus, declared exactly like an extension would declare them.
 *
 * Order follows the port contract (Your Feed, Search, Subscriptions, Trending, Watch
 * Later, Liked, History, Clips, Playlists, Custom Playlists, Saved, Recent, Channels,
 * Miscellaneous, Edit Config) rather than upstream's internal index order, because
 * every entry here is stable and sortable by id.
 */
object BuiltInMenus {

    private const val GLYPH_TRENDING = "\uF06D"
    private const val GLYPH_GEAR = "\uF013"
    private const val GLYPH_GLOBE = "\uF0AC"
    private const val GLYPH_REFRESH = "\uF021"

    fun catalog(): MenuCatalog = MenuCatalog.of(
        main(),
        miscellaneous(),
        config(),
        choice(),
        searchHistory(),
        histories(),
        customPlaylists(),
        localPlaylistActions(),
        savedPlaylists(),
        channels(),
        addToPlaylist(),
        customCommands(),
        customCommandActions(),
        sites(),
        extensionsSettings(),
        cookiesSettings(),
        extractorSettings(),
        updateScreen(),
        MediaMenus.mediaActions(),
        MediaMenus.channelActions(),
        DownloadMenus.downloads(),
        DownloadMenus.taskActions(),
        DownloadMenus.fileActions(),
    )

    private fun main(): MenuDefinition = MenuDefinition(
        id = MenuIds.MAIN,
        titleKey = "TXT_MENU_MAIN",
        build = { ctx ->
            listOf(
                MenuDsl.feed(
                    id = "feed.home",
                    labelKey = "TXT_MENU_MAIN_FEED",
                    iconKey = "TXT_ICON_MENU_MAIN_FEED",
                    kind = FeedKind.HOME,
                ),
                MenuDsl.target(
                    id = "search",
                    labelKey = "TXT_MENU_MAIN_SEARCH",
                    targetMenuId = MenuIds.SEARCH,
                    iconKey = "TXT_ICON_MENU_MAIN_SEARCH",
                ),
                MenuDsl.feed(
                    id = "feed.subscriptions",
                    labelKey = "TXT_MENU_MAIN_SUBS_FEED",
                    iconKey = "TXT_ICON_MENU_MAIN_SUBS_FEED",
                    kind = FeedKind.SUBSCRIPTIONS,
                ),
                MenuNode(
                    id = "feed.trending",
                    labelKey = "TXT_ANDROID_MENU_TRENDING",
                    icon = GLYPH_TRENDING,
                    targetMenuId = MenuIds.FEED,
                    payload = MenuPayload.Feed(FeedKind.TRENDING),
                ),
                MenuDsl.feed(
                    id = "feed.watch_later",
                    labelKey = "TXT_MENU_MAIN_WATCH_LATER",
                    iconKey = "TXT_ICON_MENU_MAIN_WATCH_LATER",
                    kind = FeedKind.WATCH_LATER,
                ),
                MenuDsl.feed(
                    id = "feed.liked",
                    labelKey = "TXT_MENU_MAIN_LIKED",
                    iconKey = "TXT_ICON_MENU_MAIN_LIKED",
                    kind = FeedKind.LIKED,
                ),
                MenuDsl.feed(
                    id = "feed.history",
                    labelKey = "TXT_MENU_MAIN_HISTORY",
                    iconKey = "TXT_ICON_MENU_MAIN_HISTORY",
                    kind = FeedKind.HISTORY,
                ),
                MenuDsl.feed(
                    id = "feed.clips",
                    labelKey = "TXT_ANDROID_MENU_CLIPS",
                    iconKey = "TXT_ICON_MENU_MAIN_CLIPS",
                    kind = FeedKind.CLIPS,
                ),
                MenuDsl.target(
                    id = "playlists",
                    labelKey = "TXT_MENU_MAIN_PLAYLISTS",
                    targetMenuId = MenuIds.PLAYLISTS,
                    iconKey = "TXT_ICON_MENU_MAIN_PLAYLISTS",
                ),
                MenuDsl.target(
                    id = "custom_playlists",
                    labelKey = "TXT_ANDROID_MENU_CUSTOM_PLAYLISTS",
                    targetMenuId = MenuIds.CUSTOM_PLAYLISTS,
                    iconKey = "TXT_ICON_MENU_MAIN_CUSTOM_PLAYLISTS",
                ),
                MenuDsl.library(
                    id = "library.saved",
                    labelKey = "TXT_ANDROID_MENU_SAVED",
                    iconKey = "TXT_ICON_MENU_MAIN_SAVED",
                    kind = LibraryKind.SAVED,
                ),
                MenuDsl.library(
                    id = "library.recent",
                    labelKey = "TXT_MENU_MAIN_MY_RECENTS",
                    iconKey = "TXT_ICON_MENU_MAIN_RECENT",
                    kind = LibraryKind.RECENT,
                ),
                MenuDsl.target(
                    id = "channels",
                    labelKey = "TXT_MENU_MAIN_CHANNELS",
                    targetMenuId = MenuIds.CHANNELS,
                    iconKey = "TXT_ICON_MENU_MAIN_CHANNELS",
                ),
                MenuDsl.target(
                    id = "misc",
                    labelKey = "TXT_MENU_MAIN_MISC",
                    targetMenuId = MenuIds.MISC,
                    iconKey = "TXT_ICON_MENU_MAIN_MISC",
                ),
                MenuDsl.target(
                    id = "config",
                    labelKey = "TXT_MENU_MAIN_EDIT_CONFIG",
                    targetMenuId = MenuIds.CONFIG,
                    iconKey = "TXT_ICON_MENU_MAIN_EDIT_CONFIG",
                ),
                MenuDsl.exit(),
            )
        },
    )

    private fun miscellaneous(): MenuDefinition = MenuDefinition(
        id = MenuIds.MISC,
        titleKey = "TXT_MENU_MAIN_MISC",
        build = { ctx ->
            listOf(
                MenuDsl.search(
                    id = "misc.explore_channels",
                    labelKey = "TXT_MENU_MISC_EXPLORE_CHANNELS",
                    iconKey = "TXT_ICON_MENU_MISC_EXPLORE_CHANNELS",
                    kind = SearchKind.CHANNELS,
                ),
                MenuDsl.search(
                    id = "misc.explore_playlists",
                    labelKey = "TXT_MENU_MISC_EXPLORE_PLAYLISTS",
                    iconKey = "TXT_ICON_MENU_MISC_EXPLORE_PLAYLISTS",
                    kind = SearchKind.PLAYLISTS,
                ),
                MenuDsl.search(
                    id = "misc.explore_shorts",
                    labelKey = "TXT_MENU_MISC_EXPLORE_SHORTS",
                    iconKey = "TXT_ICON_MENU_MISC_EXPLORE_SHORTS",
                    kind = SearchKind.SHORTS,
                ),
                MenuDsl.search(
                    id = "misc.explore_movies",
                    labelKey = "TXT_MENU_MISC_EXPLORE_MOVIES",
                    iconKey = "TXT_ICON_MENU_MISC_EXPLORE_MOVIES",
                    kind = SearchKind.MOVIES,
                ),
                MenuDsl.target(
                    id = "misc.search_history",
                    labelKey = "TXT_MENU_MISC_SEARCH_HISTORY",
                    targetMenuId = MenuIds.SEARCH_HISTORY,
                    iconKey = "TXT_ICON_MENU_MISC_SEARCH_HISTORY",
                ),
                MenuDsl.target(
                    id = "misc.custom_commands",
                    labelKey = "TXT_MENU_MISC_CUSTOM_CMDS",
                    targetMenuId = MenuIds.CUSTOM_COMMANDS,
                    iconKey = "TXT_ICON_MENU_MISC_CUSTOM_CMDS",
                    detail = if (ctx.extensions.commands.isEmpty()) {
                        null
                    } else {
                        ctx.extensions.commands.size.toString()
                    },
                ),
                MenuDsl.action(
                    id = "misc.new_custom_command",
                    labelKey = "TXT_MENU_MISC_NEW_CUSTOM_CMD",
                    iconKey = "TXT_ICON_MENU_MISC_NEW_CUSTOM_CMD",
                    handler = HandlerIds.CUSTOM_CMD_NEW,
                ),
                MenuDsl.target(
                    id = "misc.sites",
                    labelKey = "TXT_MENU_SITES",
                    targetMenuId = MenuIds.SITES,
                    icon = GLYPH_GLOBE,
                    detail = if (ctx.extensions.sites.isEmpty()) {
                        ctx.strings.text("TXT_ANDROID_EXTENSIONS_EMPTY_HINT")
                    } else {
                        ctx.extensions.sites.size.toString()
                    },
                ),
                MenuDsl.action(
                    id = "misc.sync_subs",
                    labelKey = "TXT_MENU_MISC_SYNC_SUBS",
                    iconKey = "TXT_ICON_MENU_MISC_SYNC_SUBS",
                    handler = HandlerIds.SUBS_SYNC,
                ),
                MenuDsl.target(
                    id = "misc.config",
                    labelKey = "TXT_MENU_MAIN_EDIT_CONFIG",
                    targetMenuId = MenuIds.CONFIG,
                    iconKey = "TXT_ICON_MENU_MISC_EDIT_MPV_CONFIG",
                ),
                MenuDsl.target(
                    id = "misc.edit_histories",
                    labelKey = "TXT_ANDROID_MENU_EDIT_HISTORIES",
                    targetMenuId = MenuIds.HISTORIES,
                    iconKey = "TXT_ICON_MENU_MISC_EDIT_SEARCH_HISTORY",
                ),
                MenuDsl.target(
                    id = "misc.downloads",
                    labelKey = "TXT_ANDROID_MENU_DOWNLOADS",
                    targetMenuId = MenuIds.DOWNLOADS,
                    iconKey = "TXT_ICON_MENU_MEDIA_ACTIONS_DOWNLOAD",
                ),
                MenuDsl.target(
                    id = "misc.update",
                    labelKey = "TXT_ANDROID_MENU_UPDATE",
                    targetMenuId = MenuIds.UPDATE,
                    icon = GLYPH_REFRESH,
                    detail = ctx.updates.latestVersion,
                ),
                MenuDsl.action(
                    id = "misc.about",
                    labelKey = "TXT_ANDROID_ABOUT",
                    icon = GLYPH_GEAR,
                    handler = HandlerIds.ABOUT,
                ),
                MenuDsl.back(),
                MenuDsl.exit(),
            )
        },
    )

    private fun config(): MenuDefinition = MenuDefinition(
        id = MenuIds.CONFIG,
        titleKey = "TXT_ANDROID_MENU_CONFIG_TOGGLES",
        build = { ctx -> buildConfigMenu(ctx) },
    )

    /**
     * Radio-style choices share one menu id and differ by `MenuPayload.ConfigValue`.
     * Resolving from the catalog (instead of pushing an inline `children` list) means
     * the selected row is recomputed from the live config every time the frame is
     * drawn, so a checkmark can never go stale.
     */
    private fun choice(): MenuDefinition = MenuDefinition(
        id = MenuIds.CHOICE,
        build = { ctx ->
            when ((ctx.payload as? MenuPayload.ConfigValue)?.key) {
                ConfigKeys.THEME -> themeChoices(ctx)
                ConfigKeys.LANG -> languageChoices(ctx)
                ConfigKeys.PER_PAGE -> pageChoices(ctx)
                ConfigKeys.CACHE_RETENTION_DAYS -> retentionChoices(ctx)
                ConfigKeys.DOWNLOAD_RETRIES -> retryChoices(ctx)
                ConfigKeys.EXTRACTOR -> extractorChoices(ctx)
                ConfigKeys.YTDLP_CHANNEL -> channelChoices(ctx, YtdlpSupport.updateChannels())
                ConfigKeys.UPDATE_CHANNEL -> updateChannelChoices(ctx)
                else -> emptyList()
            }
        },
    )

    private fun buildConfigMenu(ctx: MenuContext): List<MenuNode> {
        val perPage = ctx.config.int(ConfigKeys.PER_PAGE, 30)
        val currentTheme = ctx.config.string(ConfigKeys.THEME, ThemeSpec.DEFAULT_ID)
        val currentLang = ctx.config.string(ConfigKeys.LANG, "auto")
        val themeName = ctx.themes.firstOrNull { it.id == currentTheme }?.name ?: currentTheme
        val langName = if (currentLang.equals("auto", ignoreCase = true)) {
            ctx.strings.text("TXT_ANDROID_LANG_AUTO")
        } else {
            ctx.languages.firstOrNull { it.tag.equals(currentLang, ignoreCase = true) }?.name ?: currentLang
        }

        return listOf(
            MenuDsl.toggle(
                id = "cfg.preview",
                labelKey = "TXT_ANDROID_CFG_PREVIEW",
                configKey = ConfigKeys.ENABLE_PREVIEW,
                handler = HandlerIds.TOGGLE_CONFIG,
            ),
            MenuDsl.toggle(
                id = "cfg.preview_images",
                labelKey = "TXT_ANDROID_CFG_PREVIEW_IMAGES",
                configKey = ConfigKeys.ENABLE_PREVIEW_IMAGES,
                handler = HandlerIds.TOGGLE_CONFIG,
            ),
            MenuDsl.toggle(
                id = "cfg.nerd_fonts",
                labelKey = "TXT_ANDROID_CFG_NERD_FONTS",
                configKey = ConfigKeys.NERD_FONTS,
                handler = HandlerIds.TOGGLE_CONFIG,
            ).let { row ->
                // No patched font in the APK means the glyphs would render as tofu, so
                // the switch is shown disabled with an explanation instead of claiming
                // an icon set the app cannot draw.
                if (ctx.nerdFontAvailable) {
                    row
                } else {
                    row.copy(
                        enabled = false,
                        detailKey = "TXT_ANDROID_CFG_NERD_FONTS_MISSING",
                    )
                }
            },
            choiceTarget(
                id = "cfg.theme",
                labelKey = "TXT_ANDROID_CFG_THEME",
                configKey = ConfigKeys.THEME,
                icon = GLYPH_GEAR,
                detail = themeName,
            ),
            choiceTarget(
                id = "cfg.language",
                labelKey = "TXT_ANDROID_CFG_LANGUAGE",
                configKey = ConfigKeys.LANG,
                icon = GLYPH_GLOBE,
                detail = langName,
            ),
            choiceTarget(
                id = "cfg.per_page",
                labelKey = "TXT_ANDROID_CFG_PER_PAGE",
                configKey = ConfigKeys.PER_PAGE,
                detail = perPage.toString(),
            ),

            MenuDsl.toggle(
                id = "cfg.enumerate",
                labelKey = "TXT_ANDROID_CFG_ENUMERATE",
                configKey = ConfigKeys.DOWNLOADS_ENUMERATE,
                handler = HandlerIds.TOGGLE_CONFIG,
            ),
            MenuDsl.toggle(
                id = "cfg.wifi_only",
                labelKey = "TXT_ANDROID_CFG_WIFI_ONLY",
                configKey = ConfigKeys.DOWNLOAD_WIFI_ONLY,
                handler = HandlerIds.TOGGLE_CONFIG,
            ),
            MenuDsl.toggle(
                id = "cfg.download_archive",
                labelKey = "TXT_ANDROID_CFG_DOWNLOAD_ARCHIVE",
                configKey = ConfigKeys.DOWNLOAD_ARCHIVE,
                handler = HandlerIds.TOGGLE_CONFIG,
            ),
            choiceTarget(
                id = "cfg.download_retries",
                labelKey = "TXT_ANDROID_CFG_DOWNLOAD_RETRIES",
                configKey = ConfigKeys.DOWNLOAD_RETRIES,
                detail = ctx.config
                    .int(ConfigKeys.DOWNLOAD_RETRIES, RangedDownloader.DEFAULT_ATTEMPTS)
                    .toString(),
            ),
            MenuDsl.toggle(
                id = "cfg.sponsorblock",
                labelKey = "TXT_ANDROID_CFG_SPONSORBLOCK",
                configKey = ConfigKeys.SPONSORBLOCK,
                handler = HandlerIds.TOGGLE_CONFIG,
            ),
            MenuDsl.toggle(
                id = "cfg.account_feeds",
                labelKey = "TXT_ANDROID_CFG_ACCOUNT_FEEDS",
                configKey = ConfigKeys.ACCOUNT_FEEDS,
                handler = HandlerIds.TOGGLE_CONFIG,
                detail = ctx.strings.text("TXT_ANDROID_CFG_ACCOUNT_FEEDS_HINT"),
            ),
            MenuDsl.toggle(
                id = "cfg.search_history",
                labelKey = "TXT_ANDROID_CFG_SEARCH_HISTORY",
                configKey = ConfigKeys.ENABLE_SEARCH_HISTORY,
                handler = HandlerIds.TOGGLE_CONFIG,
            ),
            MenuDsl.action(
                id = "cfg.download_dir",
                labelKey = "TXT_ANDROID_CFG_DOWNLOAD_DIR",
                icon = Glyphs.FOLDER,
                handler = HandlerIds.DOWNLOADS_PICK_FOLDER,
                detail = ctx.downloads.folderLabel
                    .ifBlank { DownloadNaming.DEFAULT_FOLDER },
            ),
            MenuDsl.action(
                id = "cfg.download_dir_path",
                labelKey = "TXT_ANDROID_CFG_DOWNLOAD_DIR_PATH",
                icon = Glyphs.PENCIL,
                handler = HandlerIds.DOWNLOADS_SET_FOLDER,
                detail = ctx.strings.text("TXT_ANDROID_CFG_DOWNLOAD_DIR_PATH_HINT"),
            ),
            MenuDsl.target(
                id = "cfg.extensions",
                labelKey = "TXT_ANDROID_CFG_EXTENSIONS",
                targetMenuId = MenuIds.EXTENSIONS,
                icon = Glyphs.FOLDER,
                detail = when {
                    !ctx.extensions.enabled -> ctx.strings.text("TXT_ANDROID_DISABLED")
                    !ctx.extensions.hasFolder -> ctx.strings.text("TXT_ANDROID_EXTENSIONS_NONE")
                    else -> ctx.extensions.folderLabel
                },
            ),
            MenuDsl.target(
                id = "cfg.cookies",
                labelKey = "TXT_ANDROID_CFG_COOKIES",
                targetMenuId = MenuIds.COOKIES,
                icon = GLYPH_GEAR,
                detail = if (ctx.config.bool(ConfigKeys.COOKIES_ENABLED, false)) {
                    ctx.strings.text("TXT_ANDROID_ENABLED")
                } else {
                    ctx.strings.text("TXT_ANDROID_DISABLED")
                },
            ),
            MenuDsl.target(
                id = "cfg.extractor",
                labelKey = "TXT_ANDROID_CFG_EXTRACTOR",
                targetMenuId = MenuIds.EXTRACTOR,
                icon = GLYPH_REFRESH,
                detail = extractorLabel(ctx),
            ),
            choiceTarget(
                id = "cfg.cache_retention",
                labelKey = "TXT_ANDROID_CFG_CACHE_RETENTION",
                configKey = ConfigKeys.CACHE_RETENTION_DAYS,
                detail = retentionLabel(ctx),
            ),
            MenuDsl.action(
                id = "cfg.clear_cache",
                labelKey = "TXT_ANDROID_CFG_CLEAR_CACHE",
                icon = Glyphs.TRASH,
                handler = HandlerIds.CACHE_CLEAR,
            ),
            MenuDsl.toggle(
                id = "cfg.check_updates",
                labelKey = "TXT_ANDROID_CFG_CHECK_UPDATES",
                configKey = ConfigKeys.CHECK_FOR_UPDATES,
                handler = HandlerIds.TOGGLE_CONFIG,
            ),
            choiceTarget(
                id = "cfg.update_channel",
                labelKey = "TXT_ANDROID_CFG_UPDATE_CHANNEL",
                configKey = ConfigKeys.UPDATE_CHANNEL,
                detail = ctx.config.string(ConfigKeys.UPDATE_CHANNEL, "stable"),
            ),
            MenuDsl.action(
                id = "cfg.open_config",
                labelKey = "TXT_ANDROID_CFG_EDITOR",
                iconKey = "TXT_ICON_MENU_MISC_EDIT_MPV_CONFIG",
                handler = HandlerIds.CONFIG_OPEN_EDITOR,
                detail = "files/config",
            ),
            MenuDsl.action(
                id = "cfg.open_config_external",
                labelKey = "TXT_ANDROID_CFG_EDITOR_EXTERNAL",
                iconKey = "TXT_ICON_MENU_MISC_EDIT_MPV_CONFIG",
                handler = HandlerIds.CONFIG_OPEN_EXTERNAL,
                detail = "files/config",
            ),
            MenuDsl.back(),
        )
    }

    private fun extractorLabel(ctx: MenuContext): String =
        when (ctx.config.string(ConfigKeys.EXTRACTOR, "auto").lowercase()) {
            "newpipe", "fast" -> "NewPipeExtractor"
            "ytdlp" -> "yt-dlp"
            else -> ctx.strings.text("TXT_ANDROID_EXTRACTOR_AUTO")
        }

    private fun retentionLabel(ctx: MenuContext): String =
        daysLabel(ctx, ctx.config.int(ConfigKeys.CACHE_RETENTION_DAYS, 3))

    private fun daysLabel(ctx: MenuContext, days: Int): String = when {
        days <= 0 -> ctx.strings.text("TXT_ANDROID_OFF")
        days == 1 -> ctx.strings.text("TXT_ANDROID_CFG_ONE_DAY")
        else -> ctx.strings.format("TXT_ANDROID_CFG_DAYS_FMT", days)
    }

    // -- local library menus --------------------------------------------------------

    /**
     * Search history rows. Each one re-enters the search screen with its query, which is
     * upstream's "Search for" prompt already answered. The list is a catalog menu (not a
     * data screen) because queries live in the library snapshot: a new search appears here
     * without a reload.
     */
    private fun searchHistory(): MenuDefinition = MenuDefinition(
        id = MenuIds.SEARCH_HISTORY,
        titleKey = "TXT_MENU_MISC_SEARCH_HISTORY",
        build = { ctx ->
            buildList {
                ctx.library.searchHistory.forEachIndexed { index, query ->
                    add(
                        MenuNode(
                            id = "history.$index",
                            label = query,
                            icon = Glyphs.SEARCH,
                            targetMenuId = MenuIds.SEARCH,
                            payload = MenuPayload.Search(query),
                        ),
                    )
                }
                if (ctx.library.searchHistory.isNotEmpty()) {
                    add(
                        MenuDsl.action(
                            id = "history.clear",
                            labelKey = "TXT_MENU_MISC_CLEAR_SEARCH_HISTORY",
                            icon = Glyphs.TRASH,
                            handler = HandlerIds.HISTORY_CLEAR_SEARCH,
                            tone = MenuTone.DESTRUCTIVE,
                        ),
                    )
                }
                add(MenuDsl.back())
                add(MenuDsl.exit())
            }
        },
    )

    /** Bulk clears for the two histories the app keeps. */
    private fun histories(): MenuDefinition = MenuDefinition(
        id = MenuIds.HISTORIES,
        titleKey = "TXT_ANDROID_MENU_EDIT_HISTORIES",
        build = { ctx ->
            buildList {
                add(
                    MenuDsl.action(
                        id = "histories.clear_search",
                        labelKey = "TXT_MENU_MISC_CLEAR_SEARCH_HISTORY",
                        icon = Glyphs.HISTORY,
                        handler = HandlerIds.HISTORY_CLEAR_SEARCH,
                        tone = MenuTone.DESTRUCTIVE,
                    ),
                )
                add(
                    MenuDsl.action(
                        id = "histories.clear_recent",
                        labelKey = "TXT_ANDROID_CLEAR_RECENT",
                        icon = Glyphs.TRASH,
                        handler = HandlerIds.HISTORY_CLEAR_RECENT,
                        tone = MenuTone.DESTRUCTIVE,
                    ),
                )
                add(MenuDsl.back())
                add(MenuDsl.exit())
            }
        },
    )

    private fun customPlaylists(): MenuDefinition = MenuDefinition(
        id = MenuIds.CUSTOM_PLAYLISTS,
        titleKey = "TXT_ANDROID_MENU_CUSTOM_PLAYLISTS",
        build = { ctx ->
            buildList {
                add(
                    MenuDsl.action(
                        id = "playlists.new",
                        labelKey = "TXT_ANDROID_PLAYLIST_NEW",
                        icon = Glyphs.PLUS,
                        handler = HandlerIds.PLAYLIST_CREATE,
                        tone = MenuTone.ACCENT,
                    ),
                )
                ctx.library.playlists.forEach { playlist ->
                    add(
                        MenuNode(
                            id = "playlist.${playlist.id}",
                            label = playlist.title,
                            detail = countLabel(ctx, playlist.itemCount),
                            icon = Glyphs.LIST,
                            targetMenuId = MenuIds.LOCAL_PLAYLIST_ACTIONS,
                            payload = MenuPayload.LocalPlaylist(playlist.id, playlist.title),
                        ),
                    )
                }
                add(MenuDsl.back())
                add(MenuDsl.exit())
            }
        },
    )

    private fun localPlaylistActions(): MenuDefinition = MenuDefinition(
        id = MenuIds.LOCAL_PLAYLIST_ACTIONS,
        // No catalog title: the frame is titled after the playlist it acts on, which is
        // the row label when opened from the list and the payload title from the handler.
        titleKey = null,
        build = { ctx ->
            val playlist = ctx.payload as? MenuPayload.LocalPlaylist ?: return@MenuDefinition emptyList()
            listOf(
                MenuNode(
                    id = "local_playlist.open",
                    labelKey = "TXT_ANDROID_PLAYLIST_OPEN",
                    icon = Glyphs.FOLDER,
                    targetMenuId = MenuIds.LOCAL_PLAYLIST,
                    payload = playlist,
                    tone = MenuTone.ACCENT,
                ),
                MenuDsl.action(
                    id = "local_playlist.rename",
                    labelKey = "TXT_ANDROID_PLAYLIST_RENAME",
                    icon = Glyphs.PENCIL,
                    handler = HandlerIds.PLAYLIST_RENAME,
                    payload = playlist,
                ),
                MenuDsl.action(
                    id = "local_playlist.delete",
                    labelKey = "TXT_ANDROID_PLAYLIST_DELETE",
                    icon = Glyphs.TRASH,
                    handler = HandlerIds.PLAYLIST_DELETE,
                    payload = playlist,
                    tone = MenuTone.DESTRUCTIVE,
                ),
                MenuDsl.back(),
                MenuDsl.exit(),
            )
        },
    )

    /** Saved remote playlists, plus the account's own playlists behind the same entry. */
    private fun savedPlaylists(): MenuDefinition = MenuDefinition(
        id = MenuIds.PLAYLISTS,
        titleKey = "TXT_MENU_MAIN_PLAYLISTS",
        build = { ctx ->
            buildList {
                add(
                    MenuDsl.target(
                        id = "playlists.account",
                        labelKey = "TXT_ANDROID_MENU_YOUR_PLAYLISTS",
                        targetMenuId = MenuIds.FEED,
                        iconKey = "TXT_ICON_MENU_MAIN_PLAYLISTS",
                        payload = MenuPayload.Feed(FeedKind.PLAYLISTS),
                    ),
                )
                ctx.library.savedPlaylists.forEachIndexed { index, playlist ->
                    add(
                        MenuNode(
                            id = "saved_playlist.$index",
                            label = playlist.title,
                            detail = playlist.channelName,
                            iconKey = "TXT_ICON_MENU_MAIN_PLAYLISTS",
                            handler = HandlerIds.OPEN_ACTIONS,
                            payload = MenuPayload.Playlist(playlist),
                        ),
                    )
                }
                add(MenuDsl.back())
                add(MenuDsl.exit())
            }
        },
    )

    /** Locally subscribed channels. */
    private fun channels(): MenuDefinition = MenuDefinition(
        id = MenuIds.CHANNELS,
        titleKey = "TXT_MENU_MAIN_CHANNELS",
        build = { ctx ->
            buildList {
                ctx.library.subscriptions.forEachIndexed { index, channel ->
                    add(
                        MenuNode(
                            id = "subscription.$index",
                            label = channel.name,
                            iconKey = "TXT_ICON_MENU_MAIN_CHANNELS",
                            handler = HandlerIds.OPEN_ACTIONS,
                            payload = MenuPayload.Channel(channel.copy(isSubscribed = true)),
                        ),
                    )
                }
                add(MenuDsl.back())
                add(MenuDsl.exit())
            }
        },
    )

    /**
     * Playlist picker behind "Add to playlist". Creating a playlist from here carries the
     * media item so the new playlist already contains it.
     */
    private fun addToPlaylist(): MenuDefinition = MenuDefinition(
        id = MenuIds.ADD_TO_PLAYLIST,
        titleKey = "TXT_ANDROID_PLAYLIST_ADD",
        build = { ctx ->
            val media = ctx.payload as? MenuPayload.Media ?: return@MenuDefinition emptyList()
            buildList {
                add(
                    MenuDsl.action(
                        id = "add_to_playlist.new",
                        labelKey = "TXT_ANDROID_PLAYLIST_NEW",
                        icon = Glyphs.PLUS,
                        handler = HandlerIds.PLAYLIST_CREATE,
                        payload = media,
                        tone = MenuTone.ACCENT,
                    ),
                )
                ctx.library.playlists.forEach { playlist ->
                    add(
                        MenuNode(
                            id = "add_to_playlist.${playlist.id}",
                            label = playlist.title,
                            icon = Glyphs.LIST,
                            handler = HandlerIds.PLAYLIST_ADD_ITEM,
                            payload = media,
                            value = playlist.id.toString(),
                            detail = countLabel(ctx, playlist.itemCount),
                        ),
                    )
                }
                add(MenuDsl.back())
                add(MenuDsl.exit())
            }
        },
    )

    // -- custom commands and sites (F7) ---------------------------------------------

    private fun customCommands(): MenuDefinition = MenuDefinition(
        id = MenuIds.CUSTOM_COMMANDS,
        titleKey = "TXT_MENU_MISC_CUSTOM_CMDS",
        build = { ctx ->
            buildList {
                add(
                    MenuDsl.action(
                        id = "commands.new",
                        labelKey = "TXT_MENU_MISC_NEW_CUSTOM_CMD",
                        icon = Glyphs.PLUS,
                        handler = HandlerIds.CUSTOM_CMD_NEW,
                        tone = MenuTone.ACCENT,
                    ),
                )
                ctx.extensions.commands.forEach { command ->
                    add(
                        MenuNode(
                            id = "commands.${command.id}",
                            label = command.name,
                            detail = when (command.source) {
                                MenuCommandSource.LOCAL -> command.url
                                MenuCommandSource.EXTENSION -> ctx.strings.text("TXT_ANDROID_EXTENSION")
                            },
                            icon = GLYPH_GLOBE,
                            targetMenuId = MenuIds.CUSTOM_COMMAND_ACTIONS,
                            payload = MenuPayload.Command(command.id),
                        ),
                    )
                }
                if (ctx.extensions.commands.isEmpty()) {
                    add(
                        MenuDsl.info(
                            id = "commands.empty",
                            labelKey = "TXT_ANDROID_COMMANDS_EMPTY",
                            detail = ctx.strings.text("TXT_ANDROID_COMMANDS_EMPTY_HINT"),
                        ),
                    )
                }
                add(MenuDsl.back())
                add(MenuDsl.exit())
            }
        },
    )

    /**
     * Actions for one command. Extension-provided commands are read-only: their file is the
     * source of truth, so editing them in the app would be overwritten by the next reload.
     */
    private fun customCommandActions(): MenuDefinition = MenuDefinition(
        id = MenuIds.CUSTOM_COMMAND_ACTIONS,
        titleKey = null,
        build = { ctx ->
            val command = resolveCommand(ctx)
            if (command == null) {
                listOf(
                    MenuDsl.info(
                        id = "commands.missing",
                        labelKey = "TXT_ANDROID_COMMAND_MISSING",
                        detail = ctx.strings.text("TXT_ANDROID_COMMANDS_EMPTY_HINT"),
                    ),
                    MenuDsl.back(),
                    MenuDsl.exit(),
                )
            } else {
                buildList {
                    add(
                        MenuDsl.action(
                            id = "commands.run",
                            labelKey = "TXT_ANDROID_COMMAND_RUN",
                            icon = Glyphs.PLAY,
                            handler = HandlerIds.CUSTOM_CMD_RUN,
                            payload = MenuPayload.Command(command.id),
                            tone = MenuTone.ACCENT,
                        ),
                    )
                    add(
                        MenuDsl.action(
                            id = "commands.shell",
                            labelKey = "TXT_ANDROID_COMMAND_SHELL",
                            icon = Glyphs.REFRESH,
                            handler = HandlerIds.CUSTOM_CMD_SHELL,
                            payload = MenuPayload.Command(command.id),
                            detail = command.options.takeIf { it.isNotBlank() },
                        ),
                    )
                    add(
                        MenuDsl.info(
                            id = "commands.url",
                            labelKey = "TXT_ANDROID_COMMAND_URL",
                            detail = command.url,
                        ),
                    )
                    if (command.source == MenuCommandSource.LOCAL) {
                        add(
                            MenuDsl.action(
                                id = "commands.edit",
                                labelKey = "TXT_ANDROID_COMMAND_EDIT",
                                icon = Glyphs.PENCIL,
                                handler = HandlerIds.CUSTOM_CMD_EDIT,
                                payload = MenuPayload.Command(command.id),
                            ),
                        )
                        add(
                            MenuDsl.action(
                                id = "commands.delete",
                                labelKey = "TXT_ANDROID_COMMAND_DELETE",
                                icon = Glyphs.TRASH,
                                handler = HandlerIds.CUSTOM_CMD_DELETE,
                                payload = MenuPayload.Command(command.id),
                                tone = MenuTone.DESTRUCTIVE,
                            ),
                        )
                    }
                    add(MenuDsl.back())
                    add(MenuDsl.exit())
                }
            }
        },
    )

    private fun sites(): MenuDefinition = MenuDefinition(
        id = MenuIds.SITES,
        titleKey = "TXT_MENU_SITES",
        build = { ctx ->
            buildList {
                ctx.extensions.sites.forEach { site ->
                    site.actions.forEach { action ->
                        add(
                            MenuNode(
                                id = "sites.${site.id}.${action.id}",
                                label = "${site.name} · ${action.label}",
                                detail = action.prompt ?: action.urlTemplate,
                                icon = site.icon ?: GLYPH_GLOBE,
                                handler = HandlerIds.SITE_RUN,
                                payload = MenuPayload.SiteActionRef(site.id, action.id),
                            ),
                        )
                    }
                }
                if (ctx.extensions.sites.isEmpty()) {
                    add(
                        MenuDsl.info(
                            id = "sites.empty",
                            labelKey = "TXT_ANDROID_SITES_EMPTY",
                            detail = ctx.strings.text("TXT_ANDROID_SITES_EMPTY_HINT"),
                        ),
                    )
                }
                add(MenuDsl.back())
                add(MenuDsl.exit())
            }
        },
    )

    // -- settings screens (F7) ------------------------------------------------------

    private fun extensionsSettings(): MenuDefinition = MenuDefinition(
        id = MenuIds.EXTENSIONS,
        titleKey = "TXT_ANDROID_CFG_EXTENSIONS",
        build = { ctx ->
            buildList {
                add(
                    MenuDsl.toggle(
                        id = "extensions.enabled",
                        labelKey = "TXT_ANDROID_EXTENSIONS_ENABLED",
                        configKey = ConfigKeys.EXTENSIONS_ENABLED,
                        handler = HandlerIds.EXTENSIONS_TOGGLE,
                        detail = ctx.strings.text("TXT_ANDROID_EXTENSIONS_ENABLED_HINT"),
                    ),
                )
                add(
                    MenuDsl.action(
                        id = "extensions.pick",
                        labelKey = "TXT_ANDROID_EXTENSIONS_PICK",
                        icon = Glyphs.FOLDER,
                        handler = HandlerIds.EXTENSIONS_PICK,
                        detail = ctx.extensions.folderLabel
                            .ifBlank { ctx.strings.text("TXT_ANDROID_EXTENSIONS_NONE") },
                    ),
                )
                add(
                    MenuDsl.action(
                        id = "extensions.autoload",
                        labelKey = "TXT_ANDROID_EXTENSIONS_AUTOLOAD",
                        icon = Glyphs.LIST,
                        handler = HandlerIds.EXTENSIONS_AUTOLOAD,
                        detail = ctx.extensions.autoloaded
                            .ifBlank { ctx.strings.text("TXT_ANDROID_EXTENSIONS_AUTOLOAD_ALL") },
                    ),
                )
                add(
                    MenuDsl.action(
                        id = "extensions.reload",
                        labelKey = "TXT_ANDROID_EXTENSIONS_RELOAD",
                        icon = Glyphs.REFRESH,
                        handler = HandlerIds.EXTENSIONS_RELOAD,
                        detail = if (ctx.extensions.loadedCount == 1) {
                            ctx.strings.text("TXT_ANDROID_EXTENSIONS_LOADED_ONE")
                        } else {
                            ctx.strings.format(
                                "TXT_ANDROID_EXTENSIONS_LOADED_FMT",
                                ctx.extensions.loadedCount,
                            )
                        },
                    ),
                )
                add(
                    MenuDsl.action(
                        id = "extensions.clear",
                        labelKey = "TXT_ANDROID_EXTENSIONS_CLEAR",
                        icon = Glyphs.TRASH,
                        handler = HandlerIds.EXTENSIONS_CLEAR,
                        enabled = ctx.extensions.hasFolder,
                        tone = MenuTone.DESTRUCTIVE,
                    ),
                )
                ctx.extensions.entries.forEachIndexed { index, entry ->
                    add(
                        MenuNode(
                            id = "extensions.entry.$index",
                            label = entry.name,
                            detail = listOf(entry.kind, entry.detail)
                                .filter { it.isNotBlank() }
                                .joinToString(" · "),
                            kind = MenuNodeKind.INFO,
                            enabled = false,
                        ),
                    )
                }
                ctx.extensions.issues.forEachIndexed { index, issue ->
                    add(
                        MenuNode(
                            id = "extensions.issue.$index",
                            labelKey = "TXT_ANDROID_EXTENSION_ISSUE",
                            detail = issue,
                            kind = MenuNodeKind.INFO,
                            enabled = false,
                            tone = MenuTone.MUTED,
                        ),
                    )
                }
                add(MenuDsl.back())
                add(MenuDsl.exit())
            }
        },
    )

    private fun cookiesSettings(): MenuDefinition = MenuDefinition(
        id = MenuIds.COOKIES,
        titleKey = "TXT_ANDROID_CFG_COOKIES",
        build = { ctx ->
            listOf(
                MenuDsl.toggle(
                    id = "cookies.enabled",
                    labelKey = "TXT_ANDROID_COOKIES_ENABLED",
                    configKey = ConfigKeys.COOKIES_ENABLED,
                    handler = HandlerIds.TOGGLE_CONFIG,
                    detail = ctx.strings.text("TXT_ANDROID_COOKIES_ENABLED_HINT"),
                ),
                MenuDsl.action(
                    id = "cookies.import",
                    labelKey = "TXT_ANDROID_COOKIES_IMPORT",
                    icon = Glyphs.FOLDER,
                    handler = HandlerIds.COOKIES_IMPORT,
                ),
                MenuDsl.action(
                    id = "cookies.login",
                    labelKey = "TXT_ANDROID_COOKIES_LOGIN",
                    icon = GLYPH_GLOBE,
                    handler = HandlerIds.COOKIES_LOGIN,
                ),
                MenuDsl.action(
                    id = "cookies.clear",
                    labelKey = "TXT_ANDROID_COOKIES_CLEAR",
                    icon = Glyphs.TRASH,
                    handler = HandlerIds.COOKIES_CLEAR,
                    tone = MenuTone.DESTRUCTIVE,
                ),
                MenuDsl.info(
                    id = "cookies.storage",
                    labelKey = "TXT_ANDROID_COOKIES_STORAGE",
                    detail = ctx.strings.text("TXT_ANDROID_COOKIES_STORAGE_HINT"),
                ),
                MenuDsl.info(
                    id = "cookies.scope",
                    labelKey = "TXT_ANDROID_COOKIES_SCOPE",
                    detail = ctx.strings.text("TXT_ANDROID_COOKIES_SCOPE_HINT"),
                ),
                MenuDsl.back(),
                MenuDsl.exit(),
            )
        },
    )

    private fun extractorSettings(): MenuDefinition = MenuDefinition(
        id = MenuIds.EXTRACTOR,
        titleKey = "TXT_ANDROID_CFG_EXTRACTOR",
        build = { ctx ->
            buildList {
                add(
                    choiceTarget(
                        id = "extractor.backend",
                        labelKey = "TXT_ANDROID_CFG_EXTRACTOR",
                        configKey = ConfigKeys.EXTRACTOR,
                        detail = extractorLabel(ctx),
                    ),
                )
                add(
                    MenuNode(
                        id = "extractor.version",
                        labelKey = "TXT_ANDROID_EXTRACTOR_VERSION",
                        detail = if (YtdlpSupport.BUNDLED) {
                            ctx.strings.text("TXT_ANDROID_EXTRACTOR_BUNDLED")
                        } else {
                            ctx.strings.text("TXT_ANDROID_EXTRACTOR_FAST_ONLY")
                        },
                        kind = MenuNodeKind.INFO,
                        enabled = false,
                    ),
                )
                if (YtdlpSupport.BUNDLED) {
                    add(
                        choiceTarget(
                            id = "extractor.channel",
                            labelKey = "TXT_ANDROID_EXTRACTOR_CHANNEL",
                            configKey = ConfigKeys.YTDLP_CHANNEL,
                            detail = ctx.config.string(ConfigKeys.YTDLP_CHANNEL, "stable"),
                        ),
                    )
                    add(
                        MenuDsl.action(
                            id = "extractor.update",
                            labelKey = "TXT_ANDROID_EXTRACTOR_UPDATE",
                            icon = Glyphs.REFRESH,
                            handler = HandlerIds.YTDLP_UPDATE,
                            detail = ctx.strings.text("TXT_ANDROID_EXTRACTOR_UPDATE_HINT"),
                        ),
                    )
                }
                add(MenuDsl.back())
                add(MenuDsl.exit())
            }
        },
    )

    private fun updateScreen(): MenuDefinition = MenuDefinition(
        id = MenuIds.UPDATE,
        titleKey = "TXT_ANDROID_MENU_UPDATE",
        build = { ctx ->
            val updates = ctx.updates
            buildList {
                when (updates.state) {
                    MenuUpdateState.CHECKING -> add(
                        MenuDsl.info(
                            id = "update.checking",
                            labelKey = "TXT_ANDROID_UPDATE_CHECKING",
                            detail = updates.currentVersion,
                        ),
                    )

                    MenuUpdateState.AVAILABLE -> {
                        add(
                            MenuDsl.info(
                                id = "update.available",
                                labelKey = "TXT_ANDROID_UPDATE_AVAILABLE",
                                detail = updates.latestVersion.orEmpty(),
                            ),
                        )
                        updates.releaseName?.let { name ->
                            add(
                                MenuDsl.info(
                                    id = "update.name",
                                    labelKey = "TXT_ANDROID_UPDATE_RELEASE",
                                    detail = name,
                                ),
                            )
                        }
                        updates.publishedAt?.let { date ->
                            add(
                                MenuDsl.info(
                                    id = "update.date",
                                    labelKey = "TXT_ANDROID_UPDATE_PUBLISHED",
                                    detail = date.take(10),
                                ),
                            )
                        }
                        updates.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                            add(
                                MenuDsl.info(
                                    id = "update.notes",
                                    labelKey = "TXT_ANDROID_UPDATE_NOTES",
                                    detail = notes.take(1_200),
                                ),
                            )
                        }
                        updates.url?.takeIf { it.startsWith("http") }?.let { url ->
                            add(
                                MenuDsl.action(
                                    id = "update.open",
                                    labelKey = "TXT_ANDROID_UPDATE_OPEN",
                                    icon = GLYPH_GLOBE,
                                    handler = HandlerIds.OPEN_URL,
                                    payload = MenuPayload.Url(url),
                                ),
                            )
                        }
                    }

                    MenuUpdateState.CURRENT -> add(
                        MenuDsl.info(
                            id = "update.current",
                            labelKey = "TXT_ANDROID_UPDATE_CURRENT",
                            detail = updates.latestVersion ?: updates.currentVersion,
                        ),
                    )

                    MenuUpdateState.FAILED -> add(
                        MenuDsl.info(
                            id = "update.failed",
                            labelKey = "TXT_ANDROID_UPDATE_FAILED",
                            detail = updates.message.orEmpty(),
                        ),
                    )

                    MenuUpdateState.IDLE -> add(
                        MenuDsl.info(
                            id = "update.idle",
                            labelKey = "TXT_ANDROID_UPDATE_IDLE",
                            detail = updates.currentVersion,
                        ),
                    )
                }
                add(
                    MenuDsl.action(
                        id = "update.check",
                        labelKey = "TXT_ANDROID_UPDATE_CHECK",
                        icon = Glyphs.REFRESH,
                        handler = HandlerIds.UPDATE_CHECK,
                    ),
                )
                add(MenuDsl.back())
                add(MenuDsl.exit())
            }
        },
    )

    private fun resolveCommand(ctx: MenuContext): MenuCommand? = when (val payload = ctx.payload) {
        is MenuPayload.Command -> ctx.extensions.findCommand(payload.id)
        is MenuPayload.Text -> ctx.extensions.commands.firstOrNull {
            it.id == payload.value || it.name.equals(payload.value, ignoreCase = true)
        }

        is MenuPayload.CommandDraft -> payload.id?.let(ctx.extensions::findCommand)
        else -> null
    }

    private fun retentionChoices(ctx: MenuContext): List<MenuNode> {
        val current = ctx.config.int(ConfigKeys.CACHE_RETENTION_DAYS, 3)
        return RETENTION_PRESETS.map { days ->
            val selected = days == current
            MenuNode(
                id = "retention.$days",
                label = daysLabel(ctx, days),
                value = days.toString(),
                handler = HandlerIds.SELECT_CACHE,
                payload = MenuPayload.ConfigValue(ConfigKeys.CACHE_RETENTION_DAYS),
                checked = selected,
                tone = if (selected) MenuTone.ACCENT else MenuTone.NORMAL,
            )
        }
    }

    private fun retryChoices(ctx: MenuContext): List<MenuNode> {
        val current = ctx.config.int(ConfigKeys.DOWNLOAD_RETRIES, RangedDownloader.DEFAULT_ATTEMPTS)
        return RETRY_PRESETS.map { preset ->
            val selected = preset == current
            MenuNode(
                id = "retry.$preset",
                label = preset.toString(),
                detail = if (selected) ctx.strings.text("TXT_ANDROID_ACTIVE") else null,
                value = preset.toString(),
                handler = HandlerIds.SELECT_CONFIG,
                payload = MenuPayload.ConfigValue(ConfigKeys.DOWNLOAD_RETRIES),
                checked = selected,
                tone = if (selected) MenuTone.ACCENT else MenuTone.NORMAL,
            )
        }
    }

    private fun extractorChoices(ctx: MenuContext): List<MenuNode> {
        val current = ctx.config.string(ConfigKeys.EXTRACTOR, "auto").lowercase()
        val rows = buildList {
            add(
                choice(
                    ctx = ctx,
                    id = "auto",
                    label = ctx.strings.text("TXT_ANDROID_EXTRACTOR_AUTO"),
                    detail = ctx.strings.text("TXT_ANDROID_EXTRACTOR_AUTO_HINT"),
                    current = current,
                ),
            )
            add(
                choice(
                    ctx = ctx,
                    id = "newpipe",
                    label = "NewPipeExtractor",
                    detail = ctx.strings.text("TXT_ANDROID_EXTRACTOR_NEWPIPE_HINT"),
                    current = current,
                ),
            )
            add(
                choice(
                    ctx = ctx,
                    id = "ytdlp",
                    label = "yt-dlp",
                    detail = if (YtdlpSupport.BUNDLED) {
                        ctx.strings.text("TXT_ANDROID_EXTRACTOR_YTDLP_HINT")
                    } else {
                        ctx.strings.text("TXT_ANDROID_EXTRACTOR_YTDLP_MISSING")
                    },
                    current = current,
                    enabled = YtdlpSupport.BUNDLED,
                ),
            )
        }
        return rows
    }

    private fun choice(
        ctx: MenuContext,
        id: String,
        label: String,
        detail: String?,
        current: String,
        enabled: Boolean = true,
    ): MenuNode {
        val selected = current == id
        return MenuNode(
            id = "extractor.$id",
            label = label,
            detail = detail,
            value = id,
            handler = HandlerIds.SELECT_CONFIG,
            payload = MenuPayload.ConfigValue(ConfigKeys.EXTRACTOR),
            checked = selected,
            enabled = enabled,
            tone = if (selected) MenuTone.ACCENT else MenuTone.NORMAL,
        )
    }

    private fun channelChoices(ctx: MenuContext, channels: List<String>): List<MenuNode> {
        val current = ctx.config.string(ConfigKeys.YTDLP_CHANNEL, "stable").lowercase()
        return channels.map { channel ->
            val selected = channel.equals(current, ignoreCase = true)
            MenuNode(
                id = "channel.$channel",
                label = channel,
                value = channel,
                handler = HandlerIds.SELECT_CONFIG,
                payload = MenuPayload.ConfigValue(ConfigKeys.YTDLP_CHANNEL),
                checked = selected,
                tone = if (selected) MenuTone.ACCENT else MenuTone.NORMAL,
            )
        }
    }

    private fun updateChannelChoices(ctx: MenuContext): List<MenuNode> {
        val current = ctx.config.string(ConfigKeys.UPDATE_CHANNEL, "stable").lowercase()
        return listOf("stable", "prerelease").map { channel ->
            val selected = channel == current
            MenuNode(
                id = "update_channel.$channel",
                label = channel,
                value = channel,
                handler = HandlerIds.SELECT_CONFIG,
                payload = MenuPayload.ConfigValue(ConfigKeys.UPDATE_CHANNEL),
                checked = selected,
                tone = if (selected) MenuTone.ACCENT else MenuTone.NORMAL,
            )
        }
    }

    private val RETENTION_PRESETS = listOf(0, 1, 3, 7, 14, 30)

    private fun countLabel(ctx: MenuContext, count: Int): String =
        if (count == 1) {
            ctx.strings.text("TXT_ANDROID_ITEM_COUNT_ONE")
        } else {
            ctx.strings.format("TXT_ANDROID_ITEM_COUNT_FMT", count.toString())
        }

    private fun choiceTarget(
        id: String,
        labelKey: String,
        configKey: String,
        icon: String? = null,
        detail: String? = null,
    ): MenuNode = MenuNode(
        id = id,
        labelKey = labelKey,
        icon = icon,
        detail = detail,
        kind = MenuNodeKind.SUBMENU,
        targetMenuId = MenuIds.CHOICE,
        payload = MenuPayload.ConfigValue(configKey),
    )

    private fun themeChoices(ctx: MenuContext): List<MenuNode> {
        val current = ctx.config.string(ConfigKeys.THEME, ThemeSpec.DEFAULT_ID)
        return ctx.themes.map { theme ->
            MenuNode(
                id = "theme.${theme.id}",
                label = theme.name,
                detail = theme.id,
                value = theme.id,
                handler = HandlerIds.SELECT_THEME,
                checked = theme.id == current,
                tone = if (theme.id == current) MenuTone.ACCENT else MenuTone.NORMAL,
            )
        }
    }

    private fun languageChoices(ctx: MenuContext): List<MenuNode> {
        val current = ctx.config.string(ConfigKeys.LANG, "auto")
        val auto = current.equals("auto", ignoreCase = true)
        return buildList {
            add(
                MenuNode(
                    id = "lang.auto",
                    labelKey = "TXT_ANDROID_LANG_AUTO",
                    detail = Locale.getDefault().toLanguageTag(),
                    value = "auto",
                    handler = HandlerIds.SELECT_LANG,
                    checked = auto,
                    tone = if (auto) MenuTone.ACCENT else MenuTone.NORMAL,
                ),
            )
            ctx.languages.forEach { language ->
                val selected = language.tag.equals(current, ignoreCase = true)
                add(
                    MenuNode(
                        id = "lang.${language.tag}",
                        label = language.name,
                        detail = language.tag,
                        value = language.tag,
                        handler = HandlerIds.SELECT_LANG,
                        checked = selected,
                        tone = if (selected) MenuTone.ACCENT else MenuTone.NORMAL,
                    ),
                )
            }
        }
    }

    private fun pageChoices(ctx: MenuContext): List<MenuNode> {
        val current = ctx.config.int(ConfigKeys.PER_PAGE, 30)
        return PAGE_PRESETS.map { preset ->
            val selected = preset == current
            MenuNode(
                id = "page.$preset",
                label = preset.toString(),
                detail = if (selected) ctx.strings.text("TXT_ANDROID_ACTIVE") else null,
                value = preset.toString(),
                handler = HandlerIds.SELECT_CONFIG,
                payload = MenuPayload.ConfigValue(ConfigKeys.PER_PAGE),
                checked = selected,
                tone = if (selected) MenuTone.ACCENT else MenuTone.NORMAL,
            )
        }
    }

    private val PAGE_PRESETS = listOf(10, 20, 30, 50, 100)

    /**
     * Retry budgets offered in the config screen. One is useful on a metered link, where
     * a stalled transfer should fail fast instead of spending the allowance again.
     */
    private val RETRY_PRESETS = listOf(1, 3, 5, 10)
}
