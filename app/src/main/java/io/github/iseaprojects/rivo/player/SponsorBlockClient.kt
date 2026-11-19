package io.github.iseaprojects.rivo.player

import io.github.iseaprojects.rivo.data.extract.SponsorSegment
import io.github.iseaprojects.rivo.data.net.HttpClients
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * SponsorBlock lookup.
 *
 * Neither extractor exposes SponsorBlock: NewPipe's `streamSegments` are the video's own
 * chapters, which is why the player also has a chapters sheet. The public API is the only
 * source for sponsor/self-promo/interaction segments, and it is queried per video with
 * the categories the user configured.
 */
class SponsorBlockClient(
    private val client: OkHttpClient = HttpClients.shared,
    private val baseUrl: String = DEFAULT_URL,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cache = ConcurrentHashMap<String, List<SponsorSegment>>()

    fun cached(videoId: String): List<SponsorSegment>? = cache[videoId]

    suspend fun segments(videoId: String, categories: List<String>): List<SponsorSegment> {
        val id = videoId.takeIf { YOUTUBE_ID.matches(it) } ?: return emptyList()
        val wanted = categories.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return emptyList()
        cache[id]?.let { return it }
        return withContext(Dispatchers.IO) {
            val result = runCatching { request(id, wanted) }.getOrDefault(emptyList())
            cache[id] = result
            result
        }
    }

    private fun request(videoId: String, categories: List<String>): List<SponsorSegment> {
        val encoded = URLEncoder.encode(
            categories.joinToString(prefix = "[", postfix = "]", separator = ",") { "\"$it\"" },
            "UTF-8",
        )
        val url = "$baseUrl/api/skipSegments?videoID=$videoId&categories=$encoded"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("SponsorBlock HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            return parse(body)
        }
    }

    fun parse(body: String): List<SponsorSegment> {
        val root = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull()
            ?: return emptyList()
        val entry = root.firstOrNull() ?: return emptyList()
        val decoded = runCatching {
            json.decodeFromJsonElement(SponsorBlockEntry.serializer(), entry)
        }.getOrNull() ?: return emptyList()
        return decoded.segments.mapNotNull { segment ->
            if (segment.segment.size < 2) return@mapNotNull null
            val start = (segment.segment[0] * 1000).toLong()
            val end = (segment.segment[1] * 1000).toLong()
            if (end <= start) return@mapNotNull null
            SponsorSegment(
                startMs = start,
                endMs = end,
                category = segment.category,
                title = segment.category.replaceFirstChar { it.uppercase() },
            )
        }
    }

    @Serializable
    private data class SponsorBlockEntry(
        val segments: List<SponsorBlockSegment> = emptyList(),
    )

    @Serializable
    private data class SponsorBlockSegment(
        val category: String = "",
        val segment: List<Double> = emptyList(),
    )

    private companion object {
        const val DEFAULT_URL = "https://sponsor.ajay.app"
        const val USER_AGENT = "rivo-android/0.8.7"
        val YOUTUBE_ID = Regex("^[A-Za-z0-9_-]{11}$")
    }
}
