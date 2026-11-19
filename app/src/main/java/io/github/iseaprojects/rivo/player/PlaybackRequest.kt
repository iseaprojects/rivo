package io.github.iseaprojects.rivo.player

import io.github.iseaprojects.rivo.core.model.MediaSummary
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Video or audio-only playback.
 *
 * The distinction is not cosmetic: audio mode picks the best audio rendition and never
 * attaches a surface, which is what makes background playback and the notification's
 * artwork correct on a locked phone.
 */
@Serializable
enum class PlayMode { VIDEO, AUDIO }

/**
 * The subset of [MediaSummary] the player needs, kept separate so the queue survives
 * an Intent without serialising extractor-facing models.
 */
@Serializable
data class QueueItem(
    val id: String,
    val url: String,
    val title: String,
    val channelId: String? = null,
    val channelName: String? = null,
    val channelUrl: String? = null,
    val thumbnailUrl: String? = null,
    val durationSeconds: Long? = null,
    val isLive: Boolean = false,
    val playlistId: String? = null,
    val playlistTitle: String? = null,
)

/** One playback session: a queue, where to start, and the user's stream preferences. */
@Serializable
data class PlaybackRequest(
    val items: List<QueueItem>,
    val startIndex: Int = 0,
    val mode: PlayMode = PlayMode.VIDEO,
    val title: String = "",
    val qualityHeight: Int? = null,
    val audioTrack: String? = null,
) {
    val current: QueueItem? get() = items.getOrNull(startIndex)
}

fun MediaSummary.toQueueItem(playlistTitle: String? = null): QueueItem = QueueItem(
    id = id,
    url = url,
    title = title,
    channelId = channelId,
    channelName = channelName,
    channelUrl = channelUrl,
    thumbnailUrl = thumbnailUrl,
    durationSeconds = durationSeconds,
    isLive = isLive,
    playlistId = playlistId,
    playlistTitle = playlistTitle,
)

fun QueueItem.toSummary(): MediaSummary = MediaSummary(
    id = id,
    url = url,
    title = title,
    provider = io.github.iseaprojects.rivo.core.model.MediaProvider.fromUrl(url),
    channelId = channelId,
    channelName = channelName,
    channelUrl = channelUrl,
    thumbnailUrl = thumbnailUrl,
    durationSeconds = durationSeconds,
    isLive = isLive,
    playlistId = playlistId,
)

object PlaybackRequestCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    fun encode(request: PlaybackRequest): String =
        json.encodeToString(PlaybackRequest.serializer(), request)

    fun decode(text: String?): PlaybackRequest? {
        if (text.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(PlaybackRequest.serializer(), text) }
            .getOrNull()
            ?.takeIf { it.items.isNotEmpty() }
    }
}
