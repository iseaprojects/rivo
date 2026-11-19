package io.github.iseaprojects.rivo.player

import kotlinx.serialization.json.Json

/**
 * Chapters, SponsorBlock segments and the stream choices travel to the activity inside
 * `MediaMetadata.extras`. The activity is a `MediaController` client and cannot see the
 * service's extraction results, so this small payload is the only bridge that keeps the
 * seek bar, the chapter sheet and the quality sheet in sync with what is playing.
 */
object PlayerExtrasCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        coerceInputValues = true
    }

    fun encode(extras: PlayerExtras): String {
        val compact = extras.copy(
            // A heatmap has a point every few seconds; 400 buckets already draw a full
            // seek bar and keep the Bundle under the Binder transaction limit.
            heatmap = extras.heatmap.take(400),
            chapters = extras.chapters.take(200),
            sponsors = extras.sponsors.take(100),
        )
        return runCatching { json.encodeToString(PlayerExtras.serializer(), compact) }
            .getOrDefault("")
    }

    fun decode(text: String?): PlayerExtras {
        if (text.isNullOrBlank()) return PlayerExtras()
        return runCatching { json.decodeFromString(PlayerExtras.serializer(), text) }
            .getOrDefault(PlayerExtras())
    }
}
