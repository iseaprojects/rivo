package io.github.iseaprojects.rivo.data.library

/**
 * Fixed ceilings for the local library.
 *
 * `CONFIG_NO_OF_RECENT` decides how many recents are *kept*; the ceiling here bounds what a
 * screen will read, so a hand-edited config cannot make a phone render ten thousand rows.
 * The search-history size follows upstream's intent (a long, cheap recall list) without
 * letting the table grow forever.
 */
object LibraryLimits {
    const val SEARCH_HISTORY = 50
    const val RECENTS_CEILING = 200
}
