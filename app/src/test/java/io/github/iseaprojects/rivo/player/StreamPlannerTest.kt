package io.github.iseaprojects.rivo.player

import io.github.iseaprojects.rivo.core.model.MediaSummary
import io.github.iseaprojects.rivo.data.extract.DeliveryKind
import io.github.iseaprojects.rivo.data.extract.StreamFormat
import io.github.iseaprojects.rivo.data.extract.StreamKind
import io.github.iseaprojects.rivo.data.extract.SubtitleTrack
import io.github.iseaprojects.rivo.data.extract.VideoDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Format selection is the part of playback that used to lose audio: YouTube serves the
 * best picture as a video-only DASH rendition with the audio in a separate response. These
 * tests pin the decisions that keep the pair together.
 */
class StreamPlannerTest {

    private fun muxed(
        height: Int,
        url: String = "muxed-$height",
        delivery: DeliveryKind = DeliveryKind.PROGRESSIVE,
    ) = StreamFormat(
        formatId = "m$height",
        kind = StreamKind.MUXED,
        delivery = delivery,
        url = url,
        mimeType = "video/mp4",
        height = height,
        width = height * 16 / 9,
        totalBitrateKbps = height,
    )

    private fun video(
        height: Int,
        url: String = "video-$height",
        delivery: DeliveryKind = DeliveryKind.PROGRESSIVE,
    ) = StreamFormat(
        formatId = "v$height",
        kind = StreamKind.VIDEO_ONLY,
        delivery = delivery,
        url = url,
        mimeType = "video/mp4",
        height = height,
        width = height * 16 / 9,
    )

    private fun audio(
        bitrate: Int = 128,
        language: String = "en",
        url: String = "audio-$bitrate",
    ) = StreamFormat(
        formatId = "a$bitrate",
        kind = StreamKind.AUDIO_ONLY,
        delivery = DeliveryKind.PROGRESSIVE,
        url = url,
        mimeType = "audio/mp4",
        audioBitrateKbps = bitrate,
        audioLanguage = language,
        isOriginalAudio = true,
    )

    private fun details(
        formats: List<StreamFormat>,
        subtitles: List<SubtitleTrack> = emptyList(),
        dash: String? = null,
        hls: String? = null,
        live: Boolean = false,
    ) = VideoDetails(
        summary = MediaSummary(
            id = "abc12345678",
            url = "https://www.youtube.com/watch?v=abc12345678",
            title = "Lo-fi",
            isLive = live,
        ),
        formats = formats,
        subtitles = subtitles,
        dashManifestUrl = dash,
        hlsManifestUrl = hls,
    )

    @Test
    fun `muxed wins when it is as tall as the best video stream`() {
        val plan = StreamPlanner.plan(
            details(listOf(muxed(720), video(720), audio())),
            PlayMode.VIDEO,
        )

        assertEquals("muxed-720", plan?.videoUrl)
        assertNull(plan?.audioUrl)
        assertEquals(DeliveryKind.PROGRESSIVE, plan?.delivery)
    }

    @Test
    fun `separated audio is merged when the video stream is taller`() {
        val plan = StreamPlanner.plan(
            details(listOf(muxed(360), video(1080), audio())),
            PlayMode.VIDEO,
        )

        assertEquals("video-1080", plan?.videoUrl)
        assertEquals("audio-128", plan?.audioUrl)
        assertTrue(plan?.isMerged == true)
        assertEquals(1080, plan?.height)
    }

    @Test
    fun `live streams use the DASH manifest`() {
        val plan = StreamPlanner.plan(
            details(listOf(muxed(720), audio()), dash = "https://example.test/live.mpd", live = true),
            PlayMode.VIDEO,
        )

        assertEquals(DeliveryKind.DASH, plan?.delivery)
        assertEquals("https://example.test/live.mpd", plan?.manifestUrl)
        assertTrue(plan?.isLive == true)
    }

    @Test
    fun `quality cap falls back to a smaller muxed rendition`() {
        val plan = StreamPlanner.plan(
            details(listOf(muxed(720), muxed(1080), video(1080), audio())),
            PlayMode.VIDEO,
            maxHeight = 720,
        )

        assertEquals("muxed-720", plan?.videoUrl)
        assertNull(plan?.audioUrl)
    }

    @Test
    fun `audio mode never requests a surface`() {
        val plan = StreamPlanner.plan(
            details(listOf(muxed(720), video(1080), audio())),
            PlayMode.AUDIO,
        )

        assertNull(plan?.videoUrl)
        assertEquals("audio-128", plan?.audioUrl)
        assertFalse(StreamPlanner.hasVideo(plan!!))
    }

    @Test
    fun `audio mode prefers the requested language track`() {
        val spanish = audio(bitrate = 96, language = "es", url = "audio-es").copy(
            isOriginalAudio = false,
        )
        val plan = StreamPlanner.plan(
            details(listOf(audio(), spanish)),
            PlayMode.AUDIO,
            audioTrack = "es",
        )

        assertEquals("audio-es", plan?.audioUrl)
    }

    @Test
    fun `quality options list playable heights best first`() {
        val options = StreamPlanner.qualityOptions(
            details(listOf(muxed(720), muxed(1080), video(1440), audio())),
        )

        assertEquals(listOf("1440p", "1080p", "720p"), options)
    }

    @Test
    fun `subtitles keep only formats exoplayer can render and the wanted languages`() {
        val subtitles = listOf(
            SubtitleTrack("en", "English", "https://t/en.vtt", container = "vtt"),
            SubtitleTrack("en", "English (auto)", "https://t/en.json3", container = "json3", isAutoGenerated = true),
            SubtitleTrack("es", "Español", "https://t/es.vtt", container = "vtt"),
            SubtitleTrack("pt", "Português", "https://t/pt.srt", container = "srt"),
        )

        val chosen = StreamPlanner.subtitles(details(emptyList(), subtitles), listOf("es"))

        assertEquals(1, chosen.size)
        assertEquals("es", chosen.first().language)
        assertEquals("text/vtt", chosen.first().mimeType)
    }

    @Test
    fun `manual captions win over auto-generated ones for the same language`() {
        val subtitles = listOf(
            SubtitleTrack("en", "English (auto)", "https://t/auto.vtt", isAutoGenerated = true),
            SubtitleTrack("en", "English", "https://t/manual.vtt"),
        )

        val chosen = StreamPlanner.subtitles(details(emptyList(), subtitles), emptyList())

        assertEquals(1, chosen.size)
        assertEquals("https://t/manual.vtt", chosen.first().url)
    }

    @Test
    fun `hls manifest is used when dash is missing`() {
        val plan = StreamPlanner.plan(
            details(emptyList(), hls = "https://example.test/live.m3u8", live = true),
            PlayMode.VIDEO,
        )

        assertEquals(DeliveryKind.HLS, plan?.delivery)
        assertEquals("https://example.test/live.m3u8", plan?.manifestUrl)
    }

    @Test
    fun `no playable stream returns null rather than a broken item`() {
        val plan = StreamPlanner.plan(
            details(listOf(muxed(720).copy(url = null, manifestUrl = null))),
            PlayMode.VIDEO,
        )

        assertNull(plan)
    }
}
