package io.github.iseaprojects.rivo.core.menu

/**
 * ASCII replacements for Nerd Font glyphs.
 *
 * yt-x has the same escape hatch (`CONFIG_ENABLE_COLORS` / a font without patched
 * glyphs): the menu stays fully readable, only the icon column degrades. Keys are
 * matched by concept so an extension that reuses, say, `TXT_ICON_MENU_MISC_BACK`
 * still gets an arrow.
 */
object IconCatalog {

    fun asciiFor(node: MenuNode): String? {
        val glyph = node.icon
        if (glyph != null) {
            val trimmed = glyph.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.all { it.code in 0x20..0x7E }) return trimmed
            literalAscii[trimmed]?.let { return it }
        }
        return asciiForKey(node.iconKey)
    }

    fun asciiForKey(key: String?): String? {
        if (key == null) return null
        val k = key.uppercase()
        return when {
            k.contains("WATCH_ALL") -> ">>"
            k.contains("LISTEN_ALL") -> "~~"
            k.contains("DOWNLOAD_ALL_AUDIO") -> "vv~"
            k.contains("DOWNLOAD_ALL") -> "vv"
            k.contains("SEARCH_HISTORY") -> "?"
            k.contains("SEARCH") -> "?"
            k.contains("EXPLORE") -> "?"
            k.contains("SUBS") -> "+"
            k.contains("SUBSCRIBE") -> "+"
            k.contains("WATCH_LATER") -> "o"
            k.contains("PLAYLISTS") -> "="
            k.contains("PLAYLIST") -> "="
            k.contains("CHANNELS") -> "@"
            k.contains("CHANNEL") -> "@"
            k.contains("SAVED") -> "*"
            k.contains("RECENT") -> "^"
            k.contains("LIKED") -> "<3"
            k.contains("HISTORY") -> "h"
            k.contains("CLIPS") -> "c"
            k.contains("MISC") -> ":"
            k.contains("CONFIG") -> "%"
            k.contains("EDIT") -> "%"
            k.contains("EXIT") -> "x"
            k.contains("BACK") -> "<"
            k.contains("WATCH") -> ">"
            k.contains("LISTEN") -> "~"
            k.contains("MIX") -> "M"
            k.contains("DOWNLOAD_AUDIO") -> "v~"
            k.contains("DOWNLOAD") -> "v"
            k.contains("BROWSER") -> "W"
            k.contains("COPY") -> "C"
            k.contains("SHELL") -> "$"
            k.contains("ENUM") -> "#"
            k.contains("SYNC") -> "S"
            k.contains("CLEAR") -> "-"
            k.contains("NEW") -> "+"
            k.contains("RENAME") -> "r"
            k.contains("DELETE") -> "x"
            k.contains("REMOVE") -> "-"
            k.contains("ADD") -> "+"
            k.contains("OPEN") -> ">"
            k.contains("FEATURED") -> "*"
            k.contains("SHORTS") -> "|"
            k.contains("STREAMS") -> "~"
            k.contains("PODCASTS") -> "p"
            k.contains("MOVIES") -> "M"
            k.contains("FEED") -> "~"
            else -> "*"
        }
    }

    /** Glyphs used directly in code (Nerd Font private-use area). */
    private val literalAscii: Map<String, String> = mapOf(
        "\uF013" to "%", // gear
        "\uF06D" to "!", // fire / trending
        "\uF0C1" to "@", // link
        "\uF021" to "^", // refresh
        "\uF019" to "v", // download
        "\uF001" to "~", // feed
        "\uF002" to "?", // search
        "\uF067" to "+", // plus / new
        "\uF068" to "-", // minus / remove
        "\uF044" to "%", // pencil / rename
        "\uF1F8" to "x", // trash / delete
        "\uF115" to "=", // folder
        "\uF0CB" to "=", // list / playlist
        "\uF04B" to ">", // play
        "\uF055" to "+", // subscribe
        "\uF056" to "-", // unsubscribe
        "\uF1DA" to "h", // history
        "\uF2C2" to "@", // channel
    )
}
