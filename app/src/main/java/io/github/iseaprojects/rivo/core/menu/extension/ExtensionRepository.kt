package io.github.iseaprojects.rivo.core.menu.extension

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import io.github.iseaprojects.rivo.core.config.ConfigKeys
import io.github.iseaprojects.rivo.core.config.ConfigSnapshot
import io.github.iseaprojects.rivo.core.i18n.LangFile
import io.github.iseaprojects.rivo.core.i18n.LangOverlay
import io.github.iseaprojects.rivo.core.menu.MenuCommand
import io.github.iseaprojects.rivo.core.menu.MenuExtensionEntry
import io.github.iseaprojects.rivo.core.menu.MenuSite
import io.github.iseaprojects.rivo.core.theme.ThemeParser
import io.github.iseaprojects.rivo.core.theme.ThemeSpec
import java.io.FileNotFoundException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Everything the extension folders contribute, in one immutable snapshot.
 *
 * Assets are always loaded (they ship with the APK and cannot be broken by a stale SAF
 * grant). The user-picked tree is layered on top of them and is honouring
 * `CONFIG_EXTENSIONS_ENABLED` and `CONFIG_AUTOLOADED_EXTENSIONS`.
 */
data class ExtensionSnapshot(
    val enabled: Boolean = true,
    val folderLabel: String = "",
    val autoloaded: String = "",
    val patches: Map<String, List<UiMenuPatch>> = emptyMap(),
    val commands: List<MenuCommand> = emptyList(),
    val sites: List<MenuSite> = emptyList(),
    val themes: List<ThemeSpec> = emptyList(),
    val languageOverlays: Map<String, LangOverlay> = emptyMap(),
    val entries: List<MenuExtensionEntry> = emptyList(),
    val issues: List<String> = emptyList(),
)

/**
 * Loads extensions from `assets/extensions/…` and from a SAF folder selected by the user.
 *
 * The folder layout mirrors upstream: `ui/`, `sites/`, `cmds/`, `themes/`, `langs/`. Files
 * with the port's JSON suffixes (`.ui.json`, `.sites.json`, `.cmds.json`, `.theme.json`,
 * `.lang.json`) are parsed; upstream shell scripts are reported as unsupported instead of
 * being executed, because an Android app has no shell to source them into and running
 * arbitrary code from a folder is not a feature this port is willing to add.
 *
 * Reloading is explicit and serialized. The menu engine reads [patchesFor] synchronously
 * from the last snapshot, so a slow cloud provider never stalls a frame.
 */
class ExtensionRepository(
    context: Context,
    private val config: StateFlow<ConfigSnapshot>,
    private val scope: CoroutineScope,
) : UiExtensionSource {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val uiLoader = UiExtensionLoader()
    private val fileParser = ExtensionFileParser()
    private val mutex = Mutex()

    private val _state = MutableStateFlow(ExtensionSnapshot())
    val state: StateFlow<ExtensionSnapshot> = _state.asStateFlow()

    private val _themes = MutableStateFlow<List<ThemeSpec>>(emptyList())
    val themes: StateFlow<List<ThemeSpec>> = _themes.asStateFlow()

    private val _languageOverlays = MutableStateFlow<Map<String, LangOverlay>>(emptyMap())
    val languageOverlays: StateFlow<Map<String, LangOverlay>> = _languageOverlays.asStateFlow()

    init {
        scope.launch { reload() }
    }

    override fun patchesFor(menuId: String): List<UiMenuPatch> =
        _state.value.patches[menuId].orEmpty()

    fun folderUri(): Uri? = prefs.getString(KEY_FOLDER, null)?.let { value ->
        runCatching { value.toUri() }.getOrNull()
    }

    fun folderLabel(): String = folderUri()?.let(::describe) ?: ""

    suspend fun setFolder(uri: String?) {
        if (uri.isNullOrBlank()) prefs.edit { remove(KEY_FOLDER) }
        else prefs.edit { putString(KEY_FOLDER, uri) }
        reload()
    }

    suspend fun reload() {
        mutex.withLock {
            val snapshot = withContext(Dispatchers.IO) { load() }
            _state.value = snapshot
            _themes.value = snapshot.themes
            _languageOverlays.value = snapshot.languageOverlays
        }
    }

    private fun load(): ExtensionSnapshot {
        val snapshotConfig = config.value
        val enabled = snapshotConfig.bool(ConfigKeys.EXTENSIONS_ENABLED, true)
        val autoload = snapshotConfig.list(ConfigKeys.AUTOLOADED_EXTENSIONS)
        val issues = mutableListOf<String>()
        val patches = LinkedHashMap<String, MutableList<UiMenuPatch>>()
        val commands = mutableListOf<MenuCommand>()
        val sites = mutableListOf<MenuSite>()
        val themes = mutableListOf<ThemeSpec>()
        val overlays = LinkedHashMap<String, LangOverlay>()
        val entries = mutableListOf<MenuExtensionEntry>()

        loadAssetUi(patches, issues)

        val uri = folderUri()
        if (!enabled) {
            return ExtensionSnapshot(
                enabled = false,
                folderLabel = uri?.let(::describe).orEmpty(),
                autoloaded = autoload.joinToString(", "),
                patches = patches,
                issues = issues,
            )
        }
        if (uri == null) {
            return ExtensionSnapshot(
                enabled = true,
                autoloaded = autoload.joinToString(", "),
                patches = patches,
                issues = issues,
            )
        }

        val root = runCatching { DocumentFile.fromTreeUri(appContext, uri) }.getOrNull()
        if (root == null || !root.exists() || !root.isDirectory) {
            issues += "the extension folder is no longer readable; pick it again"
            return ExtensionSnapshot(
                enabled = true,
                folderLabel = describe(uri),
                autoloaded = autoload.joinToString(", "),
                patches = patches,
                issues = issues,
            )
        }

        val files = mutableListOf<Pair<String, DocumentFile>>()
        walk(root, "", files, issues)
        files.take(MAX_FILES).forEach { (relative, document) ->
            val name = document.name.orEmpty()
            if (!isAutoloaded(relative, name, autoload)) return@forEach
            val text = read(document, relative, issues) ?: return@forEach
            when {
                name.endsWith(UI_SUFFIX) -> {
                    val extension = uiLoader.parse(relative, text)
                    issues += extension.issues
                    extension.patches.forEach { (menuId, patch) ->
                        patches.getOrPut(menuId) { mutableListOf() }.add(patch)
                    }
                    entries += MenuExtensionEntry(relative, "ui", "menus: ${extension.patches.size}")
                }

                name.endsWith(THEME_SUFFIX) -> {
                    val theme = ThemeParser.parse(text)
                    if (theme == null) {
                        issues += "$relative: invalid theme (id, name and colors are required)"
                    } else {
                        themes += theme
                        entries += MenuExtensionEntry(relative, "theme", theme.name)
                    }
                }

                name.endsWith(LANG_SUFFIX) -> {
                    val file = runCatching {
                        json.decodeFromString(LangFile.serializer(), text)
                    }.getOrNull()
                    if (file == null) {
                        issues += "$relative: invalid language pack"
                    } else {
                        val tag = file.locale.trim().ifBlank {
                            name.removeSuffix(LANG_SUFFIX).takeIf { it.isNotBlank() } ?: "en"
                        }
                        val existing = overlays[tag]
                        overlays[tag] = LangOverlay(
                            name = file.name.takeIf { it.isNotBlank() }
                                ?: existing?.name
                                ?: tag,
                            strings = (existing?.strings.orEmpty()) + file.strings,
                        )
                        entries += MenuExtensionEntry(relative, "lang", "$tag (${file.strings.size})")
                    }
                }

                name.endsWith(SITES_SUFFIX) -> {
                    val parsed = fileParser.parseSites(relative, slugOf(relative), text)
                    issues += parsed.issues
                    sites += parsed.sites
                    entries += MenuExtensionEntry(relative, "sites", "${parsed.sites.size}")
                }

                name.endsWith(CMDS_SUFFIX) -> {
                    val parsed = fileParser.parseCommands(relative, slugOf(relative), text)
                    issues += parsed.issues
                    commands += parsed.commands
                    entries += MenuExtensionEntry(relative, "cmds", "${parsed.commands.size}")
                }

                SHELL_SUFFIXES.any { name.endsWith(it) } -> {
                    issues += "$relative: shell extensions are not executable on Android; " +
                        "convert it to the JSON format"
                }
            }
        }

        return ExtensionSnapshot(
            enabled = true,
            folderLabel = describe(uri),
            autoloaded = autoload.joinToString(", "),
            patches = patches.mapValues { it.value.toList() },
            commands = commands.distinctBy { it.id }.sortedBy { it.name.lowercase() },
            sites = sites.distinctBy { it.id }.sortedBy { it.name.lowercase() },
            themes = themes.distinctBy { it.id }.sortedBy { it.name.lowercase() },
            languageOverlays = overlays,
            entries = entries.distinctBy { it.name }.sortedBy { it.name },
            issues = issues,
        )
    }

    private fun loadAssetUi(
        patches: MutableMap<String, MutableList<UiMenuPatch>>,
        issues: MutableList<String>,
    ) {
        val names = runCatching { appContext.assets.list(ASSET_UI)?.toList().orEmpty() }
            .getOrDefault(emptyList())
        names.filter { it.endsWith(UI_SUFFIX) }.sorted().forEach { name ->
            val text = runCatching {
                appContext.assets.open("$ASSET_UI/$name").bufferedReader().use { it.readText() }
            }.getOrNull() ?: return@forEach
            val extension = uiLoader.parse(name, text)
            issues += extension.issues
            extension.patches.forEach { (menuId, patch) ->
                patches.getOrPut(menuId) { mutableListOf() }.add(patch)
            }
        }
    }

    private fun walk(
        dir: DocumentFile,
        prefix: String,
        out: MutableList<Pair<String, DocumentFile>>,
        issues: MutableList<String>,
    ) {
        if (out.size >= MAX_FILES) return
        val children = runCatching { dir.listFiles() }.getOrElse { error ->
            issues += "${prefix.ifBlank { "extensions" }}: ${error.message ?: "could not be listed"}"
            return
        }
        children.forEach { child ->
            val name = child.name.orEmpty()
            if (name.isBlank()) return@forEach
            val relative = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) {
                if (relative.count { it == '/' } < MAX_DEPTH) walk(child, relative, out, issues)
            } else {
                out += relative to child
            }
        }
    }

    private fun read(
        document: DocumentFile,
        relative: String,
        issues: MutableList<String>,
    ): String? {
        val size = document.length()
        if (size > MAX_BYTES) {
            issues += "$relative: larger than ${MAX_BYTES / 1024} KB; skipped"
            return null
        }
        return try {
            appContext.contentResolver.openInputStream(document.uri)?.use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            } ?: run {
                issues += "$relative: could not be opened"
                null
            }
        } catch (_: FileNotFoundException) {
            issues += "$relative: the file disappeared"
            null
        } catch (error: Throwable) {
            issues += "$relative: ${error.message ?: "could not be read"}"
            null
        }
    }

    private fun isAutoloaded(relative: String, fileName: String, autoload: List<String>): Boolean {
        if (autoload.isEmpty()) return true
        val base = fileName
            .removeSuffix(UI_SUFFIX)
            .removeSuffix(THEME_SUFFIX)
            .removeSuffix(LANG_SUFFIX)
            .removeSuffix(SITES_SUFFIX)
            .removeSuffix(CMDS_SUFFIX)
        return autoload.any { entry ->
            val wanted = entry.trim().trimStart('/')
            wanted.isNotEmpty() &&
                (relative.equals(wanted, ignoreCase = true) ||
                    fileName.equals(wanted, ignoreCase = true) ||
                    base.equals(wanted, ignoreCase = true) ||
                    wanted.endsWith("/$base", ignoreCase = true) ||
                    wanted.substringBefore('.').equals(base, ignoreCase = true))
        }
    }

    private fun describe(uri: Uri): String =
        uri.lastPathSegment?.substringAfter(':')?.takeIf { it.isNotBlank() } ?: uri.toString()

    private fun slugOf(relative: String): String =
        ExtensionFileParser.slug(relative.substringBeforeLast('.'))

    companion object {
        private const val PREFS = "extensions"
        private const val KEY_FOLDER = "tree_uri"

        private const val ASSET_UI = "extensions/ui"

        private const val UI_SUFFIX = ".ui.json"
        private const val THEME_SUFFIX = ".theme.json"
        private const val LANG_SUFFIX = ".lang.json"
        private const val SITES_SUFFIX = ".sites.json"
        private const val CMDS_SUFFIX = ".cmds.json"

        /** Upstream shell extensions, recognized only so the error message can be precise. */
        private val SHELL_SUFFIXES = listOf(".ui", ".site", ".sites", ".cmds", ".theme", ".lang")

        private const val MAX_FILES = 200
        private const val MAX_BYTES = 512 * 1024
        private const val MAX_DEPTH = 3
    }
}
