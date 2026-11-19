package io.github.iseaprojects.rivo.data.library

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Columns every library table needs to render a row without touching the network.
 *
 * The snapshot is deliberately denormalized: a saved video must still be listed when the
 * extractor is offline, the video was removed, or the user switches backend flavor.
 * [videoId] plus [provider] is the canonical identity; the URL is what the actions menu
 * hands to the browser.
 */
data class VideoRecord(
    val videoId: String,
    val url: String,
    val title: String,
    val provider: String,
    val channelId: String? = null,
    val channelName: String? = null,
    val channelUrl: String? = null,
    val durationSeconds: Long? = null,
    val viewCount: Long? = null,
    val publishedText: String? = null,
    val thumbnailUrl: String? = null,
    val isLive: Boolean = false,
)

/** Videos the user saved explicitly ("Save" in the media actions menu). */
@Entity(tableName = "saved_video", indices = [Index("savedAt")])
data class SavedVideoEntity(
    @PrimaryKey val key: String,
    @Embedded val video: VideoRecord,
    val savedAt: Long,
)

/**
 * Locally opened media ("Recent"). Recording on open is deliberate: the player does not
 * exist yet in F4, and the list is only useful if it fills up before F5 lands. The player
 * re-records on playback, which simply bumps the same row.
 */
@Entity(tableName = "recent_video", indices = [Index("openedAt")])
data class RecentVideoEntity(
    @PrimaryKey val key: String,
    @Embedded val video: VideoRecord,
    val openedAt: Long,
    val openCount: Int,
)

/** A user-created playlist. Ids are autoincrement because the title is user text. */
@Entity(tableName = "custom_playlist", indices = [Index("updatedAt")])
data class CustomPlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Membership of a video in a custom playlist, with the order the user added them. */
@Entity(
    tableName = "playlist_item",
    primaryKeys = ["playlistId", "key"],
    foreignKeys = [
        ForeignKey(
            entity = CustomPlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId")],
)
data class PlaylistItemEntity(
    val playlistId: Long,
    val key: String,
    @Embedded val video: VideoRecord,
    val position: Int,
    val addedAt: Long,
)

/** `queryKey` is the case-folded query, so "Lofi" and "lofi" are one history entry. */
@Entity(tableName = "search_history", indices = [Index("lastUsedAt")])
data class SearchHistoryEntity(
    @PrimaryKey val queryKey: String,
    val query: String,
    val lastUsedAt: Long,
    val useCount: Int,
)

/** Locally subscribed channels; `channelKey` is the `UC…` id or the channel URL. */
@Entity(tableName = "subscription", indices = [Index("name")])
data class SubscriptionEntity(
    @PrimaryKey val channelKey: String,
    val channelId: String?,
    val url: String,
    val name: String,
    val provider: String,
    val avatarUrl: String?,
    val subscribedAt: Long,
)

/**
 * A remote playlist the user saved, mirroring upstream's `playlists` file. The videos are
 * not copied: the playlist is re-read from its source when opened, and this row keeps it
 * listed while offline.
 */
@Entity(tableName = "saved_playlist", indices = [Index("savedAt")])
data class SavedPlaylistEntity(
    @PrimaryKey val key: String,
    val playlistId: String,
    val url: String,
    val title: String,
    val provider: String,
    val channelName: String?,
    val channelId: String?,
    val itemCount: Int?,
    val thumbnailUrl: String?,
    val savedAt: Long,
)

/** A playlist row plus its item count, which Room computes in the same query. */
data class PlaylistWithCount(
    @Embedded val playlist: CustomPlaylistEntity,
    val itemCount: Int,
)
