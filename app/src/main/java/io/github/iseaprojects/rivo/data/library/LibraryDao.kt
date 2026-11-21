package io.github.iseaprojects.rivo.data.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * All library reads are Flows, so a screen updates when the same data is changed from a
 * different one (unsaving from the actions menu while the Saved list is behind it).
 *
 * Writes are suspend and run on the repository's dispatcher; nothing here touches the
 * network. Every query is a single statement so Room can verify it at compile time, and
 * the one-shot variants exist for handlers that must answer "now" rather than collect.
 */
@Dao
interface LibraryDao {

    // -- saved videos ---------------------------------------------------------------

    @Query("SELECT * FROM saved_video ORDER BY savedAt DESC")
    fun observeSaved(): Flow<List<SavedVideoEntity>>

    @Query("SELECT * FROM saved_video ORDER BY savedAt DESC")
    suspend fun saved(): List<SavedVideoEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM saved_video WHERE key = :key)")
    suspend fun isSaved(key: String): Boolean

    @Upsert
    suspend fun upsertSaved(entity: SavedVideoEntity)

    @Query("DELETE FROM saved_video WHERE key = :key")
    suspend fun deleteSaved(key: String)

    // -- recents --------------------------------------------------------------------

    @Query("SELECT * FROM recent_video ORDER BY openedAt DESC LIMIT :limit")
    fun observeRecents(limit: Int): Flow<List<RecentVideoEntity>>

    @Query("SELECT * FROM recent_video ORDER BY openedAt DESC LIMIT :limit")
    suspend fun recents(limit: Int): List<RecentVideoEntity>

    @Query("SELECT * FROM recent_video WHERE key = :key")
    suspend fun recent(key: String): RecentVideoEntity?

    @Upsert
    suspend fun upsertRecent(entity: RecentVideoEntity)

    /** Keeps the newest [limit] rows; `NOT IN` on the ordered keys is SQLite-portable. */
    @Query(
        "DELETE FROM recent_video WHERE key NOT IN " +
            "(SELECT key FROM recent_video ORDER BY openedAt DESC LIMIT :limit)",
    )
    suspend fun trimRecents(limit: Int)

    @Query("DELETE FROM recent_video WHERE key = :key")
    suspend fun deleteRecent(key: String)

    @Query("DELETE FROM recent_video")
    suspend fun clearRecents()

    // -- custom playlists -----------------------------------------------------------

    @Query(
        "SELECT p.*, (SELECT COUNT(*) FROM playlist_item i WHERE i.playlistId = p.id) " +
            "AS itemCount FROM custom_playlist p ORDER BY p.updatedAt DESC",
    )
    fun observePlaylists(): Flow<List<PlaylistWithCount>>

    @Query("SELECT * FROM custom_playlist WHERE id = :id")
    suspend fun playlist(id: Long): CustomPlaylistEntity?

    @Insert
    suspend fun insertPlaylist(entity: CustomPlaylistEntity): Long

    @Query("UPDATE custom_playlist SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun renamePlaylist(id: Long, title: String, now: Long)

    @Query("UPDATE custom_playlist SET updatedAt = :now WHERE id = :id")
    suspend fun touchPlaylist(id: Long, now: Long)

    @Query("DELETE FROM custom_playlist WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query("SELECT position FROM playlist_item WHERE playlistId = :id ORDER BY position DESC LIMIT 1")
    suspend fun lastPosition(id: Long): Int?

    /** IGNORE keeps the original position when the same video is added twice. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItem(entity: PlaylistItemEntity): Long

    @Query("SELECT * FROM playlist_item WHERE playlistId = :id ORDER BY position ASC")
    fun observePlaylistItems(id: Long): Flow<List<PlaylistItemEntity>>

    @Query("SELECT * FROM playlist_item WHERE playlistId = :id ORDER BY position ASC")
    suspend fun playlistItems(id: Long): List<PlaylistItemEntity>

    /** Rows removed, so a no-op removal does not bump the playlist's `updatedAt`. */
    @Query("DELETE FROM playlist_item WHERE playlistId = :id AND key = :key")
    suspend fun deletePlaylistItem(id: Long, key: String): Int

    // -- search history -------------------------------------------------------------

    @Query("SELECT * FROM search_history ORDER BY lastUsedAt DESC LIMIT :limit")
    fun observeSearchHistory(limit: Int): Flow<List<SearchHistoryEntity>>

    @Query("SELECT * FROM search_history ORDER BY lastUsedAt DESC LIMIT :limit")
    suspend fun searchHistory(limit: Int): List<SearchHistoryEntity>

    @Query("SELECT * FROM search_history WHERE queryKey = :queryKey")
    suspend fun searchHistoryEntry(queryKey: String): SearchHistoryEntity?

    @Upsert
    suspend fun upsertSearchHistory(entity: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE queryKey = :queryKey")
    suspend fun deleteSearchHistory(queryKey: String)

    @Query(
        "DELETE FROM search_history WHERE queryKey NOT IN " +
            "(SELECT queryKey FROM search_history ORDER BY lastUsedAt DESC LIMIT :limit)",
    )
    suspend fun trimSearchHistory(limit: Int)

    @Query("DELETE FROM search_history")
    suspend fun clearSearchHistory()

    // -- subscriptions --------------------------------------------------------------

    @Query("SELECT * FROM subscription ORDER BY name COLLATE NOCASE ASC")
    fun observeSubscriptions(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscription ORDER BY name COLLATE NOCASE ASC")
    suspend fun subscriptions(): List<SubscriptionEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM subscription WHERE channelKey = :channelKey)")
    suspend fun isSubscribed(channelKey: String): Boolean

    @Upsert
    suspend fun upsertSubscription(entity: SubscriptionEntity)

    @Query("DELETE FROM subscription WHERE channelKey = :channelKey")
    suspend fun deleteSubscription(channelKey: String)

    /** Bulk delete used by a sync; the caller chunks the list under SQLite's limit. */
    @Query("DELETE FROM subscription WHERE channelKey IN (:channelKeys)")
    suspend fun deleteSubscriptions(channelKeys: List<String>)

    @Query("DELETE FROM subscription")
    suspend fun clearSubscriptions()

    // -- saved (remote) playlists ---------------------------------------------------

    @Query("SELECT * FROM saved_playlist ORDER BY savedAt DESC")
    fun observeSavedPlaylists(): Flow<List<SavedPlaylistEntity>>

    @Query("SELECT * FROM saved_playlist ORDER BY savedAt DESC")
    suspend fun savedPlaylists(): List<SavedPlaylistEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM saved_playlist WHERE key = :key)")
    suspend fun isPlaylistSaved(key: String): Boolean

    @Upsert
    suspend fun upsertSavedPlaylist(entity: SavedPlaylistEntity)

    @Query("DELETE FROM saved_playlist WHERE key = :key")
    suspend fun deleteSavedPlaylist(key: String)
}
