package io.github.iseaprojects.rivo.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieTxtTest {

    private fun parseOk(text: String): List<StoredCookie> {
        val result = CookieTxt.parse(text)
        assertTrue("expected Ok but got $result", result is CookieTxt.ImportResult.Ok)
        return (result as CookieTxt.ImportResult.Ok).cookies
    }

    @Test
    fun `netscape file including HttpOnly and comments`() {
        val cookies = parseOk(
            """
            # Netscape HTTP Cookie File
            .youtube.com	TRUE	/	TRUE	0	SID	secret
            #HttpOnly_.youtube.com	TRUE	/	TRUE	1893456000	HSID	other
            # a comment that must be ignored

            """.trimIndent(),
        )
        assertEquals(2, cookies.size)
        assertEquals("SID", cookies[0].name)
        assertFalse(cookies[0].hostOnly)
        assertTrue(cookies[0].secure)
        assertFalse(cookies[0].httpOnly)
        assertTrue(cookies[1].httpOnly)
        assertEquals(1893456000L, cookies[1].expiresAtEpochSeconds)
    }

    @Test
    fun `header only contains cookies scoped to the host`() {
        val cookies = parseOk(
            """
            .youtube.com	TRUE	/	TRUE	0	SID	yt
            .google.com	TRUE	/	TRUE	0	NID	g
            """.trimIndent(),
        )
        assertEquals("SID=yt", CookieTxt.headerFor(cookies, "https://www.youtube.com/watch?v=x"))
        // ytimg is a sibling CDN, not a subdomain of youtube.com: the session must not leak.
        assertNull(CookieTxt.headerFor(cookies, "https://i.ytimg.com/vi/x/hq.jpg"))
    }

    @Test
    fun `host only cookies do not match subdomains`() {
        val cookies = parseOk("youtube.com	FALSE	/	TRUE	0	SID	yt")
        assertEquals("SID=yt", CookieTxt.headerFor(cookies, "https://youtube.com/"))
        assertNull(CookieTxt.headerFor(cookies, "https://www.youtube.com/"))
    }

    @Test
    fun `secure cookies are not sent over http`() {
        val cookies = parseOk(".youtube.com	TRUE	/	TRUE	0	SID	yt")
        assertNull(CookieTxt.headerFor(cookies, "http://www.youtube.com/"))
    }

    @Test
    fun `path scoping follows rfc6265 boundaries`() {
        val cookies = parseOk(".youtube.com	TRUE	/feed	FALSE	0	PREF	a")
        assertNull(CookieTxt.headerFor(cookies, "https://www.youtube.com/watch"))
        assertEquals("PREF=a", CookieTxt.headerFor(cookies, "https://www.youtube.com/feed/trending"))
        assertNull(CookieTxt.headerFor(cookies, "https://www.youtube.com/feedback"))
    }

    @Test
    fun `expired cookies are dropped and longer paths come first`() {
        val text = """
            .youtube.com	TRUE	/	FALSE	1	OLD	gone
            .youtube.com	TRUE	/	FALSE	0	ROOT	r
            .youtube.com	TRUE	/api	FALSE	0	API	a
        """.trimIndent()
        val cookies = parseOk(text)
        assertEquals("API=a; ROOT=r", CookieTxt.headerFor(cookies, "https://www.youtube.com/api/x"))
    }

    @Test
    fun `tld wide cookie is rejected`() {
        val result = CookieTxt.parse(".com	TRUE	/	FALSE	0	BAD	1")
        assertTrue(result is CookieTxt.ImportResult.Failure)
    }

    @Test
    fun `json export is understood`() {
        val json = """
            [
              {"domain": ".youtube.com", "name": "SID", "value": "x", "path": "/",
               "secure": true, "httpOnly": true, "expirationDate": 1893456000},
              {"domain": ".youtube.com", "name": "PREF", "value": "y", "path": "/"}
            ]
        """.trimIndent()
        val result = CookieTxt.parseJson(json)
        assertTrue(result is CookieTxt.ImportResult.Ok)
        val cookies = (result as CookieTxt.ImportResult.Ok).cookies
        assertEquals(2, cookies.size)
        assertEquals(1893456000L, cookies[0].expiresAtEpochSeconds)
        assertTrue(cookies[1].secure.not())
    }

    @Test
    fun `format round trips through the parser`() {
        val original = parseOk(
            """
            #HttpOnly_.youtube.com	TRUE	/	TRUE	0	SID	value with spaces
            """.trimIndent(),
        )
        val text = CookieTxt.format(original)
        val again = parseOk(text)
        assertEquals(original.first().name, again.first().name)
        assertEquals("value with spaces", again.first().value)
        assertTrue(again.first().httpOnly)
    }

    @Test
    fun `webview header is converted into scoped cookies`() {
        val cookies = CookieTxt.fromCookieHeader("SID=a; HSID=b; PREF=c", "youtube.com")
        assertEquals(3, cookies.size)
        assertEquals("SID=a; HSID=b; PREF=c", CookieTxt.headerFor(cookies, "https://www.youtube.com/"))
    }

    @Test
    fun `ytdlp header only includes the requested site`() {
        val cookies = parseOk(
            """
            .youtube.com	TRUE	/	TRUE	0	SID	yt
            .example.com	TRUE	/	TRUE	0	X	ex
            """.trimIndent(),
        )
        assertEquals("SID=yt", CookieTxt.encodeForYtDlp(cookies, "youtube.com"))
    }
}
