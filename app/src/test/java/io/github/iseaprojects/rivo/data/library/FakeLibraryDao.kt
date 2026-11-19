package io.github.iseaprojects.rivo.data.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [LibraryDao] for JVM tests.
 *
 * Room itself cannot run outside an Android runtime, and the repository's contract is what
 * these tests are about: idempotent writes, trimming, ordering, and change notifications.
 * Every query is emulated with the same semantics as the SQL in [LibraryDao] — descending
 * timestamps, `LIMIT`, `IGNORE` on duplicate membership, and `CASCADE` on playlist delete —
 * so a behaviour change in either layer shows up here.
 */
class FakeLibraryDao : LibraryDao {

    private val saved = MutableStateFlow<List<SavedVideoEntity>>(emptyList())
    private val recents = MutableStateFlow<List<RecentVideoEntity>>(emptyList())
    private val playlists = MutableStateFlow<List<CustomPlaylistEntity>>(emptyList())
    private val items = MutableStateFlow<List<PlaylistItemEntity>>(emptyList())
    private val history = MutableStateFlow<List<SearchHistoryEntity>>(emptyList())
    private val subscription = MutableStateFlow<List<SubscriptionEntity>>(emptyList())
    private val savedPlaylists = MutableStateFlow<List<SavedPlaylistEntity>>(emptyList())

    private var nextPlaylistId = 1L

    // -- saved videos ---------------------------------------------------------------

    override fun observeSaved(): Flow<List<SavedVideoEntity>> =
        saved.map { rows -> rows.sortedByDescending { it.savedAt } }

    override suspend fun saved(): List<SavedVideoEntity> =
        saved.value.sortedByDescending { it.savedAt }

    override suspend fun isSaved(key: String): Boolean = saved.value.any { it.key == key }

    override suspend fun upsertSaved(entity: SavedVideoEntity) {
        saved.value = saved.value.filterNot { it.key == entity.key } + entity
    }

    override suspend fun deleteSaved(key: String) {
        saved.value = saved.value.filterNot { it.key == key }
    }

    // -- recents --------------------------------------------------------------------

    override fun observeRecents(limit: Int): Flow<List<RecentVideoEntity>> =
        recents.map { rows -> rows.sortedByDescending { it.openedAt }.take(limit) }

    override suspend fun recents(limit: Int): List<RecentVideoEntity> =
        recents.value.sortedByDescending { it.openedAt }.take(limit)

    override suspend fun recent(key: String): RecentVideoEntity? =
        recents.value.firstOrNull { it.key == key }

    override suspend fun upsertRecent(entity: RecentVideoEntity) {
        recents.value = recents.value.filterNot { it.key == entity.key } + entity
    }

    override suspend fun trimRecents(limit: Int) {
        val keep = recents.value.sortedByDescending { it.openedAt }.take(limit).map { it.key }
        recents.value = recents.value.filter { it.key in keep }
    }

    override suspend fun deleteRecent(key: String) {
        recents.value = recents.value.filterNot { it.key == key }
    }

    override suspend fun clearRecents() {
        recents.value = emptyList()
    }

    // -- custom playlists -----------------------------------------------------------

    override fun observePlaylists(): Flow<List<PlaylistWithCount>> =
        playlists.map { rows -> rows.withCounts().sortedByDescending { it.playlist.updatedAt } }

    override suspend fun playlist(id: Long): CustomPlaylistEntity? =
        playlists.value.firstOrNull { it.id == id }

    override suspend fun insertPlaylist(entity: CustomPlaylistEntity): Long {
        val id = nextPlaylistId++
        playlists.value = playlists.value + entity.copy(id = id)
        return id
    }

    override suspend fun renamePlaylist(id: Long, title: String, now: Long) {
        playlists.value = playlists.value.map {
            if (it.id == id) it.copy(title = title, updatedAt = now) else it
        }
    }

    override suspend fun touchPlaylist(id: Long, now: Long) {
        playlists.value = playlists.value.map {
            if (it.id == id) it.copy(updatedAt = now) else it
        }
    }

    override suspend fun deletePlaylist(id: Long) {
        playlists.value = playlists.value.filterNot { it.id == id }
        items.value = items.value.filterNot { it.playlistId == id }
    }

    override suspend fun lastPosition(id: Long): Int? =
        items.value.filter { it.playlistId == id }.maxOfOrNull { it.position }

    override suspend fun insertItem(entity: PlaylistItemEntity): Long {
        val exists = items.value.any {
            it.playlistId == entity.playlistId && it.key == entity.key
        }
        if (exists) return -1L
        items.value = items.value + entity
        return 0L
    }

    override fun observePlaylistItems(id: Long): Flow<List<PlaylistItemEntity>> =
        items.map { rows ->
            rows.filter { it.playlistId == id }.sortedBy { it.position }
        }

    override suspend fun playlistItems(id: Long): List<PlaylistItemEntity> =
        items.value.filter { it.playlistId == id }.sortedBy { it.position }

    override suspend fun deletePlaylistItem(id: Long, key: String) {
        items.value = items.value.filterNot { it.playlistId == id && it.key == key }
    }

    // -- search history -------------------------------------------------------------

    override fun observeSearchHistory(limit: Int): Flow<List<SearchHistoryEntity>> =
        history.map { rows -> rows.sortedByDescending { it.lastUsedAt }.take(limit) }

    override suspend fun searchHistory(limit: Int): List<SearchHistoryEntity> =
        history.value.sortedByDescending { it.lastUsedAt }.take(limit)

    override suspend fun searchHistoryEntry(queryKey: String): SearchHistoryEntity? =
        history.value.firstOrNull { it.queryKey == queryKey }

    override suspend fun upsertSearchHistory(entity: SearchHistoryEntity) {
        history.value = history.value.filterNot { it.queryKey == entity.queryKey } + entity
    }

    override suspend fun deleteSearchHistory(queryKey: String) {
        history.value = history.value.filterNot { it.queryKey == queryKey }
    }

    override suspend fun trimSearchHistory(limit: Int) {
        val keep = history.value.sortedByDescending { it.lastUsedAt }.take(limit)
            .map { it.queryKey }
        history.value = history.value.filter { it.queryKey in keep }
    }

    override suspend fun clearSearchHistory() {
        history.value = emptyList()
    }

    // -- subscriptions --------------------------------------------------------------

    override fun observeSubscriptions(): Flow<List<SubscriptionEntity>> =
        subscription.map { rows -> rows.sortedBy { it.name.lowercase() } }

    override suspend fun subscriptions(): List<SubscriptionEntity> =
        subscription.value.sortedBy { it.name.lowercase() }

    override suspend fun isSubscribed(channelKey: String): Boolean =
        subscription.value.any { it.channelKey == channelKey }

    override suspend fun upsertSubscription(entity: SubscriptionEntity) {
        subscription.value = subscription.value.filterNot { it.channelKey == entity.channelKey } +
            entity
    }

    override suspend fun deleteSubscription(channelKey: String) {
        subscription.value = subscription.value.filterNot { it.channelKey == channelKey }
    }

    override suspend fun clearSubscriptions() {
        subscription.value = emptyList()
    }

    // -- saved (remote) playlists ---------------------------------------------------

    override fun observeSavedPlaylists(): Flow<List<SavedPlaylistEntity>> =
        savedPlaylists.map { rows -> rows.sortedByDescending { it.savedAt } }

    override suspend fun savedPlaylists(): List<SavedPlaylistEntity> =
        savedPlaylists.value.sortedByDescending { it.savedAt }

    override suspend fun isPlaylistSaved(key: String): Boolean =
        savedPlaylists.value.any { it.key == key }

    override suspend fun upsertSavedPlaylist(entity: SavedPlaylistEntity) {
        savedPlaylists.value = savedPlaylists.value.filterNot { it.key == entity.key } + entity
    }

    override suspend fun deleteSavedPlaylist(key: String) {
        savedPlaylists.value = savedPlaylists.value.filterNot { it.key == key }
    }

    private fun List<CustomPlaylistEntity>.withCounts(): List<PlaylistWithCount> = map {
        PlaylistWithCount(it, items.value.count { row -> row.playlistId == it.id })
    }
}
