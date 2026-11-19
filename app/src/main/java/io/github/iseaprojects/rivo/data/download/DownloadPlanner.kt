package io.github.iseaprojects.rivo.data.download

import io.github.iseaprojects.rivo.data.extract.DeliveryKind
import io.github.iseaprojects.rivo.data.extract.StreamFormat
import io.github.iseaprojects.rivo.data.extract.VideoDetails

/** What the downloader has to do for one item. */
sealed interface DownloadPlan {

    /** A single progressive file: a muxed rendition, or an audio-only one. */
    data class Direct(val format: StreamFormat) : DownloadPlan

    /** Two progressive renditions that need joining before they are playable. */
    data class Merge(val video: StreamFormat, val audio: StreamFormat) : DownloadPlan

    /** Only a manifest is available; the platform HTTP path cannot fetch segment lists. */
    data class Manifest(val url: String, val mimeType: String?) : DownloadPlan
}

/**
 * Format choice for downloads, kept pure so the rules are testable.
 *
 * The player can merge at playback time; a file has to be merged at download time, which
 * is why this is not just `StreamPlanner.plan`. The order is:
 *
 *  1. video-only higher than the best muxed → merge it with the best *MP4* audio track.
 *  2. best muxed → direct.
 *  3. audio-only rendition → direct (audio mode, and for sites that ship audio only).
 *  4. a manifest → yt-dlp, when the build has it.
 *
 * Step 1 deliberately refuses Opus/WebM audio: `MediaMuxer` writes MP4 here, and an
 * Opus track inside MP4 is not portable (D41). Falling back to muxed-quality is worse
 * than the alternative — a file that plays nowhere.
 */
object DownloadPlanner {

    fun plan(
        details: VideoDetails,
        kind: DownloadKind,
        maxHeight: Int? = null,
        audioFormat: String = "m4a",
    ): DownloadPlan? {
        if (kind == DownloadKind.AUDIO) {
            audioFor(details, audioFormat)?.let { return DownloadPlan.Direct(it) }
            details.bestMuxed(maxHeight)?.takeIf { it.url != null }?.let { return DownloadPlan.Direct(it) }
            return manifest(details)
        }

        val muxed = details.bestMuxed(maxHeight)
        // Joining tracks writes an MP4, so both halves must be MP4: a VP9/AV1 WebM
        // video joined with AAC is a file that plays on very little (D41).
        val video = bestMp4Video(details, maxHeight)
        if (muxed?.url != null && (video == null || (muxed.height ?: 0) >= (video.height ?: 0))) {
            return DownloadPlan.Direct(muxed)
        }
        if (video?.url != null) {
            val audio = bestMp4Audio(details)
            if (audio?.url != null) return DownloadPlan.Merge(video, audio)
        }
        if (muxed?.url != null) return DownloadPlan.Direct(muxed)
        if (video?.url != null) {
            // Video without any audio the muxer can carry: the file would be silent, so
            // this is a failure the engine reports rather than a quiet download.
            throw DownloadException(
                "no audio track in a container that can be joined with the video; " +
                    "lower the video quality or use the yt-dlp build",
                retryable = false,
            )
        }
        return manifest(details)
    }

    /** Best MP4 video rendition: the only kind `MediaMuxer` can carry into the output. */
    fun bestMp4Video(details: VideoDetails, maxHeight: Int? = null): StreamFormat? = details
        .videoOnly()
        .filter { it.url != null && it.delivery == DeliveryKind.PROGRESSIVE && isMp4Video(it) }
        .filter { maxHeight == null || (it.height ?: 0) <= maxHeight }
        .sortedWith(compareByDescending<StreamFormat> { it.height ?: 0 }
            .thenByDescending { it.fps ?: 0 }
            .thenByDescending { it.videoBitrateKbps ?: 0 })
        .firstOrNull()

    fun isMp4Video(format: StreamFormat): Boolean = when {
        format.mimeType == "video/mp4" || format.mimeType == "video/x-m4v" -> true
        format.container?.trim()?.lowercase() in setOf("mp4", "m4v") -> true
        else -> false
    }

    /** Best audio rendition in an MP4 container, preferring the original track. */
    fun bestMp4Audio(details: VideoDetails): StreamFormat? {
        val original = details.bestAudio()
        val candidates = details.audioOnly()
            .filter { it.url != null && isMp4Audio(it) }
            .filter { original?.audioTrackKey == null || it.audioTrackKey == original.audioTrackKey }
            .sortedWith(compareByDescending<StreamFormat> { it.isOriginalAudio }
                .thenByDescending { it.isDefaultAudio }
                .thenByDescending { it.audioBitrateKbps ?: 0 })
        return candidates.firstOrNull()
    }

    /**
     * Audio rendition for an audio-only download, honouring `CONFIG_AUDIO_FORMAT`.
     *
     * The default is `m4a` for a practical reason: the music library's `MediaStore.Audio`
     * collection rejects `audio/webm`, so an Opus track can fail to be filed at all on
     * some devices. Opus is still selectable, and the writer falls back to the downloads
     * collection when the music library refuses the type.
     */
    fun audioFor(details: VideoDetails, format: String): StreamFormat? {
        val pool = details.audioOnly().filter { it.url != null }
        if (pool.isEmpty()) return null
        val wanted = format.trim().lowercase().removePrefix(".")
        val matching = when (wanted) {
            "m4a", "mp4", "m4b", "aac" -> pool.filter { isMp4Audio(it) }
            "opus", "webm", "weba", "ogg", "oga" -> pool.filter { !isMp4Audio(it) }
            else -> emptyList()
        }
        return (matching.ifEmpty { pool })
            .sortedWith(compareByDescending<StreamFormat> { it.isOriginalAudio }
                .thenByDescending { it.isDefaultAudio }
                .thenByDescending { it.audioBitrateKbps ?: 0 })
            .firstOrNull()
    }

    /** `CONFIG_VIDEO_FORMAT`: `best`, `1080`, `1080p`, `720p60` → height cap. */
    fun heightCap(configured: String?): Int? {
        val digits = configured.orEmpty().trim().lowercase().removeSuffix("p").takeWhile { it.isDigit() }
        val value = digits.toIntOrNull() ?: return null
        return value.takeIf { it in 144..4320 }
    }

    fun isMp4Audio(format: StreamFormat): Boolean = when {
        format.mimeType == "audio/mp4" || format.mimeType == "audio/m4a" -> true
        format.container?.trim()?.lowercase() in setOf("m4a", "mp4", "m4v") -> true
        else -> false
    }

    private fun manifest(details: VideoDetails): DownloadPlan? {
        val dash = details.dashManifestUrl?.takeIf { it.isNotBlank() }
        val hls = details.hlsManifestUrl?.takeIf { it.isNotBlank() }
        val manifestFormat = (details.muxed() + details.videoOnly() + details.audioOnly())
            .firstOrNull { it.delivery == DeliveryKind.DASH || it.delivery == DeliveryKind.HLS }
        return when {
            dash != null -> DownloadPlan.Manifest(dash, "application/dash+xml")
            hls != null -> DownloadPlan.Manifest(hls, "application/x-mpegURL")
            manifestFormat?.manifestUrl != null || manifestFormat?.url != null -> DownloadPlan.Manifest(
                manifestFormat.manifestUrl ?: manifestFormat.url.orEmpty(),
                manifestFormat.mimeType ?: if (manifestFormat.delivery == DeliveryKind.HLS) {
                    "application/x-mpegURL"
                } else {
                    "application/dash+xml"
                },
            )
            else -> null
        }
    }
}
