package io.github.iseaprojects.rivo.player

import android.os.Bundle
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import io.github.iseaprojects.rivo.data.extract.Chapter
import io.github.iseaprojects.rivo.data.extract.HeatmapPoint
import io.github.iseaprojects.rivo.data.extract.SponsorSegment

/** One subtitle rendition the user can switch to, taken from the current media item. */
data class SubtitleChoice(
    val language: String,
    val label: String,
    val id: String,
)

/**
 * Compose-facing snapshot of the session.
 *
 * A `MediaController` cannot be read inside composition without subscribing to it, and
 * polling every player field per frame is wasteful. The activity owns one controller and
 * this wrapper converts it into a single immutable [PlayerUiState] that Compose can diff.
 */
data class PlayerUiState(
    val title: String = "",
    val artist: String = "",
    val artworkUri: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val speed: Float = 1f,
    val queueIndex: Int = 0,
    val queueSize: Int = 0,
    val extras: PlayerExtras = PlayerExtras(),
    val subtitles: List<SubtitleChoice> = emptyList(),
    val subtitlesOff: Boolean = false,
    val subtitleLanguage: String? = null,
    /** Track the user picked; null while the backend default plays. */
    val audioTrack: String? = null,
    val audioTrackLabel: String? = null,
    val error: String? = null,
    val mediaId: String? = null,
) {
    val audioOnly: Boolean get() = extras.isAudioOnly
    val chapters: List<Chapter> get() = extras.chapters
    val sponsors: List<SponsorSegment> get() = extras.sponsors
    val heatmap: List<HeatmapPoint> get() = extras.heatmap
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

@Stable
class PlayerController(private val controller: MediaController) {

    var state by mutableStateOf(PlayerUiState())
        private set

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh()
        }
    }

    fun attach() {
        controller.addListener(listener)
        refresh()
    }

    fun detach() {
        controller.removeListener(listener)
    }

    /** Position-only update for the 250 ms tick; avoids rebuilding extras every frame. */
    fun tick() {
        val position = controller.currentPosition.coerceAtLeast(0)
        val buffered = controller.bufferedPosition.coerceAtLeast(0)
        if (position == state.positionMs && buffered == state.bufferedMs) return
        state = state.copy(
            positionMs = position,
            bufferedMs = buffered,
            isBuffering = controller.playbackState == Player.STATE_BUFFERING,
        )
    }

    fun refresh() {
        val item = controller.currentMediaItem
        val metadata = item?.mediaMetadata
        val extras = PlayerExtrasCodec.decode(
            metadata?.extras?.getString(PlayerExtras.KEY),
        )
        val textParams = controller.trackSelectionParameters
        val index = controller.currentMediaItemIndex
        // `mediaItemCount` is only the prefetch window; the request's real length travels
        // in the extras so "1 of 26" does not become "1 of 3". Next/Previous follow the
        // same rule: for the seconds before the next item is resolved, the queue length is
        // the truth, not the window.
        val queueSize = extras.queueSize.takeIf { it > 0 } ?: controller.mediaItemCount
        state = PlayerUiState(
            title = metadata?.title?.toString().orEmpty(),
            artist = metadata?.artist?.toString().orEmpty(),
            artworkUri = metadata?.artworkUri?.toString(),
            isPlaying = controller.isPlaying,
            isBuffering = controller.playbackState == Player.STATE_BUFFERING,
            hasNext = controller.hasNextMediaItem() || index + 1 < queueSize,
            hasPrevious = controller.hasPreviousMediaItem() || index > 0,
            positionMs = controller.currentPosition.coerceAtLeast(0),
            durationMs = controller.duration.takeIf { it > 0 }
                ?: metadata?.durationMs
                ?: 0L,
            bufferedMs = controller.bufferedPosition.coerceAtLeast(0),
            speed = controller.playbackParameters.speed,
            queueIndex = index + 1,
            queueSize = queueSize,
            extras = extras,
            subtitles = subtitleChoices(item),
            subtitlesOff = C.TRACK_TYPE_TEXT in textParams.disabledTrackTypes,
            subtitleLanguage = textParams.preferredTextLanguages.firstOrNull(),
            audioTrack = extras.audioTrack,
            audioTrackLabel = extras.audioTracks
                .firstOrNull { it.key == extras.audioTrack }?.label,
            error = state.error,
            mediaId = item?.mediaId,
        )
    }

    fun showError(message: String?) {
        state = state.copy(error = message)
    }

    fun clearError() {
        state = state.copy(error = null)
    }

    fun togglePlayPause() {
        when {
            controller.playbackState == Player.STATE_ENDED -> {
                controller.seekTo(0)
                controller.play()
            }

            controller.isPlaying -> controller.pause()
            else -> controller.play()
        }
    }

    fun play() = controller.play()

    fun pause() = controller.pause()

    fun seekTo(positionMs: Long) {
        // A zero duration means "not known yet", not "everything is at zero": clamping
        // against it turned every chapter tap into a seek to the start, and the stored
        // position then disagreed with the player until the next tick.
        val target = if (state.durationMs > 0) {
            positionMs.coerceIn(0, state.durationMs)
        } else {
            positionMs.coerceAtLeast(0)
        }
        controller.seekTo(target)
        state = state.copy(positionMs = target)
    }

    fun next() {
        if (controller.hasNextMediaItem()) {
            controller.seekToNextMediaItem()
            return
        }
        // The queue is longer than the prefetch window: ask the service to resolve and
        // append the next item, then advance. Checking only `hasNextMediaItem()` made Next
        // do nothing for the first seconds of every playlist while the UI said "1 of 26".
        if (state.queueIndex < state.queueSize) {
            controller.sendCustomCommand(PlayerCommands.next, Bundle.EMPTY)
        }
    }

    fun previous() {
        // Media3's own rewind-on-previous behaviour is what every other player does: the
        // first press restarts the item, a second one goes back.
        if (controller.currentPosition > PREVIOUS_RESTART_MS) {
            controller.seekTo(0)
        } else if (controller.hasPreviousMediaItem()) {
            controller.seekToPreviousMediaItem()
        }
    }

    fun setSpeed(speed: Float) {
        controller.setPlaybackSpeed(speed.coerceIn(MIN_SPEED, MAX_SPEED))
        refresh()
    }

    fun setQuality(height: Int?) {
        val args = Bundle().apply {
            putInt(PlayerCommands.EXTRA_HEIGHT, height ?: 0)
        }
        controller.sendCustomCommand(PlayerCommands.setStream, args)
    }

    fun setAudioTrack(track: String?) {
        val args = Bundle().apply {
            putString(PlayerCommands.EXTRA_AUDIO_TRACK, track.orEmpty())
        }
        controller.sendCustomCommand(PlayerCommands.setStream, args)
    }

    fun setSubtitles(choice: SubtitleChoice?) {
        val builder = controller.trackSelectionParameters.buildUpon()
        if (choice == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            builder
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setPreferredTextLanguage(choice.language)
                .setSelectUndeterminedTextLanguage(true)
        }
        controller.trackSelectionParameters = builder.build()
        refresh()
    }

    private fun subtitleChoices(item: MediaItem?): List<SubtitleChoice> =
        item?.localConfiguration?.subtitleConfigurations.orEmpty().map { config ->
            SubtitleChoice(
                language = config.language.orEmpty(),
                label = config.label
                    ?: config.language
                    ?: config.mimeType
                    ?: "",
                id = config.id ?: config.uri.toString(),
            )
        }

    private companion object {
        const val PREVIOUS_RESTART_MS = 3_000L
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4f
    }
}
