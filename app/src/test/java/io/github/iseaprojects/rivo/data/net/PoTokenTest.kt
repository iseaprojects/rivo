package io.github.iseaprojects.rivo.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PoTokenTest {

    @Test
    fun `json object form`() {
        val spec = PoTokenConfig.parse(
            """{"visitorData":"VIS","poToken":"PLAY","streamingPoToken":"STREAM"}""",
        )
        assertNotNull(spec)
        assertEquals("VIS", spec!!.visitorData)
        assertEquals("PLAY", spec.playerToken)
        assertEquals("STREAM", spec.streamingToken)
    }

    @Test
    fun `compact colon form with and without streaming token`() {
        val two = PoTokenConfig.parse("VIS:PLAY")
        assertEquals("VIS", two?.visitorData)
        assertEquals("PLAY", two?.playerToken)
        assertNull(two?.streamingToken)

        val three = PoTokenConfig.parse("VIS:PLAY:STREAM")
        assertEquals("STREAM", three?.streamingToken)
    }

    @Test
    fun `bare token is rejected because visitor data is required`() {
        assertNull(PoTokenConfig.parse("just-a-token"))
        assertNull(PoTokenConfig.parse(""))
        assertNull(PoTokenConfig.parse(null))
    }

    @Test
    fun `extractor args use the client dot context syntax of yt-dlp 2025`() {
        val spec = PoTokenSpec("VIS", "PLAY", "STREAM")
        assertEquals(
            "youtube:po_token=web.player+PLAY,web.gvs+STREAM",
            PoTokenConfig.extractorArgs(spec),
        )
        assertEquals(
            "youtube:po_token=web.player+PLAY",
            PoTokenConfig.extractorArgs(PoTokenSpec("VIS", "PLAY")),
        )
        assertNull(PoTokenConfig.extractorArgs(null))
    }

    @Test
    fun `incomplete json is rejected`() {
        assertNull(PoTokenConfig.parse("""{"poToken":"PLAY"}"""))
        assertNull(PoTokenConfig.parse("""{"visitorData":"VIS"}"""))
        assertNull(PoTokenConfig.parse("{not json"))
    }
}
