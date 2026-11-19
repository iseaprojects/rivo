package io.github.iseaprojects.rivo.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Version comparison is the whole update verdict. A release feed is easy; the part that
 * used to break on a device was `0.8.10` sorting below `0.8.9` and a release candidate
 * being offered as newer than the release it precedes.
 */
class VersionNumbersTest {

    @Test
    fun `leading v is ignored`() {
        assertEquals(0, VersionNumbers.compare("v0.8.7", "0.8.7"))
        assertEquals(0, VersionNumbers.compare("V1.2.3", "v1.2.3"))
    }

    @Test
    fun `numeric segments compare as numbers`() {
        assertTrue(VersionNumbers.isNewer("0.8.10", "0.8.9"))
        assertTrue(VersionNumbers.isNewer("0.9", "0.8.99"))
        assertFalse(VersionNumbers.isNewer("0.8.7", "0.8.7"))
    }

    @Test
    fun `missing segments are zero`() {
        assertEquals(0, VersionNumbers.compare("0.9", "0.9.0"))
        assertEquals(0, VersionNumbers.compare("1", "1.0.0.0"))
    }

    @Test
    fun `a prerelease sorts below its release`() {
        assertTrue(VersionNumbers.isNewer("1.0.0", "1.0.0-rc1"))
        assertFalse(VersionNumbers.isNewer("1.0.0-rc1", "1.0.0"))
        assertEquals(0, VersionNumbers.compare("1.0.0-rc1", "v1.0.0-rc1"))
    }

    @Test
    fun `dotted prerelease identifiers compare numerically`() {
        assertTrue(VersionNumbers.isNewer("0.8.7-rivo.10", "0.8.7-rivo.9"))
        assertTrue(VersionNumbers.isNewer("0.8.8-rivo.1", "0.8.7-rivo.2"))
        assertFalse(VersionNumbers.isNewer("0.8.7-rivo.2", "0.8.7-rivo.10"))
    }

    @Test
    fun `release selection ignores drafts and honours the channel`() {
        val releases = listOf(
            release("v0.8.8", prerelease = false),
            release("v0.9.0-rc1", prerelease = true),
            release("v9.9.9", prerelease = false, draft = true),
        )

        assertEquals("v0.8.8", ReleaseSelection.latest(releases, includePrerelease = false)?.tag)
        assertEquals("v0.9.0-rc1", ReleaseSelection.latest(releases, includePrerelease = true)?.tag)
        assertNull(
            ReleaseSelection.newerThan(releases, currentVersion = "0.8.8", includePrerelease = false),
        )
    }

    @Test
    fun `feed parser accepts a minimal release array`() {
        val result = ReleaseFeed.parse(
            """
                [
                  {
                    "tag_name": "v0.8.8",
                    "name": "Rivo 0.8.8",
                    "body": "Fixes",
                    "html_url": "https://example.com/release",
                    "prerelease": false,
                    "draft": false,
                    "published_at": "2026-01-02T03:04:05Z",
                    "assets": [{"name": "yt-x"}]
                  }
                ]
            """.trimIndent(),
        )

        val release = (result as ReleaseFeedResult.Ok).releases.single()
        assertEquals("v0.8.8", release.tag)
        assertEquals("Rivo 0.8.8", release.name)
        assertEquals("2026-01-02T03:04:05Z", release.publishedAt)
    }

    @Test
    fun `feed parser reports malformed payloads and accepts an empty release list`() {
        assertTrue(ReleaseFeed.parse("nope") is ReleaseFeedResult.Failed)
        assertTrue(ReleaseFeed.parse("""[{"name":""}]""") is ReleaseFeedResult.Failed)
        val empty = ReleaseFeed.parse("[]")
        assertTrue(empty is ReleaseFeedResult.Ok)
        assertTrue((empty as ReleaseFeedResult.Ok).releases.isEmpty())
    }

    private fun release(tag: String, prerelease: Boolean, draft: Boolean = false) = ReleaseInfo(
        tag = tag,
        name = tag,
        notes = "",
        url = "",
        prerelease = prerelease,
        draft = draft,
        publishedAt = null,
    )
}
