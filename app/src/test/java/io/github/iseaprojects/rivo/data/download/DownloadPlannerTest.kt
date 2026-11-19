package io.github.iseaprojects.rivo.data.download

import io.github.iseaprojects.rivo.core.model.MediaSummary
import io.github.iseaprojects.rivo.data.extract.DeliveryKind
import io.github.iseaprojects.rivo.data.extract.StreamFormat
import io.github.iseaprojects.rivo.data.extract.StreamKind
import io.github.iseaprojects.rivo.data.extract.VideoDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Download format choice. The player can mux at playback time; a downloaded file cannot,
 * so these rules decide what a file is before a single byte moves.
 */
class DownloadPlannerTest {

    private fun summary() = MediaSummary(
        id = "abc",
        url = "https://www.youtube.com/watch?v=abc",
        title = "Test",
    )

    private fun video(
        height: Int,
        delivery: DeliveryKind = DeliveryKind.PROGRESSIVE,
        url: String? = "v$height",
    ) = StreamFormat(
        formatId = "v$height",
        kind = StreamKind.VIDEO_ONLY,
        delivery = delivery,
        url = url,
        mimeType = "video/mp4",
        container = "mp4",
        height = height,
        videoBitrateKbps = height,
    )

    private fun muxed(
        height: Int,
        url: String? = "m$height",
        delivery: DeliveryKind = DeliveryKind.PROGRESSIVE,
    ) = StreamFormat(
        formatId = "m$height",
        kind = StreamKind.MUXED,
        delivery = delivery,
        url = url,
        mimeType = "video/mp4",
        container = "mp4",
        height = height,
        totalBitrateKbps = height,
    )

    private fun audio(
        mime: String? = "audio/mp4",
        container: String? = "m4a",
        bitrate: Int = 128,
        track: String? = "en",
        original: Boolean = true,
        url: String? = "a$bitrate",
    ) = StreamFormat(
        formatId = "a$bitrate",
        kind = StreamKind.AUDIO_ONLY,
        delivery = DeliveryKind.PROGRESSIVE,
        url = url,
        mimeType = mime,
        container = container,
        audioBitrateKbps = bitrate,
        audioTrackId = track,
        isOriginalAudio = original,
    )

    private fun details(vararg formats: StreamFormat) = VideoDetails(
        summary = summary(),
        formats = formats.toList(),
    )

    @Test
    fun `quality cap prefers the muxed rendition inside the cap`() {
        val plan = DownloadPlanner.plan(
            details(muxed(720), video(1080), audio()),
            DownloadKind.VIDEO,
        )
        // 1080p video-only beats 720p muxed, so this one merges; pinned separately below.
        assertTrue(plan is DownloadPlan.Merge)

        val capped = DownloadPlanner.plan(
            details(muxed(720), video(1080), audio()),
            DownloadKind.VIDEO,
            maxHeight = 720,
        )
        assertEquals(DownloadPlan.Direct(muxed(720)), capped)
    }

    @Test
    fun `without an MP4 audio track the planner falls back to the muxed rendition`() {
        val plan = DownloadPlanner.plan(
            details(muxed(720), video(1080), audio("audio/webm", "webm", url = "opus")),
            DownloadKind.VIDEO,
        )
        // Opus cannot live in MP4 here (D41); a silent 1080p file would be worse than a
        // playable 720p one, so the higher rendition is refused rather than merged.
        assertEquals(DownloadPlan.Direct(muxed(720)), plan)
        assertNull(DownloadPlanner.bestMp4Audio(details(audio("audio/webm", "webm"))))
    }

    @Test
    fun `merge never pairs the video with a WebM audio track`() {
        val mp4 = audio("audio/mp4", "m4a", bitrate = 160, url = "m4a")
        val opus = audio("audio/webm", "webm", bitrate = 50)
        val plan = DownloadPlanner.plan(
            details(muxed(360), video(1080), opus, mp4),
            DownloadKind.VIDEO,
        )
        assertEquals(DownloadPlan.Merge(video(1080), mp4), plan)
    }

    @Test
    fun `audio download takes the best audio rendition`() {
        val plan = DownloadPlanner.plan(
            details(muxed(720), audio(bitrate = 64), audio(bitrate = 128)),
            DownloadKind.AUDIO,
        )
        assertEquals(DownloadPlan.Direct(audio(bitrate = 128)), plan)
    }

    @Test
    fun `audio download falls back to the muxed track`() {
        val plan = DownloadPlanner.plan(details(muxed(360)), DownloadKind.AUDIO)
        assertEquals(DownloadPlan.Direct(muxed(360)), plan)
    }

    @Test
    fun `audio download prefers the configured container`() {
        val m4a = audio(bitrate = 128, url = "m4a")
        val opus = audio("audio/webm", "webm", bitrate = 160, url = "opus")
        // `CONFIG_AUDIO_FORMAT=m4a` is the default because the music library accepts it;
        // the higher-bitrate Opus stream must not win on bitrate alone.
        assertEquals(
            DownloadPlan.Direct(m4a),
            DownloadPlanner.plan(details(muxed(720), m4a, opus), DownloadKind.AUDIO),
        )
        assertEquals(
            DownloadPlan.Direct(opus),
            DownloadPlanner.plan(
                details(muxed(720), m4a, opus),
                DownloadKind.AUDIO,
                audioFormat = "opus",
            ),
        )
    }

    @Test
    fun `audio download uses Opus when no MP4 track exists`() {
        val opus = audio("audio/webm", "webm", bitrate = 160, url = "opus")
        assertEquals(
            DownloadPlan.Direct(opus),
            DownloadPlanner.plan(details(muxed(720), opus), DownloadKind.AUDIO),
        )
    }

    @Test
    fun `an unknown audio format asks for the best rendition`() {
        val m4a = audio(bitrate = 96, url = "m4a")
        val opus = audio("audio/webm", "webm", bitrate = 160, url = "opus")
        assertEquals(
            DownloadPlan.Direct(opus),
            DownloadPlanner.plan(details(m4a, opus), DownloadKind.AUDIO, audioFormat = "best"),
        )
    }

    @Test
    fun `a DASH rendition with no URL and no manifest cannot be planned`() {
        assertNull(
            DownloadPlanner.plan(
                details(video(1080, delivery = DeliveryKind.DASH, url = null)),
                DownloadKind.VIDEO,
            ),
        )
    }

    @Test
    fun `a format that only exposes a manifest URL becomes a manifest plan`() {
        val format = StreamFormat(
            formatId = "dash",
            kind = StreamKind.VIDEO_ONLY,
            delivery = DeliveryKind.DASH,
            url = null,
            manifestUrl = "https://example.test/stream.mpd",
            mimeType = "application/dash+xml",
        )
        assertEquals(
            DownloadPlan.Manifest("https://example.test/stream.mpd", "application/dash+xml"),
            DownloadPlanner.plan(details(format), DownloadKind.VIDEO),
        )
    }

    private fun webmVideo(height: Int) = StreamFormat(
        formatId = "w$height",
        kind = StreamKind.VIDEO_ONLY,
        delivery = DeliveryKind.PROGRESSIVE,
        url = "w$height",
        mimeType = "video/webm",
        container = "webm",
        codec = "vp9",
        height = height,
    )

    @Test
    fun `a WebM video-only rendition is never muxed into MP4`() {
        // The output of a merge is an MP4 (D41); VP9 in MP4 is not portable, so a higher
        // WebM rendition must lose to the playable muxed one.
        assertEquals(
            DownloadPlan.Direct(muxed(720)),
            DownloadPlanner.plan(
                details(muxed(720), webmVideo(1080), audio()),
                DownloadKind.VIDEO,
            ),
        )
    }

    @Test
    fun `the best MP4 video is chosen for a merge`() {
        assertEquals(
            DownloadPlan.Merge(video(1080), audio()),
            DownloadPlanner.plan(
                details(muxed(720), webmVideo(2160), video(1080), audio()),
                DownloadKind.VIDEO,
            ),
        )
    }

    @Test
    fun `MP4 video detection accepts containers and MIME types`() {
        assertTrue(DownloadPlanner.isMp4Video(video(1080)))
        assertTrue(!DownloadPlanner.isMp4Video(webmVideo(1080)))
    }

    @Test
    fun `dash manifest URL wins over an HLS one`() {
        val details = VideoDetails(
            summary = summary(),
            formats = listOf(video(1080, delivery = DeliveryKind.DASH, url = null)),
            dashManifestUrl = "https://example.test/manifest.mpd",
            hlsManifestUrl = "https://example.test/manifest.m3u8",
        )
        assertEquals(
            DownloadPlan.Manifest("https://example.test/manifest.mpd", "application/dash+xml"),
            DownloadPlanner.plan(details, DownloadKind.VIDEO),
        )
    }

    @Test
    fun `hls manifest is used when there is no DASH one`() {
        val details = VideoDetails(
            summary = summary(),
            formats = listOf(video(1080, delivery = DeliveryKind.HLS, url = null)),
            hlsManifestUrl = "https://example.test/live.m3u8",
        )
        assertEquals(
            DownloadPlan.Manifest("https://example.test/live.m3u8", "application/x-mpegURL"),
            DownloadPlanner.plan(details, DownloadKind.VIDEO),
        )
    }

    @Test
    fun `no playable format yields no plan instead of an empty file`() {
        assertNull(DownloadPlanner.plan(details(), DownloadKind.VIDEO))
    }

    @Test
    fun `video without muxable audio is a definitive failure`() {
        val error = assertThrows(DownloadException::class.java) {
            DownloadPlanner.plan(details(muxed(720, url = null), video(1080)), DownloadKind.VIDEO)
        }
        assertTrue(!error.retryable)
    }

    @Test
    fun `best MP4 audio prefers the original track then the bitrate`() {
        val dub = audio(bitrate = 192, track = "es", original = false)
        val original = audio(bitrate = 128, track = "en", original = true)
        val details = details(original, dub)
        assertEquals(original, DownloadPlanner.bestMp4Audio(details))
    }

    @Test
    fun `height cap reads the configured quality string`() {
        assertEquals(1080, DownloadPlanner.heightCap("1080p"))
        assertEquals(720, DownloadPlanner.heightCap("720p60"))
        assertEquals(144, DownloadPlanner.heightCap("144"))
        assertNull(DownloadPlanner.heightCap("best"))
        assertNull(DownloadPlanner.heightCap(null))
        assertNull(DownloadPlanner.heightCap("9999"))
    }

    @Test
    fun `mp4 audio detection accepts containers and MIME types`() {
        assertTrue(DownloadPlanner.isMp4Audio(audio("audio/mp4", "m4a")))
        assertTrue(DownloadPlanner.isMp4Audio(audio(null, "mp4")))
        assertTrue(!DownloadPlanner.isMp4Audio(audio("audio/webm", "webm")))
        assertTrue(!DownloadPlanner.isMp4Audio(audio("audio/ogg", "opus")))
    }
}
