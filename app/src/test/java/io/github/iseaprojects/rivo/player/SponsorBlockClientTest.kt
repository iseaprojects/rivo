package io.github.iseaprojects.rivo.player

import io.github.iseaprojects.rivo.core.model.MediaSummary
import io.github.iseaprojects.rivo.data.extract.DeliveryKind
import io.github.iseaprojects.rivo.data.extract.StreamFormat
import io.github.iseaprojects.rivo.data.extract.StreamKind
import io.github.iseaprojects.rivo.data.extract.VideoDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SponsorBlockClientTest {

    @Test
    fun `the flat skipSegments response is decoded`() {
        // GET /api/skipSegments?videoID= answers with this shape: an array of segments.
        val body = """
            [
              {
                "segment": [12.5, 30.25],
                "UUID": "a1b2c3d4",
                "category": "sponsor",
                "videoDuration": 300,
                "actionType": "skip",
                "locked": 0,
                "votes": 3
              },
              {
                "segment": [60, 61.5],
                "UUID": "e5f6a7b8",
                "category": "selfpromo",
                "actionType": "skip"
              }
            ]
        """.trimIndent()

        val segments = SponsorBlockClient().parse(body)

        assertEquals(2, segments.size)
        assertEquals(12_500L, segments[0].startMs)
        assertEquals(30_250L, segments[0].endMs)
        assertEquals("sponsor", segments[0].category)
        assertEquals(60_000L, segments[1].startMs)
    }

    @Test
    fun `the grouped hash-prefix shape still works`() {
        val body = """
            [
              {
                "videoID": "abc12345678",
                "segments": [
                  {"category": "sponsor", "segment": [12.5, 30.25]}
                ]
              }
            ]
        """.trimIndent()

        val segments = SponsorBlockClient().parse(body)

        assertEquals(1, segments.size)
        assertEquals(12_500L, segments.first().startMs)
    }

    @Test
    fun `unknown categories and malformed entries are ignored`() {
        val body = """
            [
              {"category": "poi", "segment": [10]},
              {"category": "intro", "segment": [5, 5]},
              {"category": "", "segment": [1, 2]},
              {"category": "outro", "segment": [90, 95]}
            ]
        """.trimIndent()

        val segments = SponsorBlockClient().parse(body)

        assertEquals(1, segments.size)
        assertEquals("outro", segments.first().category)
    }

    @Test
    fun `an empty or non-array response yields no segments`() {
        assertTrue(SponsorBlockClient().parse("[]").isEmpty())
        assertTrue(SponsorBlockClient().parse("{}").isEmpty())
        assertTrue(SponsorBlockClient().parse("not json").isEmpty())
    }
}

class ExternalPlayerTest {

    private fun details(
        muxedUrl: String? = "https://cdn.test/muxed.mp4",
        videoUrl: String? = "https://cdn.test/video.mp4",
        audioUrl: String? = "https://cdn.test/audio.m4a",
        dash: String? = null,
        hls: String? = null,
    ) = VideoDetails(
        summary = MediaSummary(
            id = "abc12345678",
            url = "https://www.youtube.com/watch?v=abc12345678",
            title = "Lo-fi",
        ),
        formats = listOfNotNull(
            muxedUrl?.let {
                StreamFormat("18", StreamKind.MUXED, DeliveryKind.PROGRESSIVE, it, height = 360)
            },
            videoUrl?.let {
                StreamFormat("137", StreamKind.VIDEO_ONLY, DeliveryKind.PROGRESSIVE, it, height = 1080)
            },
            audioUrl?.let {
                StreamFormat("140", StreamKind.AUDIO_ONLY, DeliveryKind.PROGRESSIVE, it, audioBitrateKbps = 128)
            },
        ),
        dashManifestUrl = dash,
        hlsManifestUrl = hls,
    )

    @Test
    fun `muxed preference never hands a video-only url to another app`() {
        val url = ExternalPlayer.mediaUrl(details(), PlayMode.VIDEO, adaptive = false)
        assertEquals("https://cdn.test/muxed.mp4", url)
    }

    @Test
    fun `adaptive preference uses the manifest when present`() {
        val url = ExternalPlayer.mediaUrl(
            details(dash = "https://cdn.test/v.mpd"),
            PlayMode.VIDEO,
            adaptive = true,
        )
        assertEquals("https://cdn.test/v.mpd", url)
    }

    @Test
    fun `audio mode sends the audio stream`() {
        val url = ExternalPlayer.mediaUrl(details(), PlayMode.AUDIO, adaptive = false)
        assertEquals("https://cdn.test/audio.m4a", url)
    }

    @Test
    fun `a video with no muxed rendition falls back to the manifest then video`() {
        assertEquals(
            "https://cdn.test/v.m3u8",
            ExternalPlayer.mediaUrl(
                details(muxedUrl = null, hls = "https://cdn.test/v.m3u8"),
                PlayMode.VIDEO,
                adaptive = false,
            ),
        )
        assertEquals(
            "https://cdn.test/video.mp4",
            ExternalPlayer.mediaUrl(
                details(muxedUrl = null),
                PlayMode.VIDEO,
                adaptive = false,
            ),
        )
        assertNull(
            ExternalPlayer.mediaUrl(
                details(muxedUrl = null, videoUrl = null, audioUrl = null),
                PlayMode.VIDEO,
                adaptive = false,
            ),
        )
    }
}
