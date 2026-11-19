package io.github.iseaprojects.rivo.data.extract

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractorFailureTest {

    private class PrivateContentException : Exception("private video")
    private class AgeRestrictedContentException : Exception("age restricted")
    private class ReCaptchaException : Exception("captcha")
    private class ExtractionException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    @Test
    fun `http statuses map to stable kinds`() {
        assertEquals(FailureKind.FORBIDDEN, ExtractorFailures.fromStatus(403).kind)
        assertEquals(FailureKind.NOT_FOUND, ExtractorFailures.fromStatus(404).kind)
        assertEquals(FailureKind.RATE_LIMITED, ExtractorFailures.fromStatus(429).kind)
        assertEquals(FailureKind.TIMEOUT, ExtractorFailures.fromStatus(408).kind)
        assertEquals(FailureKind.EXTRACTOR_BROKEN, ExtractorFailures.fromStatus(503).kind)
    }

    @Test
    fun `403 is treated as an expired url so the resolver retries`() {
        val error = ExtractorFailures.fromStatus(403)
        assertTrue(error.looksLikeExpiredUrl)
        assertTrue(error.retryable)
        assertEquals(io.github.iseaprojects.rivo.core.model.Availability.PRIVATE, error.asAvailability())
    }

    @Test
    fun `newpipe exception names are recognised`() {
        assertEquals(
            FailureKind.PRIVATE,
            ExtractorFailures.fromThrowable(PrivateContentException()).kind,
        )
        assertEquals(
            FailureKind.AGE_RESTRICTED,
            ExtractorFailures.fromThrowable(AgeRestrictedContentException()).kind,
        )
        assertEquals(
            FailureKind.RATE_LIMITED,
            ExtractorFailures.fromThrowable(ReCaptchaException()).kind,
        )
    }

    @Test
    fun `wrapped causes are inspected`() {
        val wrapped = ExtractionException(
            "could not fetch",
            PrivateContentException(),
        )
        val mapped = ExtractorFailures.fromThrowable(wrapped)
        assertEquals(FailureKind.PRIVATE, mapped.kind)
    }

    @Test
    fun `network and timeout failures are retryable`() {
        val network = ExtractorFailures.fromThrowable(UnknownHostException("no dns"))
        assertEquals(FailureKind.NETWORK, network.kind)
        assertTrue(network.retryable)

        val timeout = ExtractorFailures.fromThrowable(SocketTimeoutException("slow"))
        assertEquals(FailureKind.TIMEOUT, timeout.kind)
        assertTrue(timeout.retryable)

        assertEquals(
            FailureKind.TIMEOUT,
            ExtractorFailures.fromThrowable(TimeoutException("later")).kind,
        )
    }

    @Test
    fun `yt-dlp stderr phrases are classified`() {
        assertEquals(
            FailureKind.PRIVATE,
            ExtractorFailures.fromYtDlpMessage("ERROR: [youtube] x: Private video").kind,
        )
        assertEquals(
            FailureKind.RATE_LIMITED,
            ExtractorFailures.fromYtDlpMessage(
                "ERROR: [youtube] x: Sign in to confirm you're not a bot",
            ).kind,
        )
        assertEquals(
            FailureKind.REMOVED,
            ExtractorFailures.fromYtDlpMessage("ERROR: Video unavailable").kind,
        )
        assertEquals(
            FailureKind.AGE_RESTRICTED,
            ExtractorFailures.fromYtDlpMessage("ERROR: Sign in to confirm your age").kind,
        )
    }

    @Test
    fun `yt-dlp message keeps the last ERROR line and the exit code matters`() {
        val mapped = ExtractorFailures.fromYtDlpMessage(
            """
            [youtube] Extracting URL
            WARNING: something
            ERROR: Unable to extract nsig
            """.trimIndent(),
            1,
        )
        assertEquals(FailureKind.EXTRACTOR_BROKEN, mapped.kind)
        assertEquals("Unable to extract nsig", mapped.message)
        assertTrue(mapped.retryable)

        val silent = ExtractorFailures.fromYtDlpMessage("", exitCode = 2)
        assertEquals(FailureKind.EXTRACTOR_BROKEN, silent.kind)
        assertFalse(ExtractorFailures.fromYtDlpMessage("", exitCode = 0).kind ==
            FailureKind.EXTRACTOR_BROKEN)
    }
}
