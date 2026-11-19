package io.github.iseaprojects.rivo.menu

/**
 * Nerd Font glyphs used directly by menu builders.
 *
 * Rows that exist in every language pack keep their `TXT_ICON_*` keys so a pack can
 * override them; rows introduced by the Android port (library, playlists, history) use a
 * literal glyph here plus an ASCII fallback in
 * [io.github.iseaprojects.rivo.core.menu.IconCatalog], so adding a row never requires editing three
 * language files just to give it an icon.
 */
object Glyphs {
    const val SEARCH = "\uF002"
    const val PLUS = "\uF067"
    const val MINUS = "\uF068"
    const val PENCIL = "\uF044"
    const val TRASH = "\uF1F8"
    const val FOLDER = "\uF115"
    const val LIST = "\uF0CB"
    const val REFRESH = "\uF021"
    const val SUBSCRIBE = "\uF055"
    const val UNSUBSCRIBE = "\uF056"
    const val PLAY = "\uF04B"
    const val HISTORY = "\uF1DA"
    const val CHANNEL = "\uF2C2"
    const val GEAR = "\uF013"
    const val FIRE = "\uF06D"
}
