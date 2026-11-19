package io.github.iseaprojects.rivo.data.extract.ytdlp

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandLineTest {

    @Test
    fun `splits flags and values`() {
        assertEquals(
            listOf("--limit-rate", "2M", "--no-mtime"),
            CommandLine.split("--limit-rate 2M --no-mtime"),
        )
    }

    @Test
    fun `keeps quoted values together`() {
        assertEquals(
            listOf("--extractor-args", "youtube:player_client=web,android", "--verbose"),
            CommandLine.split("--extractor-args \"youtube:player_client=web,android\" --verbose"),
        )
    }

    @Test
    fun `single quotes do not process backslashes`() {
        assertEquals(
            listOf("--output", "C:\\videos\\%(title)s.%(ext)s"),
            CommandLine.split("--output 'C:\\videos\\%(title)s.%(ext)s'"),
        )
    }

    @Test
    fun `escaped spaces stay in one token`() {
        assertEquals(listOf("--output", "a b.mp4"), CommandLine.split("--output a\\ b.mp4"))
    }

    @Test
    fun `blank input produces no tokens`() {
        assertEquals(emptyList<String>(), CommandLine.split("   "))
        assertEquals(emptyList<String>(), CommandLine.split(""))
    }

    @Test
    fun `unterminated quote still yields the token`() {
        assertEquals(listOf("--output", "half done"), CommandLine.split("--output \"half done"))
    }
}
