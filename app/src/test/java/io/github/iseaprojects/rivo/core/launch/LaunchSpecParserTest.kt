package io.github.iseaprojects.rivo.core.launch

import io.github.iseaprojects.rivo.core.config.ConfigKeys
import io.github.iseaprojects.rivo.core.menu.MenuIds
import io.github.iseaprojects.rivo.core.menu.MenuPayload
import io.github.iseaprojects.rivo.core.model.FeedKind
import io.github.iseaprojects.rivo.core.model.LibraryKind
import io.github.iseaprojects.rivo.core.model.SearchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchSpecParserTest {

    @Test
    fun `launcher intent is empty`() {
        val spec = LaunchSpecParser.parse(
            dataUri = null,
            intentAction = "android.intent.action.MAIN",
            mimeType = null,
            extras = emptyMap(),
        )
        assertTrue(spec.isEmpty)
    }

    @Test
    fun `cli equivalents become a launch spec`() {
        val spec = LaunchSpecParser.parse(
            dataUri = null,
            intentAction = "android.intent.action.MAIN",
            mimeType = null,
            extras = mapOf(
                "RIVO_SEARCH" to "eono",
                "RIVO_PLAY" to "true",
                "RIVO_LISTEN" to false,
                "RIVO_PLAYLIST_SKIP" to 1,
                "RIVO_PER_PAGE" to 60,
                "RIVO_THEME" to "catppuccin-mocha",
            ),
        )

        assertEquals(LaunchAction.PLAY, spec.action)
        assertTrue(spec.playlistSkip)
        assertEquals("60", spec.overrides[ConfigKeys.PER_PAGE])
        assertEquals("catppuccin-mocha", spec.overrides[ConfigKeys.THEME])
    }

    @Test
    fun `download-audio wins over download when both are set`() {
        val spec = LaunchSpecParser.parse(
            dataUri = null,
            intentAction = null,
            mimeType = null,
            extras = mapOf("RIVO_DOWNLOAD" to "1", "RIVO_DOWNLOAD_AUDIO" to "1"),
        )
        assertEquals(LaunchAction.DOWNLOAD_AUDIO, spec.action)
    }

    @Test
    fun `multi key config payload only accepts known keys`() {
        val spec = LaunchSpecParser.parse(
            dataUri = null,
            intentAction = null,
            mimeType = null,
            extras = mapOf(
                "RIVO_CONFIG" to "CONFIG_PER_PAGE=60;CONFIG_THEME=tokyo-night;EVIL_KEY=1",
            ),
        )

        assertEquals("60", spec.overrides[ConfigKeys.PER_PAGE])
        assertEquals("tokyo-night", spec.overrides[ConfigKeys.THEME])
        assertFalse(spec.overrides.containsKey("EVIL_KEY"))
    }

    @Test
    fun `unknown override names are ignored`() {
        val spec = LaunchSpecParser.parse(
            dataUri = null,
            intentAction = null,
            mimeType = null,
            extras = mapOf("RIVO_SOMETHING_ELSE" to "1"),
        )
        assertTrue(spec.overrides.isEmpty())
    }

    @Test
    fun `deep link parses host, path and decoded query`() {
        val link = DeepLink.parse("rivo://search/eono?q=%3A4k+eono&action=play&exit=true")
        assertNotNull(link)
        assertEquals("search", link!!.host)
        assertEquals("eono", link.path)
        assertEquals(":4k eono", link.param("q"))
        assertEquals("play", link.param("action"))
        assertEquals("true", link.param("exit"))
    }

    @Test
    fun `non rivo schemes are not deep links`() {
        assertNull(DeepLink.parse("https://www.youtube.com/watch?v=abc"))
        assertNull(DeepLink.parse(""))
        assertNull(DeepLink.parse("rivo://"))
    }

    @Test
    fun `shared text is routed to search when it is not a URL`() {
        val spec = LaunchSpecParser.parse(
            dataUri = null,
            intentAction = "android.intent.action.SEND",
            mimeType = "text/plain",
            extras = mapOf("android.intent.extra.TEXT" to "lofi hip hop radio"),
        )
        val route = LaunchRouter().route(spec)
        assertEquals(MenuIds.SEARCH, route?.menuId)
        assertEquals(MenuPayload.Search("lofi hip hop radio"), route?.payload)
    }

    @Test
    fun `shared url becomes a media action for that video`() {
        val spec = LaunchSpecParser.parse(
            dataUri = null,
            intentAction = "android.intent.action.SEND",
            mimeType = "text/plain",
            extras = mapOf(
                "android.intent.extra.TEXT" to "watch this https://youtu.be/dQw4w9WgXcQ now",
            ),
        )
        val route = LaunchRouter().route(spec)
        assertEquals(MenuIds.MEDIA_ACTIONS, route?.menuId)
        assertEquals("https://youtu.be/dQw4w9WgXcQ", (route?.payload as? MenuPayload.Url)?.url)
    }

    @Test
    fun `feed and library deep links keep their kind`() {
        val router = LaunchRouter()

        val feed = router.route(specFor("rivo://feed?kind=trending"))
        assertEquals(MenuIds.FEED, feed?.menuId)
        assertEquals(FeedKind.TRENDING, (feed?.payload as? MenuPayload.Feed)?.kind)

        val saved = router.route(specFor("rivo://saved"))
        assertEquals(MenuIds.LIBRARY, saved?.menuId)
        assertEquals(LibraryKind.SAVED, (saved?.payload as? MenuPayload.Library)?.kind)
    }

    @Test
    fun `search deep link understands the type parameter`() {
        val route = LaunchRouter().route(specFor("rivo://search?q=eono&type=channels"))
        assertEquals(MenuIds.SEARCH, route?.menuId)
        assertEquals(SearchKind.CHANNELS, (route?.payload as? MenuPayload.Search)?.kind)
    }

    @Test
    fun `watch deep link resolves an id to a canonical url`() {
        val route = LaunchRouter().route(specFor("rivo://watch?v=abc123"))
        assertEquals(MenuIds.MEDIA_ACTIONS, route?.menuId)
        assertEquals(
            "https://www.youtube.com/watch?v=abc123",
            (route?.payload as? MenuPayload.Url)?.url,
        )
    }

    @Test
    fun `youtube action view intent is treated as an external url`() {
        val spec = LaunchSpecParser.parse(
            dataUri = "https://www.youtube.com/watch?v=abc123",
            intentAction = "android.intent.action.VIEW",
            mimeType = null,
            extras = emptyMap(),
        )
        assertEquals("https://www.youtube.com/watch?v=abc123", spec.externalUrl)
        assertEquals(MenuIds.MEDIA_ACTIONS, LaunchRouter().route(spec)?.menuId)
    }

    private fun specFor(uri: String): LaunchSpec = LaunchSpecParser.parse(
        dataUri = uri,
        intentAction = "android.intent.action.VIEW",
        mimeType = null,
        extras = emptyMap(),
    )
}
