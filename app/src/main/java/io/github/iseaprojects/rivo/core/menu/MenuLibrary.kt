package io.github.iseaprojects.rivo.core.menu

import io.github.iseaprojects.rivo.core.model.ChannelSummary
import io.github.iseaprojects.rivo.core.model.MediaSummary
import io.github.iseaprojects.rivo.core.model.PlaylistSummary
import io.github.iseaprojects.rivo.core.model.libraryKey

/** A custom playlist as a menu row needs it. */
data class MenuPlaylist(
    val id: Long,
    val title: String,
    val itemCount: Int,
)

/**
 * An immutable snapshot of the local library, handed to [MenuContext].
 *
 * Menus are pure functions of their context, so anything a row needs to decide must be in
 * the context. Keeping the library here — instead of letting builders query Room — means a
 * save or a subscription rebuilds the visible menu with the new state, and menu resolution
 * stays synchronous and testable.
 *
 * The lists are small by construction (subscriptions, custom playlists, a bounded search
 * history, saved items), so the key sets are computed once per snapshot.
 */
class MenuLibrary(
    val saved: List<MediaSummary> = emptyList(),
    val subscriptions: List<ChannelSummary> = emptyList(),
    val playlists: List<MenuPlaylist> = emptyList(),
    val searchHistory: List<String> = emptyList(),
    val savedPlaylists: List<PlaylistSummary> = emptyList(),
) {
    private val savedKeys: Set<String> by lazy { saved.map { it.libraryKey() }.toSet() }
    private val subscriptionKeys: Set<String> by lazy { subscriptions.map { it.libraryKey() }.toSet() }
    private val savedPlaylistKeys: Set<String> by lazy { savedPlaylists.map { it.libraryKey() }.toSet() }

    fun isSaved(media: MediaSummary): Boolean = media.libraryKey() in savedKeys

    fun isSubscribed(channel: ChannelSummary): Boolean = channel.libraryKey() in subscriptionKeys

    fun isPlaylistSaved(playlist: PlaylistSummary): Boolean =
        playlist.libraryKey() in savedPlaylistKeys

    companion object {
        val EMPTY = MenuLibrary()
    }
}
