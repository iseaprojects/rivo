package io.github.iseaprojects.rivo.data.extract

import io.github.iseaprojects.rivo.core.model.Availability

/**
 * Every way an extraction can fail, normalised across backends.
 *
 * NewPipe throws ~18 exception types, yt-dlp throws a single `YoutubeDLException` whose
 * only useful information is the stderr text, and both can fail at the HTTP layer. The
 * rest of the app must not care which one it was: the UI needs a stable category for the
 * error banner, the retry logic needs to know whether retrying can help, and the resolver
 * needs to distinguish "the URL expired" from "this video is private".
 */
enum class FailureKind {
    NETWORK,
    TIMEOUT,
    RATE_LIMITED,
    NOT_FOUND,
    PRIVATE,
    AGE_RESTRICTED,
    GEO_BLOCKED,
    DRM_PROTECTED,
    MEMBERS_ONLY,
    UPCOMING,
    LIVE_NOT_READY,
    REMOVED,
    FORBIDDEN,
    UNSUPPORTED,
    EXTRACTOR_BROKEN,
    UNKNOWN,
    ;

    /** True when re-running the same request later has a realistic chance to succeed. */
    val retryable: Boolean
        get() = when (this) {
            NETWORK, TIMEOUT, RATE_LIMITED, FORBIDDEN, LIVE_NOT_READY, EXTRACTOR_BROKEN -> true
            else -> false
        }

    fun toAvailability(): Availability = when (this) {
        NETWORK, TIMEOUT -> Availability.UNKNOWN
        RATE_LIMITED -> Availability.RATE_LIMITED
        PRIVATE, FORBIDDEN -> Availability.PRIVATE
        AGE_RESTRICTED -> Availability.AGE_RESTRICTED
        GEO_BLOCKED -> Availability.GEO_BLOCKED
        DRM_PROTECTED -> Availability.DRM_PROTECTED
        MEMBERS_ONLY -> Availability.MEMBERS_ONLY
        UPCOMING -> Availability.UPCOMING
        LIVE_NOT_READY -> Availability.LIVE_NOT_READY
        NOT_FOUND, REMOVED -> Availability.REMOVED
        EXTRACTOR_BROKEN -> Availability.EXTRACTOR_BROKEN
        UNSUPPORTED, UNKNOWN -> Availability.UNKNOWN
    }
}

class ExtractorException(
    val kind: FailureKind,
    message: String,
    cause: Throwable? = null,
    /** Raw HTTP status when the failure came from a response. */
    val statusCode: Int? = null,
) : Exception(message, cause) {

    val retryable: Boolean get() = kind.retryable

    fun asAvailability(): Availability = kind.toAvailability()

    /** True when a stale stream URL is the likely cause and re-resolving may fix it. */
    val looksLikeExpiredUrl: Boolean
        get() = kind == FailureKind.FORBIDDEN || statusCode == 403 || statusCode == 410
}

/**
 * Translation layer from backend-specific failures to [ExtractorException].
 *
 * The mapping by class name instead of `is SomeNewPipeException` keeps this file free of
 * extractor imports, which is what lets the JVM tests exercise every branch with fake
 * exceptions instead of having to construct the real ones.
 */
object ExtractorFailures {

    fun fromStatus(code: Int, message: String? = null): ExtractorException {
        val kind = when (code) {
            400 -> FailureKind.UNSUPPORTED
            401 -> FailureKind.PRIVATE
            403 -> FailureKind.FORBIDDEN
            404, 410 -> FailureKind.NOT_FOUND
            408 -> FailureKind.TIMEOUT
            429 -> FailureKind.RATE_LIMITED
            in 500..599 -> FailureKind.EXTRACTOR_BROKEN
            else -> FailureKind.UNKNOWN
        }
        return ExtractorException(
            kind = kind,
            message = message ?: "HTTP $code",
            statusCode = code,
        )
    }

    fun httpStatusOf(error: Throwable): Int? {
        // NewPipe's HttpResponseException and OkHttp's Response both expose the code,
        // but through different accessors and without a shared interface.
        val byMethod = runCatching {
            error.javaClass.getMethod("getResponseCode").invoke(error) as? Int
        }.getOrNull()
        if (byMethod != null) return byMethod
        return runCatching {
            (error as? okhttp3.Response)?.code
        }.getOrNull()
    }

    fun fromThrowable(error: Throwable): ExtractorException {
        if (error is ExtractorException) return error

        val status = httpStatusOf(error)
        if (status != null && status >= 400) return fromStatus(status, error.message)

        // Walk the cause chain: NewPipe wraps parsing failures inside extraction ones.
        var current: Throwable? = error
        val names = mutableListOf<String>()
        while (current != null && names.size < 8) {
            names += current.javaClass.simpleName
            current = current.cause
        }

        val kind = when {
            names.any { it.contains("AccountTerminated") } -> FailureKind.MEMBERS_ONLY
            names.any { it.contains("AgeRestricted") } -> FailureKind.AGE_RESTRICTED
            names.any { it.contains("GeographicRestriction") } -> FailureKind.GEO_BLOCKED
            names.any { it.contains("PaidContent") || it.contains("Premium") ||
                it.contains("GoPlus") } -> FailureKind.MEMBERS_ONLY
            names.any { it.contains("PrivateContent") } -> FailureKind.PRIVATE
            names.any { it.contains("ContentNotAvailable") } -> FailureKind.REMOVED
            names.any { it.contains("ContentNotSupported") } -> FailureKind.UNSUPPORTED
            names.any { it.contains("NothingFound") } -> FailureKind.NOT_FOUND
            names.any { it.contains("ReCaptcha") } -> FailureKind.RATE_LIMITED
            names.any { it.contains("FoundAd") } -> FailureKind.UNSUPPORTED
            names.any { it.contains("SignInConfirmNotBot") } -> FailureKind.FORBIDDEN
            names.any { it.contains("Parser") || it.contains("Parsing") } ->
                FailureKind.EXTRACTOR_BROKEN
            names.any { it.contains("Timeout") || it == "InterruptedIOException" } ->
                FailureKind.TIMEOUT
            names.any { it.contains("UnknownHost") || it.contains("ConnectException") ||
                it.contains("SocketException") || it.contains("SSL") ||
                it.contains("ConnectionShutdown") } -> FailureKind.NETWORK
            names.any { it == "IOException" } -> FailureKind.NETWORK
            names.any { it.contains("Extraction") } -> FailureKind.EXTRACTOR_BROKEN
            else -> FailureKind.UNKNOWN
        }

        val message = error.message?.takeIf { it.isNotBlank() }
            ?: error.javaClass.simpleName
        return ExtractorException(kind, message, error, status)
    }

    /**
     * yt-dlp reports everything through stderr text. These are the phrases that actually
     * appear in the wild, taken from yt-dlp's own error strings.
     */
    fun fromYtDlpMessage(stderr: String, exitCode: Int? = null): ExtractorException {
        val text = stderr.lowercase()
        val kind = when {
            "private video" in text || "sign in to confirm your age" in text &&
                "private" in text -> FailureKind.PRIVATE
            "age-restricted" in text || "inappropriate for some users" in text ||
                "confirm your age" in text -> FailureKind.AGE_RESTRICTED
            "not available in your country" in text || "geo" in text &&
                "restricted" in text -> FailureKind.GEO_BLOCKED
            "members-only" in text || "join this channel" in text -> FailureKind.MEMBERS_ONLY
            "drm" in text || "protected by drm" in text -> FailureKind.DRM_PROTECTED
            "premieres in" in text || "upcoming" in text -> FailureKind.UPCOMING
            "this live event will begin" in text || "live event will begin" in text ->
                FailureKind.LIVE_NOT_READY
            "video unavailable" in text || "has been removed" in text ||
                "does not exist" in text -> FailureKind.REMOVED
            "http error 403" in text || "403: forbidden" in text -> FailureKind.FORBIDDEN
            "http error 404" in text -> FailureKind.NOT_FOUND
            "http error 429" in text || "too many requests" in text ||
                "sign in to confirm you" in text && "not a bot" in text -> FailureKind.RATE_LIMITED
            "timed out" in text || "timeout" in text -> FailureKind.TIMEOUT
            "urlopen error" in text || "connection" in text && "refused" in text ||
                "temporary failure in name resolution" in text ||
                "network is unreachable" in text -> FailureKind.NETWORK
            "unsupported url" in text || "no video formats found" in text ->
                FailureKind.UNSUPPORTED
            "unable to extract" in text || "nsig extraction failed" in text ||
                "unable to download webpage" in text -> FailureKind.EXTRACTOR_BROKEN
            else -> if ((exitCode ?: 0) != 0) FailureKind.EXTRACTOR_BROKEN else FailureKind.UNKNOWN
        }
        val message = stderr.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.startsWith("ERROR:", ignoreCase = true) }
            ?.removePrefix("ERROR:")
            ?.trim()
            ?: stderr.trim().takeLast(300)
        return ExtractorException(kind, message.ifBlank { "yt-dlp failed" })
    }
}
