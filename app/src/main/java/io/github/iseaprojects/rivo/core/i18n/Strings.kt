package io.github.iseaprojects.rivo.core.i18n

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.IllegalFormatException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Localized text source. Keys are the literal `TXT_*` names used by yt-x `.lang`
 * files, so an upstream language pack can be converted to JSON mechanically and a
 * user extension can override single strings without forking anything.
 */
interface Strings {
    val localeTag: String
    val values: Map<String, String>

    fun get(key: String): String?
    fun text(key: String, fallback: String = key): String
    fun format(key: String, vararg args: Any): String
    fun has(key: String): Boolean
}

class MapStrings(
    override val localeTag: String,
    private val primary: Map<String, String>,
    private val parent: Strings? = null,
) : Strings {

    override val values: Map<String, String> get() = primary

    override fun get(key: String): String? = primary[key] ?: parent?.get(key)

    override fun text(key: String, fallback: String): String = get(key) ?: fallback

    override fun format(key: String, vararg args: Any): String {
        val template = get(key) ?: return key
        return try {
            String.format(Locale.ROOT, template, *args)
        } catch (_: IllegalFormatException) {
            template
        }
    }

    override fun has(key: String): Boolean = get(key) != null
}

@Serializable
data class LangFile(
    val locale: String = "",
    val name: String = "",
    val extends: String? = null,
    val strings: Map<String, String> = emptyMap(),
)

data class LangInfo(
    val tag: String,
    val name: String,
) {
    companion object {
        val AUTO = LangInfo("auto", "Auto")
    }
}

/**
 * A language pack loaded from the SAF extension folder. [tag] is the file's locale, or
 * its base name when the file omits one; [strings] overrides the bundled bundle.
 */
data class LangOverlay(
    val name: String,
    val strings: Map<String, String>,
)

/**
 * Loads `assets/extensions/langs/<tag>.lang.json`, resolves `CONFIG_LANG` against the
 * system locale ("auto") and keeps the merged bundle hot when the language changes.
 *
 * SAF-provided language packs are layered on top: the merge order is base en -> selected
 * locale -> extension overlay. [externalLanguages] is keyed by the tag the pack declares.
 */
class StringsRepository(
    private val appContext: Context,
    config: StateFlow<io.github.iseaprojects.rivo.core.config.ConfigSnapshot>,
    scope: CoroutineScope,
    private val externalLanguages: StateFlow<Map<String, LangOverlay>> =
        MutableStateFlow(emptyMap()),
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val cache = ConcurrentHashMap<String, Map<String, String>>()
    private val languagesCache = ConcurrentHashMap<String, LangInfo>()

    /** Asset languages first, then SAF-only ones; a pack with the same tag overrides. */
    val languageList: StateFlow<List<LangInfo>> = externalLanguages
        .map { overlays -> languagesFor(overlays) }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = languagesFor(externalLanguages.value),
        )

    /** Snapshot for callers that cannot collect (menu builders run synchronously). */
    val languages: List<LangInfo> get() = languageList.value

    private fun languagesFor(overlays: Map<String, LangOverlay>): List<LangInfo> {
        val asset = assetLanguages()
        val external = overlays.map { (tag, overlay) ->
            LangInfo(tag = tag, name = overlay.name.ifBlank { tag })
        }.filter { info -> asset.none { it.tag.equals(info.tag, ignoreCase = true) } }
        return asset + external
    }

    val strings: StateFlow<Strings> = combine(config, externalLanguages) { snapshot, overlays ->
        val configured = snapshot.string(io.github.iseaprojects.rivo.core.config.ConfigKeys.LANG, "auto")
        val tag = resolveTag(configured)
        MapStrings(tag, bundle(tag, overlays))
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = MapStrings("en", bundle("en", externalLanguages.value)),
    )

    fun current(): Strings = strings.value

    fun resolveTag(configured: String): String {
        val available = languages.map { it.tag }
        if (available.isEmpty()) return "en"
        if (!configured.equals("auto", ignoreCase = true) && configured.isNotBlank()) {
            return match(configured, available) ?: "en"
        }
        return match(Locale.getDefault().toLanguageTag(), available) ?: "en"
    }

    fun readFile(tag: String): LangFile? = try {
        appContext.assets.open("$DIR/$tag$SUFFIX").bufferedReader().use { reader ->
            json.decodeFromString(LangFile.serializer(), reader.readText())
        }
    } catch (_: java.io.IOException) {
        null
    } catch (_: kotlinx.serialization.SerializationException) {
        null
    }

    private fun match(candidate: String, available: List<String>): String? {
        available.firstOrNull { it.equals(candidate, ignoreCase = true) }?.let { return it }
        val language = candidate.substringBefore('-').lowercase()
        available.firstOrNull { it.substringBefore('-').lowercase() == language }?.let { return it }
        return null
    }

    private fun bundle(tag: String, overlays: Map<String, LangOverlay> = externalLanguages.value): Map<String, String> {
        val asset = assetBundle(tag)
        val overlay = overlays.entries.firstOrNull { it.key.equals(tag, ignoreCase = true) }
            ?.value?.strings.orEmpty()
        return if (overlay.isEmpty()) asset else asset + overlay
    }

    private fun assetBundle(tag: String): Map<String, String> = cache.getOrPut(tag) {
        val own = readFile(tag)?.strings.orEmpty()
        if (tag == "en") own else assetBundle("en") + own
    }

    private fun assetLanguages(): List<LangInfo> {
        val names = try {
            appContext.assets.list(DIR)?.toList().orEmpty()
        } catch (_: java.io.IOException) {
            emptyList()
        }
        val infos = names
            .filter { it.endsWith(SUFFIX) }
            .map { it.removeSuffix(SUFFIX) }
            .distinct()
            .map { tag ->
                languagesCache.getOrPut(tag) {
                    val file = readFile(tag)
                    LangInfo(tag = tag, name = file?.name?.takeIf { it.isNotBlank() } ?: tag)
                }
            }
            .sortedWith(compareBy({ it.tag != "en" }, { it.tag }))
        return infos.ifEmpty { listOf(LangInfo("en", "English")) }
    }

    companion object {
        private const val DIR = "extensions/langs"
        private const val SUFFIX = ".lang.json"
    }
}
