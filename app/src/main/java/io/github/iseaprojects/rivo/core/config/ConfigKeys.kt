package io.github.iseaprojects.rivo.core.config

/**
 * Canonical names of every setting understood by the port.
 *
 * The names are deliberately identical to yt-x 0.8.7 (`config` is sourced as a shell
 * script there); only the meanings that cannot exist inside an Android app sandbox
 * (launcher, terminal renderer, browser cookie extraction) are kept as inert keys so
 * an upstream config file imports without losing data.
 */
object ConfigKeys {
    // Extensions
    const val EXTENSIONS_ENABLED = "CONFIG_EXTENSIONS_ENABLED"
    const val AUTOLOADED_EXTENSIONS = "CONFIG_AUTOLOADED_EXTENSIONS"

    // Interface
    const val LAUNCHER = "CONFIG_LAUNCHER"
    const val ENABLE_COLORS = "CONFIG_ENABLE_COLORS"
    const val ENABLE_PREVIEW = "CONFIG_ENABLE_PREVIEW"
    const val ENABLE_PREVIEW_IMAGES = "CONFIG_ENABLE_PREVIEW_IMAGES"
    const val TWO_PANE = "CONFIG_TWO_PANE"
    const val NERD_FONTS = "CONFIG_NERD_FONTS"
    const val THEME = "CONFIG_THEME"
    const val LANG = "CONFIG_LANG"
    const val KEYBINDINGS = "CONFIG_KEYBINDINGS"

    // Paging and cache
    const val PER_PAGE = "CONFIG_PER_PAGE"
    const val CACHE_RETENTION_DAYS = "CONFIG_CACHE_RETENTION_DAYS"
    const val NO_OF_RECENT = "CONFIG_NO_OF_RECENT"
    const val ENABLE_SEARCH_HISTORY = "CONFIG_ENABLE_SEARCH_HISTORY"

    // Player
    const val PLAYER = "CONFIG_PLAYER"
    const val DISOWN_PLAYER = "CONFIG_DISOWN_PLAYER"
    const val EXTERNAL_PLAYER = "CONFIG_EXTERNAL_PLAYER"
    const val MPV_ARGS = "CONFIG_MPV_ARGS"
    const val VLC_ARGS = "CONFIG_VLC_ARGS"
    const val YT_DLP_OPTS = "CONFIG_YT_DLP_OPTS"
    const val EXTERNAL_FORMAT = "CONFIG_EXTERNAL_FORMAT"
    const val SPONSORBLOCK = "CONFIG_SPONSORBLOCK"
    const val SPONSORBLOCK_CATEGORIES = "CONFIG_SPONSORBLOCK_CATEGORIES"
    const val SUBTITLES_LANGS = "CONFIG_SUBTITLES_LANGS"
    const val STORYBOARDS = "CONFIG_STORYBOARDS"
    const val RESOLVE_MAX_AGE_HOURS = "CONFIG_RESOLVE_MAX_AGE_HOURS"

    // Downloads
    const val DOWNLOAD_DIR = "CONFIG_DOWNLOAD_DIR"
    const val DOWNLOADS_ENUMERATE = "CONFIG_DOWNLOADS_ENUMERATE"
    const val DOWNLOAD_WIFI_ONLY = "CONFIG_DOWNLOAD_WIFI_ONLY"
    const val DOWNLOAD_CONCURRENCY = "CONFIG_DOWNLOAD_CONCURRENCY"
    const val DOWNLOAD_RETRIES = "CONFIG_DOWNLOAD_RETRIES"
    const val AUDIO_FORMAT = "CONFIG_AUDIO_FORMAT"
    const val VIDEO_FORMAT = "CONFIG_VIDEO_FORMAT"
    const val DOWNLOAD_ARCHIVE = "CONFIG_DOWNLOAD_ARCHIVE"

    // Accounts and network
    const val BROWSER = "CONFIG_BROWSER"
    const val COOKIES_ENABLED = "CONFIG_COOKIES_ENABLED"
    const val PO_TOKEN = "CONFIG_PO_TOKEN"
    const val ACCOUNT_FEEDS = "CONFIG_ACCOUNT_FEEDS"
    const val EXTRACTOR = "CONFIG_EXTRACTOR"
    const val YTDLP_CHANNEL = "CONFIG_YTDLP_CHANNEL"

    // Maintenance
    const val CHECK_FOR_UPDATES = "CONFIG_CHECK_FOR_UPDATES"
    const val UPDATE_CHANNEL = "CONFIG_UPDATE_CHANNEL"
    const val NOTIFICATION_DURATION = "CONFIG_NOTIFICATION_DURATION"
    const val EDITOR = "CONFIG_EDITOR"

    /**
     * Everything that may legitimately arrive through an override. Keys not listed
     * here are still preserved in the file but cannot be set through intents/env,
     * which keeps `RIVO_*` from becoming an arbitrary property injector.
     */
    val known: Set<String> = setOf(
        EXTENSIONS_ENABLED, AUTOLOADED_EXTENSIONS,
        LAUNCHER, ENABLE_COLORS, ENABLE_PREVIEW, ENABLE_PREVIEW_IMAGES, TWO_PANE,
        NERD_FONTS, THEME, LANG, KEYBINDINGS,
        PER_PAGE, CACHE_RETENTION_DAYS, NO_OF_RECENT, ENABLE_SEARCH_HISTORY,
        PLAYER, DISOWN_PLAYER, EXTERNAL_PLAYER, MPV_ARGS, VLC_ARGS, YT_DLP_OPTS,
        EXTERNAL_FORMAT, SPONSORBLOCK, SPONSORBLOCK_CATEGORIES, SUBTITLES_LANGS,
        STORYBOARDS, RESOLVE_MAX_AGE_HOURS,
        DOWNLOAD_DIR, DOWNLOADS_ENUMERATE, DOWNLOAD_WIFI_ONLY, DOWNLOAD_CONCURRENCY,
        DOWNLOAD_RETRIES, AUDIO_FORMAT, VIDEO_FORMAT, DOWNLOAD_ARCHIVE,
        BROWSER, COOKIES_ENABLED, PO_TOKEN, ACCOUNT_FEEDS,
        EXTRACTOR, YTDLP_CHANNEL,
        CHECK_FOR_UPDATES, UPDATE_CHANNEL, NOTIFICATION_DURATION, EDITOR,
    )

    /**
     * yt-x exposes `YT_X_ENUMERATE_DOWNLOADS` for `CONFIG_DOWNLOADS_ENUMERATE`, i.e.
     * the suffix does not always match the key. Intent extras use the same aliases
     * with a `RIVO_` prefix.
     */
    private val suffixAliases: Map<String, String> = mapOf(
        "ENUMERATE_DOWNLOADS" to DOWNLOADS_ENUMERATE,
        "NO_PREVIEW" to ENABLE_PREVIEW,
        "NO_PREVIEW_IMAGES" to ENABLE_PREVIEW_IMAGES,
        "AUTOLOAD_EXTENSIONS" to AUTOLOADED_EXTENSIONS,
        "CONFIG" to "",
    )

    /** `RIVO_PER_PAGE` -> `CONFIG_PER_PAGE`, or null when the name is not a setting. */
    fun keyForOverride(name: String): String? {
        val suffix = when {
            name.startsWith("RIVO_") -> name.removePrefix("RIVO_")
            name.startsWith("YT_X_") -> name.removePrefix("YT_X_")
            else -> return null
        }
        if (suffix.isEmpty()) return null
        suffixAliases[suffix]?.takeIf { it.isNotEmpty() }?.let { return it }
        val candidate = "CONFIG_$suffix"
        return candidate.takeIf { it in known }
    }
}
