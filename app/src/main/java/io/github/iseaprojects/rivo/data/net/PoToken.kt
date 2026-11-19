package io.github.iseaprojects.rivo.data.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * PO token material for YouTube's "proof of origin" requirement.
 *
 * NewPipe 0.27 accepts a [org.schabi.newpipe.extractor.services.youtube.PoTokenResult]
 * with three parts; yt-dlp accepts the same token through `--extractor-args`. Users get
 * the values from a browser extension or `bgutil-ytdlp-pot-provider`, so the config field
 * accepts both a JSON object and the compact `visitorData:token[:streaming]` form.
 */
data class PoTokenSpec(
    val visitorData: String,
    val playerToken: String,
    val streamingToken: String? = null,
) {
    val isUsable: Boolean get() = visitorData.isNotBlank() && playerToken.isNotBlank()
}

object PoTokenConfig {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String?): PoTokenSpec? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        if (text.startsWith("{")) {
            val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                ?: return null
            val visitor = obj.string("visitorData")
                ?: obj.string("visitor_data")
                ?: obj.string("visitor")
            val player = obj.string("poToken")
                ?: obj.string("po_token")
                ?: obj.string("playerPoToken")
                ?: obj.string("token")
            val streaming = obj.string("streamingPoToken")
                ?: obj.string("streaming_po_token")
                ?: obj.string("streamingToken")
            return spec(visitor, player, streaming)
        }

        val parts = text.split(':', limit = 3)
        return when (parts.size) {
            2 -> spec(parts[0], parts[1], null)
            3 -> spec(parts[0], parts[1], parts[2])
            else -> spec(null, text, null)
        }
    }

    private fun spec(visitor: String?, player: String?, streaming: String?): PoTokenSpec? {
        val v = visitor?.trim().orEmpty()
        val p = player?.trim().orEmpty()
        if (v.isEmpty() || p.isEmpty()) return null
        return PoTokenSpec(
            visitorData = v,
            playerToken = p,
            streamingToken = streaming?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * yt-dlp `--extractor-args` value, or null when no token is configured.
     *
     * yt-dlp 2025.11 expects `CLIENT.CONTEXT+TOKEN` entries and takes several of them
     * comma-separated (same syntax as `player_client=web,tv`). Visitor data is *not* a
     * configuration argument: the extractor reads it from its own HTTP responses.
     */
    fun extractorArgs(spec: PoTokenSpec?): String? {
        val token = spec?.takeIf { it.isUsable } ?: return null
        val parts = mutableListOf("web.player+${token.playerToken}")
        token.streamingToken?.takeIf { it.isNotBlank() }?.let { parts += "web.gvs+$it" }
        return "youtube:po_token=${parts.joinToString(",")}"
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            ?.takeIf { it.isNotEmpty() }
}
