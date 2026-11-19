package io.github.iseaprojects.rivo.core.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One GitHub release, reduced to what the Update screen shows. */
data class ReleaseInfo(
    val tag: String,
    val name: String,
    val notes: String,
    val url: String,
    val prerelease: Boolean,
    val draft: Boolean,
    val publishedAt: String?,
)

sealed interface ReleaseFeedResult {
    data class Ok(val releases: List<ReleaseInfo>) : ReleaseFeedResult
    data class Failed(val message: String) : ReleaseFeedResult
}

/**
 * Reads `https://api.github.com/repos/<owner>/<repo>/releases`.
 *
 * Parsed by hand instead of with a serializer for one type: the response is a large array
 * of objects whose fields change rarely but include nested objects the app does not need.
 * Manual access keeps the app from failing a whole check because GitHub added a field.
 */
object ReleaseFeed {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(body: String): ReleaseFeedResult {
        val array = try {
            json.parseToJsonElement(body).jsonArray
        } catch (error: Throwable) {
            return ReleaseFeedResult.Failed(error.message ?: "the release list is not valid JSON")
        }
        if (array.isEmpty()) {
            // A repository with no releases answers `[]`; that is "up to date", not a failure.
            return ReleaseFeedResult.Ok(emptyList())
        }
        val releases = array.mapNotNull(::releaseOf)
        if (releases.isEmpty()) {
            return ReleaseFeedResult.Failed("the release list has no usable entries")
        }
        return ReleaseFeedResult.Ok(releases)
    }

    private fun releaseOf(element: kotlinx.serialization.json.JsonElement): ReleaseInfo? {
        val item = runCatching { element.jsonObject }.getOrNull() ?: return null
        val tag = item.string("tag_name")
            ?: item.string("name")
            ?: return null
        val notes = item.string("body").orEmpty()
        return ReleaseInfo(
            tag = tag,
            name = item.string("name") ?: tag,
            notes = notes,
            url = item.string("html_url").orEmpty(),
            prerelease = item.bool("prerelease"),
            draft = item.bool("draft"),
            publishedAt = item.string("published_at"),
        )
    }

    private fun JsonObject.string(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun JsonObject.bool(key: String): Boolean =
        runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: false
}

/**
 * Compares release tags (`v0.8.7`, `0.8.8-rc1`, `0.8.7-rivo.10`) without pulling in a
 * semver library. Missing segments are zero, so `0.9` equals `0.9.0`; a pre-release suffix
 * sorts *below* the matching release, which is what GitHub's `prerelease` flag means too.
 * Pre-release identifiers are split on dots and compared the semver way, so `rivo.10` is
 * newer than `rivo.9` instead of sorting as text.
 */
object VersionNumbers {

    fun compare(left: String, right: String): Int {
        val (leftCore, leftPre) = split(left)
        val (rightCore, rightPre) = split(right)
        val length = maxOf(leftCore.size, rightCore.size)
        for (index in 0 until length) {
            val a = leftCore.getOrElse(index) { 0 }
            val b = rightCore.getOrElse(index) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return when {
            leftPre == null && rightPre == null -> 0
            leftPre == null -> 1
            rightPre == null -> -1
            else -> comparePrerelease(leftPre, rightPre)
        }
    }

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    /** Semver 11.4: numeric identifiers compare numerically and sort below alphanumeric ones. */
    private fun comparePrerelease(left: String, right: String): Int {
        val a = left.split('.')
        val b = right.split('.')
        for (index in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrNull(index) ?: return -1
            val y = b.getOrNull(index) ?: return 1
            val nx = x.toIntOrNull()
            val ny = y.toIntOrNull()
            val result = when {
                nx != null && ny != null -> nx.compareTo(ny)
                nx != null -> -1
                ny != null -> 1
                else -> x.compareTo(y)
            }
            if (result != 0) return result
        }
        return 0
    }

    private fun split(value: String): Pair<List<Int>, String?> {
        val cleaned = value.trim().removePrefix("v").removePrefix("V")
        val core = cleaned.substringBefore('-').substringBefore('+')
        val suffix = cleaned.substringAfter('-', "").substringBefore('+').takeIf { it.isNotBlank() }
        val numbers = core.split('.').map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        return numbers to suffix
    }
}

/**
 * Picks the release the Update row should offer.
 *
 * Drafts are ignored. Stable builds ignore pre-releases; `CONFIG_UPDATE_CHANNEL=prerelease`
 * includes them. Among candidates the highest version wins, and a tag that is not newer
 * than the running one is reported as current rather than as an update.
 */
object ReleaseSelection {

    fun latest(releases: List<ReleaseInfo>, includePrerelease: Boolean): ReleaseInfo? =
        releases
            .filterNot { it.draft }
            .filter { includePrerelease || !it.prerelease }
            .maxWithOrNull { left, right -> VersionNumbers.compare(left.tag, right.tag) }

    fun newerThan(
        releases: List<ReleaseInfo>,
        currentVersion: String,
        includePrerelease: Boolean,
    ): ReleaseInfo? = latest(releases, includePrerelease)
        ?.takeIf { VersionNumbers.isNewer(it.tag, currentVersion) }
}
