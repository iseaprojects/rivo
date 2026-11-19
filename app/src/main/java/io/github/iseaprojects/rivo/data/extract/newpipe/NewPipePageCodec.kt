package io.github.iseaprojects.rivo.data.extract.newpipe

import io.github.iseaprojects.rivo.data.extract.ContinueToken
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.schabi.newpipe.extractor.Page

/**
 * Serialises NewPipe's continuation [Page] into an opaque string.
 *
 * The extractor's page object holds one of: a continuation URL, a continuation id, a
 * list of ids (playlists) and sometimes a POST body plus cookies. Keeping it in memory
 * would lose the position on process death; keeping it as `toString()` would lose the
 * fields. This codec round-trips all five, which is what makes explicit paging survive
 * a rotation or a background kill.
 */
object NewPipePageCodec {

    private const val BACKEND = "newpipe"
    private val json = Json { ignoreUnknownKeys = true }
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()

    fun encode(page: Page?): ContinueToken? {
        if (page == null) return null
        if (!runCatching { Page.isValid(page) }.getOrDefault(false)) return null

        val payload = buildJsonObject {
            page.url?.let { put("u", JsonPrimitive(it)) }
            page.id?.let { put("i", JsonPrimitive(it)) }
            page.ids?.takeIf { it.isNotEmpty() }?.let { ids ->
                put("ids", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
            }
            page.cookies?.takeIf { it.isNotEmpty() }?.let { cookies ->
                put(
                    "c",
                    buildJsonObject {
                        cookies.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
                    },
                )
            }
            page.body?.takeIf { it.isNotEmpty() }?.let { body ->
                put("b", JsonPrimitive(encoder.encodeToString(body)))
            }
        }.toString()

        return ContinueToken(BACKEND, payload)
    }

    fun decode(token: ContinueToken?): Page? {
        if (token == null || token.backend != BACKEND) return null
        val root = runCatching { json.parseToJsonElement(token.payload) as? JsonObject }
            .getOrNull() ?: return null

        val url = root.string("u")
        val id = root.string("i")
        val ids = (root["ids"] as? JsonArray)
            ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
        val cookies = (root["c"] as? JsonObject)?.entries
            ?.associate { (key, value) -> key to runCatching { value.jsonPrimitive.content }.getOrDefault("") }
            ?.takeIf { it.isNotEmpty() }
        val body = root.string("b")?.let { runCatching { decoder.decode(it) }.getOrNull() }

        if (url == null && id == null && ids == null) return null

        val page = runCatching { Page(url, id, ids, cookies, body) }.getOrNull() ?: return null
        return page.takeIf { runCatching { Page.isValid(it) }.getOrDefault(false) }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotEmpty() }
}
