package io.github.iseaprojects.rivo.player

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.github.iseaprojects.rivo.core.config.ConfigKeys
import io.github.iseaprojects.rivo.data.extract.ExtractorException
import io.github.iseaprojects.rivo.data.extract.SponsorSegment
import io.github.iseaprojects.rivo.data.extract.VideoDetails
import io.github.iseaprojects.rivo.di.AppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the queue that a [PlaybackRequest] describes.
 *
 * Media3 wants a timeline of ready `MediaItem`s, but a URL only becomes playable after an
 * extractor call. Resolving a whole playlist up front would be dozens of network calls
 * before the first frame; resolving one item at a time would break next/previous in the
 * notification. The middle ground is a small prefetch window: the current item plus two
 * ahead are resolved and appended, and the window slides as playback advances.
 *
 * The coordinator also owns the two things that need extraction results after playback
 * started: quality/audio rebuilds, and SponsorBlock skipping.
 */
class PlaybackCoordinator(
    private val context: Context,
    private val player: ExoPlayer,
    private val graph: AppGraph,
    private val scope: CoroutineScope,
) {

    private val detailsById = mutableMapOf<String, VideoDetails>()
    private val segmentsById = mutableMapOf<String, List<SponsorSegment>>()
    private val sponsorBlock = SponsorBlockClient()
    private val queue = mutableListOf<QueueItem>()
    private val retried = mutableSetOf<String>()

    private var mode: PlayMode = PlayMode.VIDEO
    private var qualityHeight: Int? = null
    private var audioTrack: String? = null
    private var generation = 0
    private var prefetching = false
    private var currentSegments: List<SponsorSegment> = emptyList()
    private var lastSkipped: SponsorSegment? = null
    private var watcher: Job? = null

    val isAudioOnly: Boolean get() = mode == PlayMode.AUDIO

    init {
        watcher = scope.launch {
            while (isActive) {
                delay(SPONSOR_POLL_MS)
                maybeSkipSponsor()
            }
        }
    }

    fun start(request: PlaybackRequest) {
        val config = graph.runtime.snapshot.value
        generation++
        val gen = generation
        mode = request.mode
        qualityHeight = request.qualityHeight
        audioTrack = request.audioTrack
        detailsById.clear()
        segmentsById.clear()
        retried.clear()
        currentSegments = emptyList()
        lastSkipped = null

        val start = request.startIndex.coerceIn(0, (request.items.size - 1).coerceAtLeast(0))
        queue.clear()
        queue.addAll(request.items.drop(start))

        player.stop()
        player.clearMediaItems()

        if (queue.isEmpty()) {
            reportError("There is nothing in this queue that can be played")
            return
        }

        scope.launch {
            val first = resolve(0, gen)
            if (gen != generation) return@launch
            if (first == null) {
                reportError("Could not resolve the first item in this queue")
                return@launch
            }
            player.setMediaItem(first)
            player.prepare()
            player.playWhenReady = true
            prefetch(gen)
        }

        // `CONFIG_SUBTITLES_LANGS` is read per item, so a config change reaches the next
        // track without restarting the session.
        Log.d(TAG, "queue started: ${queue.size} items, mode=$mode, subtitles=${config.list(ConfigKeys.SUBTITLES_LANGS)}")
    }

    /** Rebuilds the current item with a new quality cap, keeping position. */
    fun setQuality(height: Int?) {
        qualityHeight = height?.takeIf { it > 0 }
        rebuildCurrent()
    }

    /** Rebuilds the current item with another audio rendition; null means original. */
    fun setAudio(track: String?) {
        audioTrack = track?.takeIf { it.isNotBlank() }
        rebuildCurrent()
    }

    private fun rebuildCurrent() {
        val index = player.currentMediaItemIndex
        val item = queue.getOrNull(index) ?: return
        val details = detailsById[item.id] ?: return
        val mediaItem = buildItem(item, details) ?: return
        val position = player.currentPosition.coerceAtLeast(0)
        val wasPlaying = player.playWhenReady
        lastSkipped = null
        // `replaceMediaItem` keeps the timeline index stable; remove+add briefly shrinks the
        // timeline, which loses the seek target and can duplicate the row in the queue.
        player.replaceMediaItem(index, mediaItem)
        player.prepare()
        player.seekTo(index, position)
        player.playWhenReady = wasPlaying
        Log.d(TAG, "stream rebuilt: index=$index position=$position count=${player.mediaItemCount}")
    }

    fun stop() {
        generation++
        queue.clear()
        detailsById.clear()
        segmentsById.clear()
        retried.clear()
        currentSegments = emptyList()
        lastSkipped = null
        player.stop()
        player.clearMediaItems()
    }

    fun onMediaItemTransition(mediaItem: MediaItem?) {
        val index = player.currentMediaItemIndex
        val item = queue.getOrNull(index)
        if (item != null) {
            currentSegments = segmentsById[item.id].orEmpty()
            lastSkipped = null
            recordRecent(item)
        }
        scope.launch { prefetch(generation) }
    }

    fun onPlaybackStateChanged(state: Int) {
        if (state != Player.STATE_ENDED) return
        if (player.mediaItemCount < queue.size) {
            scope.launch {
                prefetch(generation)
                val next = player.currentMediaItemIndex + 1
                if (next < player.mediaItemCount && player.playbackState == Player.STATE_ENDED) {
                    player.seekTo(next, 0)
                    player.play()
                }
            }
        }
    }

    fun onPlayerError(error: PlaybackException) {
        val index = player.currentMediaItemIndex
        val item = queue.getOrNull(index)
        val retryable = error.errorCode in RETRYABLE_ERRORS
        if (item != null && localUri(item.url) == null && retryable && retried.add(item.id)) {
            scope.launch { retryWithFreshUrl(index, item) }
            return
        }
        reportError(error.message ?: "Playback failed")
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            player.play()
        } else {
            player.pause()
        }
    }

    private suspend fun prefetch(gen: Int) {
        // Two triggers (start + item transition) can overlap; the window is a single
        // append-only pass, so a second concurrent run would double-add.
        if (prefetching) return
        prefetching = true
        try {
            while (gen == generation) {
                val next = player.mediaItemCount
                if (next >= queue.size) return
                if (next > player.currentMediaItemIndex + PREFETCH_AHEAD) return
                val mediaItem = resolve(next, gen)
                if (gen != generation) return
                if (mediaItem == null) {
                    // A dead item (private, removed, geo-blocked) must not stall the queue.
                    if (next < queue.size) queue.removeAt(next) else return
                    continue
                }
                player.addMediaItem(mediaItem)
            }
        } finally {
            prefetching = false
        }
    }

    private suspend fun resolve(index: Int, gen: Int): MediaItem? {
        val item = queue.getOrNull(index) ?: return null
        localUri(item.url)?.let { uri -> return localItem(item, uri) }
        val details = detailsById[item.id] ?: try {
            graph.extractors.video(item.url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ExtractorException) {
            Log.w(TAG, "cannot resolve ${item.id}: ${error.message}")
            return null
        } catch (error: Throwable) {
            Log.w(TAG, "cannot resolve ${item.id}", error)
            return null
        }
        if (gen != generation) return null
        detailsById[item.id] = details
        loadSponsors(item, details)
        return buildItem(item, details)
    }

    private fun buildItem(item: QueueItem, details: VideoDetails): MediaItem? {
        val plan = StreamPlanner.plan(details, mode, qualityHeight, audioTrack)
        if (plan == null) {
            // Nothing to play, and no exception to report: the item is dropped from the
            // queue and the UI only sees the generic "could not resolve" broadcast.
            // The format list is the whole diagnosis, so put it in logcat.
            val kinds = details.formats.joinToString(",") { "${it.kind}/${it.delivery}" }
            Log.w(
                TAG,
                "no playable format for ${item.id}: mode=$mode quality=$qualityHeight " +
                    "audio=$audioTrack formats=[$kinds]",
            )
            return null
        }
        val config = graph.runtime.snapshot.value
        val subtitleOptions = StreamPlanner.subtitles(
            details,
            config.list(ConfigKeys.SUBTITLES_LANGS),
        )
        val audioOptions = StreamPlanner.audioOptions(details)
        val extras = PlayerExtras(
            chapters = details.chapters,
            sponsors = segmentsById[item.id].orEmpty(),
            heatmap = details.heatmap,
            heights = StreamPlanner.playableHeights(details),
            audioTracks = audioOptions.map { option ->
                AudioTrackPick(
                    key = option.key,
                    label = option.label,
                    detail = option.language,
                )
            },
            audioTrack = this@PlaybackCoordinator.audioTrack,
            qualityLabel = plan.label,
            isAudioOnly = mode == PlayMode.AUDIO || !StreamPlanner.hasVideo(plan),
            queueSize = queue.size,
            webUrl = item.url,
            extractor = details.extractor,
        )

        val metadata = MediaMetadata.Builder()
            .setTitle(item.title)
            .setArtist(item.channelName)
            .setArtworkUri(item.thumbnailUrl?.takeIf { it.isNotBlank() }?.let(Uri::parse))
            .setDurationMs(item.durationSeconds?.times(1000))
            .setExtras(Bundle().apply {
                putString(PlayerExtras.KEY, PlayerExtrasCodec.encode(extras))
            })
            .build()

        val subtitles = subtitleOptions.mapIndexed { index, option ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(option.url))
                .setMimeType(option.mimeType)
                .setLanguage(option.language)
                .setLabel(option.label)
                .setId(option.url)
                .setSelectionFlags(if (index == 0) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }

        return MediaItem.Builder()
            .setMediaId(item.id)
            .setUri(placeholderUri(plan, item.id))
            .setMimeType(plan.videoMime)
            .setMediaMetadata(metadata)
            .setSubtitleConfigurations(subtitles)
            // The plan is consumed by StreamMediaSourceFactory in this process; the
            // activity never sees it and does not need to parcel it.
            .setTag(plan)
            .build()
    }

    private fun placeholderUri(plan: PlannedStream, videoId: String): String =
        "rivo-stream://${plan.delivery.name.lowercase()}/$videoId"

    /**
     * A downloaded file needs no resolution: the URI is the stream. The MIME type comes
     * from the content provider first (MediaStore knows it) and from the extension for a
     * `file://` path, so ExoPlayer picks the right extractor without sniffing.
     */
    private fun localItem(item: QueueItem, uri: Uri): MediaItem {
        val mime = context.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(uri.lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase())
        val extras = PlayerExtras(
            isAudioOnly = mode == PlayMode.AUDIO,
            queueSize = queue.size,
        )
        val metadata = MediaMetadata.Builder()
            .setTitle(item.title)
            .setExtras(Bundle().apply {
                putString(PlayerExtras.KEY, PlayerExtrasCodec.encode(extras))
            })
            .build()
        return MediaItem.Builder()
            .setMediaId(item.id)
            .setUri(uri)
            .setMimeType(mime)
            .setMediaMetadata(metadata)
            .build()
    }

    /** `content://` (MediaStore, SAF, FileProvider) or `file://` (legacy shared storage). */
    private fun localUri(url: String): Uri? {
        if (!url.startsWith("content://", ignoreCase = true) &&
            !url.startsWith("file://", ignoreCase = true)
        ) {
            return null
        }
        return runCatching { Uri.parse(url) }.getOrNull()
    }

    private fun loadSponsors(item: QueueItem, details: VideoDetails) {
        val config = graph.runtime.snapshot.value
        if (!config.bool(ConfigKeys.SPONSORBLOCK, true)) return
        // NewPipe's `streamSegments` are chapters, not SponsorBlock categories (D37); the
        // API remains the only source of sponsor segments.
        val categories = config.list(ConfigKeys.SPONSORBLOCK_CATEGORIES)
            .ifEmpty { DEFAULT_CATEGORIES }
        scope.launch {
            val result = runCatching { sponsorBlock.segments(item.id, categories) }
                .getOrDefault(emptyList())
            if (result.isEmpty()) return@launch
            segmentsById[item.id] = result
            if (player.currentMediaItemIndex >= 0 &&
                queue.getOrNull(player.currentMediaItemIndex)?.id == item.id
            ) {
                currentSegments = result
            }
        }
    }

    private fun maybeSkipSponsor() {
        val config = graph.runtime.snapshot.value
        if (!config.bool(ConfigKeys.SPONSORBLOCK, true)) return
        if (!player.isPlaying) return
        val position = player.currentPosition
        val segment = currentSegments.firstOrNull { segment ->
            val end = segment.endMs
            position >= segment.startMs && (end == null || position < end)
        } ?: return
        if (segment === lastSkipped) return
        lastSkipped = segment
        val end = segment.endMs ?: (position + SPONSOR_UNKNOWN_END_MS)
        player.seekTo(player.currentMediaItemIndex, end + SPONSOR_MARGIN_MS)
    }

    private suspend fun retryWithFreshUrl(index: Int, item: QueueItem) {
        val details = try {
            graph.extractors.fresh(item.url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            reportError(error.message ?: "Playback failed")
            return
        }
        detailsById[item.id] = details
        val mediaItem = buildItem(item, details) ?: return
        val position = player.currentPosition.coerceAtLeast(0)
        // The queue may have shifted while the request was in flight.
        val current = queue.indexOf(item).takeIf { it >= 0 } ?: index
        if (current >= player.mediaItemCount) return
        player.replaceMediaItem(current, mediaItem)
        player.prepare()
        player.seekTo(current, position)
        player.playWhenReady = true
    }

    private fun recordRecent(item: QueueItem) {
        val limit = graph.runtime.snapshot.value
            .int(ConfigKeys.NO_OF_RECENT, 10)
            .coerceIn(1, 100)
        scope.launch {
            runCatching { graph.library.recordRecent(item.toSummary(), limit) }
        }
    }

    private fun reportError(message: String) {
        val intent = android.content.Intent(PlayerEvents.ACTION_ERROR)
            .setPackage(context.packageName)
            .putExtra(PlayerEvents.EXTRA_MESSAGE, message)
        context.sendBroadcast(intent)
    }

    private companion object {
        const val TAG = "rivo-player"
        const val PREFETCH_AHEAD = 2
        const val SPONSOR_POLL_MS = 400L
        const val SPONSOR_UNKNOWN_END_MS = 1000L
        const val SPONSOR_MARGIN_MS = 200L
        val DEFAULT_CATEGORIES = listOf("sponsor", "selfpromo", "interaction")

        val RETRYABLE_ERRORS = setOf(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        )
    }
}
