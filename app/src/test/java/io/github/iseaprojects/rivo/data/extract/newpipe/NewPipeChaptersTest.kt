package io.github.iseaprojects.rivo.data.extract.newpipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.schabi.newpipe.extractor.stream.StreamSegment

/**
 * Description chapters are the only chapter source on the NewPipe backend, so the mapping has
 * to produce sorted, closed intervals from a list of (start, title) pairs.
 */
class NewPipeChaptersTest {

    @Test
    fun `each chapter ends where the next one starts`() {
        val chapters = chaptersOf(
            listOf(
                StreamSegment("Intro", 0),
                StreamSegment("Setup", 45),
                StreamSegment("Demo", 130),
            ),
            durationSeconds = 600,
        )

        assertEquals(listOf("Intro", "Setup", "Demo"), chapters.map { it.title })
        assertEquals(listOf(0L, 45_000L, 130_000L), chapters.map { it.startMs })
        assertEquals(listOf(45_000L, 130_000L, 600_000L), chapters.map { it.endMs })
    }

    @Test
    fun `unsorted segments are ordered by start`() {
        val chapters = chaptersOf(
            listOf(
                StreamSegment("Second", 90),
                StreamSegment("First", 10),
            ),
            durationSeconds = 240,
        )

        assertEquals(listOf("First", "Second"), chapters.map { it.title })
        assertEquals(listOf(90_000L, 240_000L), chapters.map { it.endMs })
    }

    @Test
    fun `untitled and repeated segments are dropped`() {
        val chapters = chaptersOf(
            listOf(
                StreamSegment(" ", 0),
                StreamSegment("Intro", 12),
                StreamSegment("Intro again", 12),
                StreamSegment("Body", 60),
            ),
            durationSeconds = 120,
        )

        assertEquals(listOf("Intro", "Body"), chapters.map { it.title })
        assertEquals(60_000L, chapters[0].endMs)
    }

    @Test
    fun `the last chapter has no end when the duration is unknown`() {
        val chapters = chaptersOf(listOf(StreamSegment("Only", 5)), durationSeconds = 0)

        assertEquals(5_000L, chapters.single().startMs)
        assertNull(chapters.single().endMs)
    }

    @Test
    fun `a segment past the reported duration keeps the duration as its end`() {
        val chapters = chaptersOf(
            listOf(StreamSegment("Intro", 0), StreamSegment("Outro", 500)),
            durationSeconds = 300,
        )

        assertEquals(500_000L, chapters[1].startMs)
        assertNull(chapters[1].endMs)
    }
}
