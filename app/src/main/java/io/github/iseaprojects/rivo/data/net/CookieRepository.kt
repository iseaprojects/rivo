package io.github.iseaprojects.rivo.data.net

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.github.iseaprojects.rivo.core.config.ConfigKeys
import io.github.iseaprojects.rivo.core.config.ConfigSnapshot
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read-only view used by the transport and extractor layers. */
interface CookieProvider {
    val enabled: Boolean
    val count: Int
    fun cookieHeader(url: String): String?
    fun exportNetscape(hostSuffix: String? = null): String?
}

/**
 * Encrypted, app-private cookie storage.
 *
 * yt-x shells out to `yt-dlp --cookies-from-browser`; on Android there is no browser
 * profile to harvest, so cookies come from two explicit sources: an imported
 * `cookies.txt` and the built-in WebView login. Both are account credentials, so they
 * live in `EncryptedSharedPreferences` (AES-256-GCM, key in the Android Keystore) and
 * are never written to the plain-text config file.
 *
 * If the Keystore is unavailable the repository degrades to memory-only for the session
 * instead of silently persisting credentials in the clear.
 */
class CookieRepository(
    context: Context,
    private val config: () -> ConfigSnapshot,
) : CookieProvider {

    private val appContext = context.applicationContext

    @Volatile
    private var cookies: List<StoredCookie> = emptyList()

    @Volatile
    private var loaded: Boolean = false

    @Volatile
    private var persisted: Boolean = false

    private val prefs: SharedPreferences? by lazy { openEncryptedPrefs() }

    override val enabled: Boolean
        get() = config().bool(ConfigKeys.COOKIES_ENABLED, false)

    override val count: Int
        get() {
            ensureLoaded()
            return cookies.size
        }

    override fun cookieHeader(url: String): String? {
        if (!enabled) return null
        ensureLoaded()
        // Cookies are only ever attached to the origin that owns them; CookieTxt.matches
        // enforces host/subdomain scoping and the secure flag.
        return CookieTxt.headerFor(cookies, url)
    }

    override fun exportNetscape(hostSuffix: String?): String? {
        ensureLoaded()
        val selected = if (hostSuffix == null) {
            cookies
        } else {
            cookies.filter { it.domain == hostSuffix || it.domain.endsWith(".$hostSuffix") }
        }
        if (selected.isEmpty()) return null
        return CookieTxt.format(selected)
    }

    fun snapshot(): List<StoredCookie> {
        ensureLoaded()
        return cookies
    }

    suspend fun importText(text: String): Result {
        ensureLoaded()
        val parsed = when {
            text.trimStart().startsWith("[") -> CookieTxt.parseJson(text)
            else -> CookieTxt.parse(text)
        }
        return when (parsed) {
            is CookieTxt.ImportResult.Failure -> Result.Failure(parsed.reason)
            is CookieTxt.ImportResult.Ok -> {
                // Merge by (domain, path, name): re-importing an updated cookies.txt
                // replaces values instead of duplicating entries.
                val merged = merge(parsed.cookies)
                commit(merged)
                Result.Ok(parsed.cookies.size, parsed.skipped)
            }
        }
    }

    suspend fun importFrom(uri: Uri): Result = withContext(Dispatchers.IO) {
        val text = try {
            appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: return@withContext Result.Failure("cannot open the selected file")
        } catch (_: FileNotFoundException) {
            return@withContext Result.Failure("cannot open the selected file")
        } catch (error: SecurityException) {
            return@withContext Result.Failure(error.message ?: "permission denied")
        }
        importText(text)
    }

    /**
     * Captures the session cookies that the WebView login flow left in the platform
     * CookieManager. Only cookies scoped to youtube.com are kept.
     */
    suspend fun captureFromWebView(): Result {
        ensureLoaded()
        val header = android.webkit.CookieManager.getInstance()
            .getCookie("https://www.youtube.com")
        if (header.isNullOrBlank()) {
            return Result.Failure("no YouTube session found in the login screen")
        }
        val captured = CookieTxt.fromCookieHeader(header, domain = "youtube.com")
        // A logged-in YouTube session always carries at least one of these.
        val authenticated = captured.any { it.name in AUTH_COOKIE_NAMES }
        if (!authenticated) {
            return Result.Failure("login did not complete: no session cookies")
        }
        val merged = merge(captured)
        commit(merged)
        return Result.Ok(captured.size, 0)
    }

    fun clear() {
        synchronized(this) {
            cookies = emptyList()
            loaded = true
            persisted = false
        }
        runCatching { prefs?.edit { remove(KEY_COOKIES) } }
    }

    sealed interface Result {
        data class Ok(val imported: Int, val skipped: Int) : Result
        data class Failure(val reason: String) : Result
    }

    private fun merge(incoming: List<StoredCookie>): List<StoredCookie> {
        val byKey = LinkedHashMap<String, StoredCookie>()
        cookies.forEach { byKey[keyOf(it)] = it }
        incoming.forEach { incomingCookie ->
            val key = keyOf(incomingCookie)
            val existing = byKey[key]
            byKey[key] = if (existing == null || incomingCookie.value.isNotEmpty()) {
                incomingCookie
            } else {
                existing
            }
        }
        return byKey.values.filterNot { it.isExpired }
    }

    private fun keyOf(cookie: StoredCookie): String =
        "${cookie.domain}|${cookie.path}|${cookie.name}"

    private fun commit(list: List<StoredCookie>) {
        cookies = list
        loaded = true
        if (persisted || persist(list)) {
            persisted = true
        }
    }

    private fun persist(list: List<StoredCookie>): Boolean {
        val store = prefs ?: return false
        return runCatching {
            store.edit { putString(KEY_COOKIES, CookieTxt.format(list)) }
            true
        }.getOrElse { error ->
            Log.w(TAG, "cookie store unavailable, keeping cookies in memory: ${error.message}")
            false
        }
    }

    private fun load() {
        val raw = runCatching { prefs?.getString(KEY_COOKIES, null) }.getOrNull() ?: return
        persisted = true
        when (val parsed = CookieTxt.parse(raw)) {
            is CookieTxt.ImportResult.Ok -> cookies = parsed.cookies
            is CookieTxt.ImportResult.Failure ->
                Log.w(TAG, "stored cookie file unreadable: ${parsed.reason}")
        }
    }

    /**
     * Reading the encrypted store touches the Keystore and disk, so it happens on first
     * use (already off the main thread, inside an extraction) rather than at process start.
     */
    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            load()
            loaded = true
        }
    }

    // `EncryptedSharedPreferences` is deprecated in security-crypto 1.1.0 in favour of a
    // hand-rolled Keystore cipher, but it is still the supported way to store secrets
    // without writing crypto code, and the replacement has no drop-in API yet.
    @Suppress("DEPRECATION")
    private fun openEncryptedPrefs(): SharedPreferences? = runCatching {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse { error ->
        Log.w(TAG, "encrypted prefs unavailable: ${error.message}")
        null
    }

    private companion object {
        const val TAG = "rivo.cookies"
        const val PREFS_NAME = "rivo_secure_cookies"
        const val KEY_COOKIES = "cookies_netscape"

        /** Any of these proves the WebView actually authenticated. */
        val AUTH_COOKIE_NAMES = setOf(
            "SID", "HSID", "SSID", "APISID", "SAPISID", "LOGIN_INFO", "__Secure-1PSID",
            "__Secure-3PSID", "__Secure-1PAPISID", "__Secure-3PAPISID",
        )
    }
}
