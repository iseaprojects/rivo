package io.github.iseaprojects.rivo.ui.media

import io.github.iseaprojects.rivo.core.i18n.Strings
import io.github.iseaprojects.rivo.data.extract.ExtractorException
import io.github.iseaprojects.rivo.data.extract.FailureKind

/**
 * One localized sentence per failure category, with the extractor's own message shown
 * underneath by the screen. The category is what the user can act on; the raw message
 * is what a bug report needs, so both are kept instead of picking one.
 */
fun ExtractorException.summary(strings: Strings): String = strings.text(
    when (kind) {
        FailureKind.NETWORK -> "TXT_ANDROID_ERROR_NETWORK"
        FailureKind.TIMEOUT -> "TXT_ANDROID_ERROR_TIMEOUT"
        FailureKind.RATE_LIMITED -> "TXT_ANDROID_ERROR_RATE_LIMITED"
        FailureKind.NOT_FOUND, FailureKind.REMOVED -> "TXT_ANDROID_ERROR_REMOVED"
        FailureKind.PRIVATE, FailureKind.FORBIDDEN -> "TXT_ANDROID_ERROR_PRIVATE"
        FailureKind.AGE_RESTRICTED -> "TXT_ANDROID_ERROR_AGE"
        FailureKind.GEO_BLOCKED -> "TXT_ANDROID_ERROR_GEO"
        FailureKind.DRM_PROTECTED -> "TXT_ANDROID_ERROR_DRM"
        FailureKind.MEMBERS_ONLY -> "TXT_ANDROID_ERROR_MEMBERS"
        FailureKind.UPCOMING -> "TXT_ANDROID_ERROR_UPCOMING"
        FailureKind.LIVE_NOT_READY -> "TXT_ANDROID_ERROR_LIVE"
        FailureKind.UNSUPPORTED -> "TXT_ANDROID_ERROR_UNSUPPORTED"
        FailureKind.EXTRACTOR_BROKEN -> "TXT_ANDROID_ERROR_BROKEN"
        FailureKind.UNKNOWN -> "TXT_ANDROID_ERROR_UNKNOWN"
    },
)
