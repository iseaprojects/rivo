package io.github.iseaprojects.rivo.core.theme

import android.content.Context
import io.github.iseaprojects.rivo.core.config.ConfigKeys
import io.github.iseaprojects.rivo.core.config.ConfigSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A color scheme extension. `terminal` keeps the exact THEME_FZF_* concepts of yt-x
 * (icon primary/secondary/accent/error, preview key/value/divider, pointer) so the
 * imported palette repaints both Material surfaces and the TUI accents.
 */
data class ThemeSpec(
    val id: String,
    val name: String,
    val dark: Boolean = true,
    val colors: Map<String, String> = emptyMap(),
    val terminal: Map<String, String> = emptyMap(),
) {
    fun colorArgb(token: String): Int? = colors[token]?.let(ColorHex::parseArgb)

    fun terminalArgb(token: String, fallbackToken: String? = null): Int? =
        terminal[token]?.let(ColorHex::parseArgb)
            ?: fallbackToken?.let { terminal[it]?.let(ColorHex::parseArgb) }

    companion object {
        const val DEFAULT_ID = "tokyo-night"

        val FALLBACK = ThemeSpec(
            id = "fallback",
            name = "Fallback",
            dark = true,
            colors = mapOf(
                "primary" to "#7aa2f7",
                "onPrimary" to "#16161e",
                "background" to "#16161e",
                "onBackground" to "#c0caf5",
                "surface" to "#1a1b26",
                "onSurface" to "#c0caf5",
                "surfaceVariant" to "#24283b",
                "onSurfaceVariant" to "#a9b1d6",
                "outline" to "#545c7e",
                "error" to "#f7768e",
            ),
            terminal = mapOf(
                "iconPrimary" to "#2ac3de",
                "iconError" to "#f7768e",
            ),
        )
    }
}

object ColorHex {
    /** Accepts #RGB, #RRGGBB and #AARRGGBB. Returns null for anything else. */
    fun parseArgb(value: String): Int? {
        val hex = value.trim().removePrefix("#")
        if (hex.isEmpty()) return null
        return try {
            when (hex.length) {
                3 -> {
                    val r = hex[0].digitToInt(16) * 17
                    val g = hex[1].digitToInt(16) * 17
                    val b = hex[2].digitToInt(16) * 17
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                6 -> (0xFF000000L or hex.toLong(16)).toInt()
                8 -> hex.toLong(16).toInt()
                else -> null
            }
        } catch (_: NumberFormatException) {
            null
        }
    }
}

@Serializable
private data class ThemeFile(
    val id: String = "",
    val name: String = "",
    val dark: Boolean = true,
    val colors: Map<String, String> = emptyMap(),
    val terminal: Map<String, String> = emptyMap(),
)

class ThemeRepository(
    private val appContext: Context,
    config: StateFlow<ConfigSnapshot>,
    scope: CoroutineScope,
    /** Themes contributed by the SAF extension folder; assets always win for built-ins. */
    private val external: StateFlow<List<ThemeSpec>> = MutableStateFlow(emptyList()),
) {

    private val _themes = MutableStateFlow(loadThemes())

    val themes: StateFlow<List<ThemeSpec>> = combine(_themes, external) { assets, extra ->
        (extra + assets).distinctBy { it.id }.sortedBy { it.name.lowercase() }
            .ifEmpty { listOf(ThemeSpec.FALLBACK) }
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = (external.value + _themes.value).distinctBy { it.id }
            .ifEmpty { listOf(ThemeSpec.FALLBACK) },
    )

    val current: StateFlow<ThemeSpec> = combine(config, themes) { snapshot, themes ->
        val wanted = snapshot.string(ConfigKeys.THEME, ThemeSpec.DEFAULT_ID)
        themes.firstOrNull { it.id == wanted }
            ?: themes.firstOrNull { it.id == ThemeSpec.DEFAULT_ID }
            ?: themes.firstOrNull()
            ?: ThemeSpec.FALLBACK
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = resolve(config.value.string(ConfigKeys.THEME, ThemeSpec.DEFAULT_ID)),
    )

    fun reload() {
        _themes.value = loadThemes()
    }

    /** Imports a `.theme.json` payload (used by the SAF extension importer in F7). */
    fun importTheme(text: String): Result<ThemeSpec> {
        val spec = ThemeParser.parse(text)
            ?: return Result.failure(IllegalArgumentException("Invalid theme: missing id, name or colors"))
        if (spec.colors.size < 4) {
            return Result.failure(IllegalArgumentException("Invalid theme: at least 4 colors are required"))
        }
        val merged = _themes.value.filterNot { it.id == spec.id } + spec
        _themes.value = merged.sortedBy { it.name.lowercase() }
        return Result.success(spec)
    }

    private fun resolve(id: String): ThemeSpec =
        themes.value.firstOrNull { it.id == id }
            ?: themes.value.firstOrNull { it.id == ThemeSpec.DEFAULT_ID }
            ?: themes.value.firstOrNull()
            ?: ThemeSpec.FALLBACK

    private fun loadThemes(): List<ThemeSpec> {
        val files = try {
            appContext.assets.list(DIR)?.toList().orEmpty()
        } catch (_: java.io.IOException) {
            emptyList()
        }
        val loaded = files
            .filter { it.endsWith(SUFFIX) }
            .mapNotNull { name ->
                try {
                    appContext.assets.open("$DIR/$name").bufferedReader().use { reader ->
                        ThemeParser.parse(reader.readText())
                    }
                } catch (_: java.io.IOException) {
                    null
                }
            }
            .distinctBy { it.id }
            .sortedBy { it.name.lowercase() }
        return loaded.ifEmpty { listOf(ThemeSpec.FALLBACK) }
    }

    companion object {
        private const val DIR = "extensions/themes"
        private const val SUFFIX = ".theme.json"
    }
}

/**
 * One parser for asset themes and SAF-provided `.theme.json` files. Kept pure so the
 * extension folder can validate a file before offering it in the theme picker.
 */
object ThemeParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    fun parse(text: String): ThemeSpec? {
        val file = try {
            json.decodeFromString(ThemeFile.serializer(), text)
        } catch (_: kotlinx.serialization.SerializationException) {
            return null
        }
        if (file.id.isBlank() || file.name.isBlank() || file.colors.isEmpty()) return null
        val colors = file.colors.mapNotNull { (key, value) ->
            ColorHex.parseArgb(value)?.let { key to value }
        }.toMap()
        if (colors.isEmpty()) return null
        return ThemeSpec(
            id = file.id,
            name = file.name,
            dark = file.dark,
            colors = colors,
            terminal = file.terminal,
        )
    }
}
