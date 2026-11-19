package io.github.iseaprojects.rivo.data.extract

/** Result of asking the yt-dlp backend to self-update. */
enum class UpdateOutcome {
    UPDATED,
    ALREADY_CURRENT,
    UNSUPPORTED,
    FAILED,
}

/**
 * Release feeds yt-dlp can update from.
 *
 * `youtubedl-android` models these as subclasses of `YoutubeDL.UpdateChannel`, one class per
 * feed, and the classes are Kotlin objects whose names collide with their own companion
 * properties. Building the channel from its API URL keeps the call site readable and does
 * not depend on that generated naming.
 */
object YtdlpChannels {

    const val STABLE = "stable"
    const val NIGHTLY = "nightly"
    const val MASTER = "master"

    private const val BASE = "https://api.github.com/repos"

    val all: List<String> = listOf(STABLE, NIGHTLY, MASTER)

    /** GitHub `releases/latest` endpoint for a channel name; unknown names fall back to stable. */
    fun apiUrl(channel: String): String = when (channel.lowercase()) {
        NIGHTLY -> "$BASE/yt-dlp/yt-dlp-nightly-builds/releases/latest"
        MASTER -> "$BASE/yt-dlp/yt-dlp-master-builds/releases/latest"
        else -> "$BASE/yt-dlp/yt-dlp/releases/latest"
    }
}
