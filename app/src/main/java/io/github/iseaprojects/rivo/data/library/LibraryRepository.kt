package io.github.iseaprojects.rivo.data.library

import io.github.iseaprojects.rivo.core.model.ChannelSummary
import io.github.iseaprojects.rivo.core.model.LibraryKeys
import io.github.iseaprojects.rivo.core.model.MediaSummary
import io.github.iseaprojects.rivo.core.model.PlaylistSummary
import io.github.iseaprojects.rivo.core.model.libraryKey
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** A user playlist as the menus and lists need it. */
data class LocalPlaylist(
    val id: Long,
    val title: String,
    val itemCount: Int,
    val updatedAt: Long,
)

/**
 * The single entry point to library state.
 *
 * Screens read through the Flow-returning methods, while every write is a suspend call on
 * [dispatcher] so a save/delete never touches the main thread. Mutations are idempotent:
 * saving an already-saved video, adding the same item twice, or clearing an empty history
 * produces no error and no duplicate row.
 *
 * [changes] is emitted after every successful write. Data screens collect it to reload a
 * local list that is behind the actions menu the user just used, without polling.
 */
class LibraryRepository(
    private val dao: LibraryDao,
    private val dispatcher: CoroutineContext = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    /** Fires after any mutation; local list screens reload when it does. */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    // -- reads ----------------------------------------------------------------------

    fun saved(): Flow<List<MediaSummary>> =
        dao.observeSaved().map { rows -> rows.map { it.video.toSummary() } }

    fun recents(limit: Int): Flow<List<MediaSummary>> =
        dao.observeRecents(limit.coerceAtLeast(1))
            .map { rows -> rows.map { it.video.toSummary() } }

    fun playlists(): Flow<List<LocalPlaylist>> =
        dao.observePlaylists().map { rows ->
            rows.map {
                LocalPlaylist(
                    id = it.playlist.id,
                    title = it.playlist.title,
                    itemCount = it.itemCount,
                    updatedAt = it.playlist.updatedAt,
                )
            }
        }

    fun playlistItems(id: Long): Flow<List<MediaSummary>> =
        dao.observePlaylistItems(id).map { rows -> rows.map { it.video.toSummary() } }

    fun searchHistory(limit: Int): Flow<List<String>> =
        dao.observeSearchHistory(limit.coerceAtLeast(1))
            .map { rows -> rows.map { it.query } }

    fun subscriptions(): Flow<List<ChannelSummary>> =
        dao.observeSubscriptions().map { rows -> rows.map { it.toSummary() } }

    fun savedPlaylists(): Flow<List<PlaylistSummary>> =
        dao.observeSavedPlaylists().map { rows -> rows.map { it.toSummary() } }

    suspend fun savedOnce(): List<MediaSummary> = withContext(dispatcher) {
        dao.saved().map { it.video.toSummary() }
    }

    suspend fun recentsOnce(limit: Int): List<MediaSummary> = withContext(dispatcher) {
        dao.recents(limit.coerceAtLeast(1)).map { it.video.toSummary() }
    }

    suspend fun playlistItemsOnce(id: Long): List<MediaSummary> = withContext(dispatcher) {
        dao.playlistItems(id).map { it.video.toSummary() }
    }

    suspend fun recentCount(): Int = withContext(dispatcher) {
        dao.recents(Int.MAX_VALUE).size
    }

    /** Newest first; used for `!1` recall without collecting the Flow. */
    suspend fun recall(limit: Int): List<String> = withContext(dispatcher) {
        dao.searchHistory(limit.coerceAtLeast(1)).map { it.query }
    }

    // -- saved videos ---------------------------------------------------------------

    /** Returns true when the video was not saved before. */
    suspend fun save(media: MediaSummary): Boolean = withContext(dispatcher) {
        val key = media.libraryKey()
        if (dao.isSaved(key)) {
            false
        } else {
            dao.upsertSaved(SavedVideoEntity(key, media.toRecord(), clock()))
            changed()
            true
        }
    }

    suspend fun unsave(media: MediaSummary): Boolean = withContext(dispatcher) {
        val key = media.libraryKey()
        val existed = dao.isSaved(key)
        if (existed) {
            dao.deleteSaved(key)
            changed()
        }
        existed
    }

    suspend fun isSaved(media: MediaSummary): Boolean = withContext(dispatcher) {
        dao.isSaved(media.libraryKey())
    }

    // -- recents --------------------------------------------------------------------

    /** Inserts or bumps a recent entry, then trims the table to [limit] rows. */
    suspend fun recordRecent(media: MediaSummary, limit: Int): Unit = withContext(dispatcher) {
        val key = media.libraryKey()
        val previous = dao.recent(key)
        val now = clock()
        dao.upsertRecent(
            RecentVideoEntity(
                key = key,
                video = media.toRecord(),
                openedAt = now,
                openCount = (previous?.openCount ?: 0) + 1,
            ),
        )
        dao.trimRecents(limit.coerceAtLeast(1))
        changed()
    }

    suspend fun removeRecent(media: MediaSummary) = withContext(dispatcher) {
        dao.deleteRecent(media.libraryKey())
        changed()
    }

    suspend fun clearRecents() = withContext(dispatcher) {
        dao.clearRecents()
        changed()
    }

    // -- custom playlists ------------------------------------------------------------

    suspend fun createPlaylist(title: String): Long = withContext(dispatcher) {
        val now = clock()
        val id = dao.insertPlaylist(
            CustomPlaylistEntity(
                title = title.trim(),
                createdAt = now,
                updatedAt = now,
            ),
        )
        changed()
        id
    }

    suspend fun renamePlaylist(id: Long, title: String) = withContext(dispatcher) {
        dao.renamePlaylist(id, title.trim(), clock())
        changed()
    }

    suspend fun deletePlaylist(id: Long) = withContext(dispatcher) {
        dao.deletePlaylist(id)
        changed()
    }

    /** Returns true when the item was not already in the playlist. */
    suspend fun addToPlaylist(id: Long, media: MediaSummary): Boolean = withContext(dispatcher) {
        val now = clock()
        val position = (dao.lastPosition(id) ?: -1) + 1
        val inserted = dao.insertItem(
            PlaylistItemEntity(
                playlistId = id,
                key = media.libraryKey(),
                video = media.toRecord(),
                position = position,
                addedAt = now,
            ),
        )
        if (inserted != -1L) {
            dao.touchPlaylist(id, now)
            changed()
        }
        inserted != -1L
    }

    suspend fun removeFromPlaylist(id: Long, media: MediaSummary) = withContext(dispatcher) {
        // Only a real removal is an edit: touching the timestamp on a no-op sorted the
        // playlist to the top of the list for nothing.
        if (dao.deletePlaylistItem(id, media.libraryKey()) > 0) {
            dao.touchPlaylist(id, clock())
            changed()
        }
    }

    suspend fun playlistTitle(id: Long): String? = withContext(dispatcher) {
        dao.playlist(id)?.title
    }

    suspend fun playlistExists(id: Long): Boolean = withContext(dispatcher) {
        dao.playlist(id) != null
    }

    // -- search history -------------------------------------------------------------

    suspend fun recordSearch(query: String, limit: Int): Unit = withContext(dispatcher) {
        val text = query.trim()
        if (text.isEmpty()) return@withContext
        val key = LibraryKeys.query(text)
        val previous = dao.searchHistoryEntry(key)
        dao.upsertSearchHistory(
            SearchHistoryEntity(
                queryKey = key,
                query = text,
                lastUsedAt = clock(),
                useCount = (previous?.useCount ?: 0) + 1,
            ),
        )
        // Keep the table bounded even though the screen only shows a window.
        dao.trimSearchHistory(limit.coerceAtLeast(1))
        changed()
    }

    suspend fun removeSearch(query: String) = withContext(dispatcher) {
        dao.deleteSearchHistory(LibraryKeys.query(query))
        changed()
    }

    suspend fun clearSearchHistory() = withContext(dispatcher) {
        dao.clearSearchHistory()
        changed()
    }

    // -- subscriptions --------------------------------------------------------------

    /** Returns true when the channel was not subscribed before. */
    suspend fun subscribe(channel: ChannelSummary): Boolean = withContext(dispatcher) {
        val key = channel.libraryKey()
        val existed = dao.isSubscribed(key)
        dao.upsertSubscription(channel.toSubscription(clock()))
        if (!existed) changed()
        !existed
    }

    suspend fun unsubscribe(channel: ChannelSummary): Boolean = withContext(dispatcher) {
        val key = channel.libraryKey()
        val existed = dao.isSubscribed(key)
        if (existed) {
            dao.deleteSubscription(key)
            changed()
        }
        existed
    }

    suspend fun isSubscribed(channel: ChannelSummary): Boolean = withContext(dispatcher) {
        dao.isSubscribed(channel.libraryKey())
    }

    /**
     * Sync replaces the local list with the account's, as upstream's sync does. An empty
     * [channels] list is a no-op, so a failed or signed-out extraction cannot silently
     * wipe the subscriptions the user added by hand.
     *
     * The inserts land before the deletes. Clearing first — which is what this did — meant
     * that a process death between the two steps left the table empty, and the promise
     * above was false. An interrupted sync now leaves a superset: a few stale rows the next
     * sync removes, nothing lost.
     */
    suspend fun replaceSubscriptions(channels: List<ChannelSummary>): Int = withContext(dispatcher) {
        val unique = channels.distinctBy { it.libraryKey() }
        if (unique.isEmpty()) return@withContext 0
        val now = clock()
        unique.forEach { channel -> dao.upsertSubscription(channel.toSubscription(now)) }
        val keep = unique.mapTo(HashSet(unique.size)) { it.libraryKey() }
        dao.subscriptions()
            .map { it.channelKey }
            .filterNot { it in keep }
            .chunked(DELETE_CHUNK)
            .forEach { stale -> dao.deleteSubscriptions(stale) }
        changed()
        unique.size
    }

    // -- saved (remote) playlists ---------------------------------------------------

    suspend fun savePlaylist(playlist: PlaylistSummary): Boolean = withContext(dispatcher) {
        val key = playlist.libraryKey()
        if (dao.isPlaylistSaved(key)) {
            false
        } else {
            dao.upsertSavedPlaylist(playlist.toSavedPlaylist(clock()))
            changed()
            true
        }
    }

    suspend fun unsavePlaylist(playlist: PlaylistSummary): Boolean = withContext(dispatcher) {
        val key = playlist.libraryKey()
        val existed = dao.isPlaylistSaved(key)
        if (existed) {
            dao.deleteSavedPlaylist(key)
            changed()
        }
        existed
    }

    suspend fun isPlaylistSaved(playlist: PlaylistSummary): Boolean = withContext(dispatcher) {
        dao.isPlaylistSaved(playlist.libraryKey())
    }

    private fun changed() {
        _changes.tryEmit(Unit)
    }

    private companion object {
        /**
         * SQLite's default limit is 999 bound variables per statement; a channel list
         * larger than that has to be deleted in pieces.
         */
        const val DELETE_CHUNK = 500
    }
}
