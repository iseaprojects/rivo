package io.github.iseaprojects.rivo.data.download

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Joins a video-only rendition and its audio track into one file without re-encoding.
 *
 * `StreamPlanner` hands the player a `MergingMediaSource` for the same reason this class
 * exists: the best quality is often a video-only rendition plus a separate audio track. A
 * download has to leave one playable file, so the compressed samples are copied into a
 * new container — `MediaExtractor` reads them, `MediaMuxer` writes them, and no frame is
 * decoded. mp4parser's box rewrite would do the same job with a 1 MB dependency and a
 * container-specific code path; this works for every container the platform can read.
 *
 * Only MP4 output is produced. An Opus/WebM audio track cannot be muxed into MP4, so the
 * engine prefers an AAC rendition before calling this (D41).
 */
class Mp4Muxer {

    /**
     * Writes [video] + [audio] into [output]. Returns false when either file has no usable
     * track or the platform refuses the combination; the caller then falls back to a
     * muxed rendition instead of leaving two orphan files behind.
     */
    fun mux(video: File, audio: File, output: File): Boolean {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            videoExtractor.setDataSource(video.absolutePath)
            audioExtractor.setDataSource(audio.absolutePath)

            val videoTrack = videoExtractor.firstTrackOf("video/") ?: return false
            val audioTrack = audioExtractor.firstTrackOf("audio/") ?: return false
            videoExtractor.selectTrack(videoTrack)
            audioExtractor.selectTrack(audioTrack)

            val videoFormat = videoExtractor.getTrackFormat(videoTrack)
            val audioFormat = audioExtractor.getTrackFormat(audioTrack)

            // Both tracks are re-based on the earliest sample so the pair starts at zero
            // together. Rebasing them independently would fix a two-second drift and
            // introduce a lip-sync error; keeping one offset keeps their relationship.
            val videoStart = videoExtractor.firstSampleTimeUs()
            val audioStart = audioExtractor.firstSampleTimeUs()
            val offset = min(videoStart, audioStart)

            val target = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = target
            if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                runCatching { target.setOrientationHint(videoFormat.getInteger(MediaFormat.KEY_ROTATION)) }
            }
            val videoOut = target.addTrack(videoFormat)
            val audioOut = target.addTrack(audioFormat)

            target.start()
            copySamples(videoExtractor, target, videoOut, offset)
            copySamples(audioExtractor, target, audioOut, offset)
            target.stop()
            true
        } catch (_: RuntimeException) {
            // MediaMuxer throws IllegalStateException/IllegalArgumentException for a
            // container or codec pair it cannot write. That is a "try the muxed
            // rendition" signal, not a crash.
            runCatching { output.delete() }
            false
        } catch (_: java.io.IOException) {
            runCatching { output.delete() }
            false
        } finally {
            runCatching { muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }
    }

    private fun copySamples(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        track: Int,
        offsetUs: Long,
    ) {
        val buffer = ByteBuffer.allocateDirect(BUFFER_BYTES)
        val info = android.media.MediaCodec.BufferInfo()
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = max(0, extractor.sampleTime - offsetUs)
            info.flags = sampleFlags(extractor.sampleFlags)
            muxer.writeSampleData(track, buffer, info)
            if (!extractor.advance()) break
        }
    }

    private fun sampleFlags(flags: Int): Int {
        var mapped = 0
        if (flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            mapped = mapped or android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        if (flags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
            mapped = mapped or android.media.MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        return mapped
    }

    private fun MediaExtractor.firstTrackOf(mimePrefix: String): Int? {
        for (index in 0 until trackCount) {
            val mime = getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(mimePrefix)) return index
        }
        return null
    }

    private fun MediaExtractor.firstSampleTimeUs(): Long {
        seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        val time = sampleTime
        return if (time >= 0) time else 0L
    }

    private companion object {
        const val BUFFER_BYTES = 1 shl 20
    }
}
