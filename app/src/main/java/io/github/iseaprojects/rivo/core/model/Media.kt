package io.github.iseaprojects.rivo.core.model

/**
 * A video-like item. This is the shape every preview, action menu and player request
 * consumes, regardless of which extractor produced it.
 */
data class MediaSummary(
    val id: String,
    val url: String,
    val title: String,
    val provider: MediaProvider = MediaProvider.YOUTUBE,
    val channelId: String? = null,
    val channelName: String? = null,
    val channelUrl: String? = null,
    val description: String? = null,
    val durationSeconds: Long? = null,
    val viewCount: Long? = null,
    val publishedAtEpochMs: Long? = null,
    val publishedText: String? = null,
    val thumbnailUrl: String? = null,
    val isLive: Boolean = false,
    val isShort: Boolean = false,
    val isUpcoming: Boolean = false,
    val availability: Availability = Availability.AVAILABLE,
    val playlistId: String? = null,
    val playlistIndex: Int? = null,
    /** Title of the playlist this item was listed in; downloads use it as a folder. */
    val playlistTitle: String? = null,
) {
    val durationText: String?
        get() = durationSeconds?.let(::formatDuration)

    companion object {
        fun formatDuration(seconds: Long): String {
            if (seconds < 0) return "--:--"
            val hours = seconds / 3600
            val minutes = (seconds % 3600) / 60
            val secs = seconds % 60
            return if (hours > 0) {
                "%d:%02d:%02d".format(hours, minutes, secs)
            } else {
                "%d:%02d".format(minutes, secs)
            }
        }
    }
}

data class PlaylistSummary(
    val id: String,
    val url: String,
    val title: String,
    val provider: MediaProvider = MediaProvider.YOUTUBE,
    val channelName: String? = null,
    val channelId: String? = null,
    val itemCount: Int? = null,
    val thumbnailUrl: String? = null,
    val description: String? = null,
)

/**
 * A YouTube mix is a playlist whose id is `RD` + the seed video id, so "Mix" is a plain
 * playlist navigation and needs no player support of its own.
 */
fun MediaSummary.mixPlaylist(): PlaylistSummary = PlaylistSummary(
    id = "RD$id",
    url = "https://www.youtube.com/watch?v=$id&list=RD$id",
    title = title,
    provider = provider,
    channelName = channelName,
    channelId = channelId,
    thumbnailUrl = thumbnailUrl,
)

data class ChannelSummary(
    val id: String,
    val url: String,
    val name: String,
    val provider: MediaProvider = MediaProvider.YOUTUBE,
    val avatarUrl: String? = null,
    val subscriberCount: Long? = null,
    val description: String? = null,
    val isSubscribed: Boolean = false,
)

enum class MediaProvider {
    YOUTUBE,
    DAILYMOTION,
    PEERTUBE,
    OTHER,
    ;

    companion object {
        fun fromUrl(url: String): MediaProvider {
            val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase().orEmpty()
            return when {
                host.contains("youtu") -> YOUTUBE
                host.contains("dailymotion") || host.contains("dai.ly") -> DAILYMOTION
                host.contains("peertube") -> PEERTUBE
                else -> OTHER
            }
        }
    }
}

enum class Availability {
    AVAILABLE,
    PRIVATE,
    AGE_RESTRICTED,
    GEO_BLOCKED,
    DRM_PROTECTED,
    MEMBERS_ONLY,
    UPCOMING,
    REMOVED,
    LIVE_NOT_READY,
    RATE_LIMITED,
    EXTRACTOR_BROKEN,
    UNKNOWN,
}

enum class FeedKind {
    HOME,
    SUBSCRIPTIONS,
    TRENDING,
    WATCH_LATER,
    LIKED,
    HISTORY,
    CLIPS,
    PLAYLISTS,
    CUSTOM_PLAYLISTS,
    CHANNELS,
    EXPLORE,
}

enum class LibraryKind {
    SAVED,
    RECENT,
    DOWNLOADS,
}

enum class SearchKind {
    VIDEOS,
    PLAYLISTS,
    CHANNELS,
    SHORTS,
    MOVIES,
}

/**
 * Explicit pagination state. yt-x never scrolls infinitely and neither does the port:
 * every list knows the window it is showing.
 */
data class PageInfo(
    val startIndex: Int,
    val endIndex: Int,
    val totalCount: Int? = null,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
) {
    val size: Int get() = (endIndex - startIndex + 1).coerceAtLeast(0)
}
