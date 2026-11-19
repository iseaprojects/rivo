package io.github.iseaprojects.rivo.data.net

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * One cookie as understood by both the Netscape `cookies.txt` format and RFC 6265.
 */
data class StoredCookie(
    val domain: String,
    val hostOnly: Boolean,
    val path: String,
    val secure: Boolean,
    val httpOnly: Boolean,
    /** Unix seconds; 0 means session cookie. */
    val expiresAtEpochSeconds: Long,
    val name: String,
    val value: String,
) {
    val isExpired: Boolean
        get() = expiresAtEpochSeconds > 0 &&
            expiresAtEpochSeconds <= System.currentTimeMillis() / 1000

    /** `#HttpOnly_` prefix keeps the flag when exporting back to Netscape. */
    fun toNetscapeLine(): String {
        val prefix = if (httpOnly) "#HttpOnly_" else ""
        val includeSubdomains = if (hostOnly) "FALSE" else "TRUE"
        return listOf(
            "$prefix$domain",
            includeSubdomains,
            path,
            if (secure) "TRUE" else "FALSE",
            expiresAtEpochSeconds.toString(),
            name,
            value,
        ).joinToString("\t")
    }
}

/**
 * Netscape cookies.txt parsing, matching and re-serialisation.
 *
 * Deliberately free of Android types: cookie scoping is security-relevant (a leaked
 * `SID` cookie is account takeover) and deserves to be tested on the JVM rather than
 * only on a device.
 */
object CookieTxt {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    sealed interface ImportResult {
        data class Ok(val cookies: List<StoredCookie>, val skipped: Int) : ImportResult
        data class Failure(val reason: String) : ImportResult
    }

    /**
     * Parses a Netscape cookies.txt file. Lines that cannot be parsed are skipped and
     * counted instead of aborting the whole import, because browser exports routinely
     * contain blank lines, comments and unrelated sites.
     */
    fun parse(text: String): ImportResult {
        if (text.isBlank()) return ImportResult.Failure("empty cookie file")
        val cookies = mutableListOf<StoredCookie>()
        var skipped = 0

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd('\r', '\n')
            if (line.isBlank()) return@forEach
            if (line.startsWith("#") && !line.startsWith("#HttpOnly_")) return@forEach

            val httpOnly = line.startsWith("#HttpOnly_")
            val body = if (httpOnly) line.removePrefix("#HttpOnly_") else line
            val fields = body.split('\t')
            if (fields.size < 7) {
                skipped++
                return@forEach
            }
            val domainRaw = fields[0]
            val includeSubdomains = fields[1].equals("TRUE", ignoreCase = true)
            val path = fields[2].ifBlank { "/" }
            val secure = fields[3].equals("TRUE", ignoreCase = true)
            val expires = fields[4].trim().toLongOrNull() ?: 0L
            val name = fields[5]
            val value = fields.subList(6, fields.size).joinToString("\t")

            val hostOnly = !includeSubdomains
            val domain = domainRaw.removePrefix(".")
            if (domain.isBlank() || name.isBlank()) {
                skipped++
                return@forEach
            }
            if (!isPlausibleDomain(domain)) {
                skipped++
                return@forEach
            }

            cookies += StoredCookie(
                domain = domain.lowercase(Locale.ROOT),
                hostOnly = hostOnly,
                path = path,
                secure = secure,
                httpOnly = httpOnly,
                expiresAtEpochSeconds = expires,
                name = name,
                value = value,
            )
        }

        return if (cookies.isEmpty()) {
            ImportResult.Failure("no usable cookies")
        } else {
            ImportResult.Ok(cookies, skipped)
        }
    }

    /**
     * Parses the JSON array that EditThisCookie / Cookie-Editor / yt-dlp's
     * `--cookies-from-browser --cookies` chain produce, so a user with a browser export
     * does not have to convert it by hand.
     */
    fun parseJson(text: String): ImportResult {
        val array = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray
            ?: return ImportResult.Failure("not a JSON cookie array")
        val cookies = mutableListOf<StoredCookie>()
        var skipped = 0
        array.forEach { element ->
            val obj = element as? JsonObject
            if (obj == null) {
                skipped++
                return@forEach
            }
            val domain = obj.str("domain")?.removePrefix(".")?.lowercase(Locale.ROOT)
            val name = obj.str("name")
            val value = obj.str("value")
            if (domain.isNullOrBlank() || name.isNullOrBlank() || value == null ||
                !isPlausibleDomain(domain)
            ) {
                skipped++
                return@forEach
            }
            val expires = obj.double("expirationDate")?.toLong()
                ?: obj.double("expires")?.toLong()
                ?: 0L
            cookies += StoredCookie(
                domain = domain,
                hostOnly = false,
                path = obj.str("path")?.ifBlank { "/" } ?: "/",
                secure = obj.bool("secure") ?: false,
                httpOnly = obj.bool("httpOnly") ?: false,
                expiresAtEpochSeconds = expires,
                name = name,
                value = value,
            )
        }
        return if (cookies.isEmpty()) {
            ImportResult.Failure("no usable cookies")
        } else {
            ImportResult.Ok(cookies, skipped)
        }
    }

    /** Netscape header + one line per live cookie. */
    fun format(cookies: List<StoredCookie>): String = buildString {
        appendLine("# Netscape HTTP Cookie File")
        appendLine("# Exported by Rivo. Generated file, edits are lost on next export.")
        cookies.filterNot { it.isExpired }
            .forEach { appendLine(it.toNetscapeLine()) }
    }

    /** The `Cookie:` header value for [url], or null when nothing matches. */
    fun headerFor(cookies: List<StoredCookie>, url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
        val path = uri.path?.takeIf { it.isNotEmpty() } ?: "/"

        val matching = cookies
            .asSequence()
            .filterNot { it.isExpired }
            .filter { matches(it, host, path, secureRequest = scheme == "https") }
            // RFC 6265 5.4: longer paths first, then older cookies first.
            .sortedByDescending { it.path.length }
            .toList()

        if (matching.isEmpty()) return null
        return matching.joinToString("; ") { "${it.name}=${it.value}" }
    }

    fun matches(cookie: StoredCookie, host: String, path: String, secureRequest: Boolean): Boolean {
        if (cookie.secure && !secureRequest) return false
        if (!pathMatches(cookie.path, path)) return false
        val domain = cookie.domain.lowercase(Locale.ROOT)
        if (cookie.hostOnly) return host == domain
        return host == domain || host.endsWith(".$domain")
    }

    /**
     * A cookie is scoped to the exact origin plus its subdomains, never to a bare TLD.
     * Without this guard a malicious/odd cookies.txt entry for `.com` would be attached
     * to every request the app makes.
     */
    private fun isPlausibleDomain(domain: String): Boolean {
        if (domain == "localhost") return true
        if (domain.startsWith(".") || domain.endsWith(".")) return false
        if (!domain.contains('.')) return false
        return true
    }

    private fun pathMatches(cookiePath: String, requestPath: String): Boolean {
        val cp = if (cookiePath.startsWith("/")) cookiePath else "/$cookiePath"
        if (cp == "/") return true
        if (requestPath == cp) return true
        if (!requestPath.startsWith(cp)) return false
        return cp.endsWith("/") || requestPath.getOrNull(cp.length) == '/'
    }

    /** `name=value; ...` from a WebView cookie string, scoped to one domain. */
    fun fromCookieHeader(
        header: String,
        domain: String,
        path: String = "/",
        secure: Boolean = true,
    ): List<StoredCookie> = header.split(';').mapNotNull { part ->
        val trimmed = part.trim()
        if (trimmed.isEmpty()) return@mapNotNull null
        val name = trimmed.substringBefore('=', "")
        val value = trimmed.substringAfter('=', "")
        if (name.isEmpty() || value.isEmpty()) return@mapNotNull null
        StoredCookie(
            domain = domain.removePrefix(".").lowercase(Locale.ROOT),
            hostOnly = false,
            path = path,
            secure = secure,
            httpOnly = false,
            expiresAtEpochSeconds = 0,
            name = name,
            value = value,
        )
    }

    /** Encodes a cookie list as the query-string form yt-dlp accepts for `--add-header`. */
    fun encodeForYtDlp(cookies: List<StoredCookie>, hostSuffix: String): String {
        val matching = cookies.filterNot { it.isExpired }
            .filter { it.domain == hostSuffix || it.domain.endsWith(".$hostSuffix") }
        return matching.joinToString("; ") { "${it.name}=${it.value}" }
    }

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.double(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull
            ?: (this[key] as? JsonPrimitive)?.content?.let { it == "true" }
}
