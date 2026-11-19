package io.github.iseaprojects.rivo.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRequestCodecTest {

    private val request = PlaybackRequest(
        items = listOf(
            QueueItem(
                id = "abc12345678",
                url = "https://www.youtube.com/watch?v=abc12345678",
                title = "Lo-fi beats — 24/7",
                channelName = "Chill",
                thumbnailUrl = "https://i.ytimg.com/vi/abc12345678/hqdefault.jpg",
                durationSeconds = 3_723,
                playlistId = "PL1",
                playlistTitle = "Focus",
            ),
            QueueItem(id = "def", url = "https://example.test/def", title = "Second"),
        ),
        startIndex = 1,
        mode = PlayMode.AUDIO,
        title = "Focus",
    )

    @Test
    fun `round trip keeps the queue and the stream preferences`() {
        val decoded = PlaybackRequestCodec.decode(PlaybackRequestCodec.encode(request))

        assertEquals(request, decoded)
        assertEquals(PlayMode.AUDIO, decoded?.mode)
        assertEquals("Second", decoded?.current?.title)
        assertEquals(3_723L, decoded?.items?.first()?.durationSeconds)
    }

    @Test
    fun `unknown keys from a newer version are ignored`() {
        val json = PlaybackRequestCodec.encode(request)
            .replaceFirst("{", "{\"futureField\":42,")

        assertEquals(request, PlaybackRequestCodec.decode(json))
    }

    @Test
    fun `empty or malformed queues are rejected`() {
        assertNull(PlaybackRequestCodec.decode(null))
        assertNull(PlaybackRequestCodec.decode("   "))
        assertNull(PlaybackRequestCodec.decode("{not json"))
        assertNull(PlaybackRequestCodec.decode("""{"items":[]}"""))
    }

    @Test
    fun `media summaries map to queue items and back`() {
        val summary = request.items.first().toSummary()
        assertEquals("abc12345678", summary.id)
        assertEquals("PL1", summary.playlistId)
        assertTrue(summary.durationSeconds == 3_723L)
    }

    @Test
    fun `extras survive the metadata bridge`() {
        val extras = PlayerExtras(
            chapters = listOf(io.github.iseaprojects.rivo.data.extract.Chapter(0, 30_000, "Intro")),
            heights = listOf(1080, 720),
            audioTracks = listOf(AudioTrackPick("en", "English", "en")),
            audioTrack = "en",
            qualityLabel = "1080p",
            webUrl = "https://www.youtube.com/watch?v=abc12345678",
        )

        val decoded = PlayerExtrasCodec.decode(PlayerExtrasCodec.encode(extras))

        assertEquals(extras.chapters, decoded.chapters)
        assertEquals(listOf(1080, 720), decoded.heights)
        assertEquals("1080p", decoded.qualityLabel)
        assertEquals(extras.webUrl, decoded.webUrl)
    }
}
