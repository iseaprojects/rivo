package io.github.iseaprojects.rivo.core.menu.extension

import android.content.Context
import io.github.iseaprojects.rivo.core.menu.MenuNode
import io.github.iseaprojects.rivo.core.menu.MenuNodeKind
import io.github.iseaprojects.rivo.core.menu.MenuPayload
import io.github.iseaprojects.rivo.core.menu.MenuSource
import io.github.iseaprojects.rivo.core.menu.MenuTone
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `ui/<name>.ui.json` - the declarative half of yt-x's UI extensions.
 *
 * ```json
 * {
 *   "schema": "rivo.ui/1",
 *   "menus": {
 *     "main": {
 *       "sort": ["search", "feed"],
 *       "filter": "^legacy\\.",
 *       "dropUnlisted": false,
 *       "inject": [
 *         {
 *           "id": "sites",
 *           "icon": "\uE5FF",
 *           "label": "Sites",
 *           "handler": "custom.sites",
 *           "position": "after:channels",
 *           "children": [
 *             { "id": "sites.dailymotion", "label": "Dailymotion", "handler": "custom.sites.dailymotion" }
 *           ]
 *         }
 *       ]
 *     }
 *   }
 * }
 * ```
 */
@Serializable
data class UiExtensionFile(
    @SerialName("menus") val menus: Map<String, UiMenuPatchJson> = emptyMap(),
)

@Serializable
data class UiMenuPatchJson(
    val sort: List<String>? = null,
    val filter: String? = null,
    val dropUnlisted: Boolean = false,
    val inject: List<UiNodeJson> = emptyList(),
)

@Serializable
data class UiNodeJson(
    val id: String,
    val icon: String? = null,
    val iconKey: String? = null,
    val label: String? = null,
    val labelKey: String? = null,
    val detail: String? = null,
    val handler: String? = null,
    val targetMenuId: String? = null,
    val payloadUrl: String? = null,
    val position: String? = null,
    val keyHint: String? = null,
    val value: String? = null,
    val destructive: Boolean = false,
    val children: List<UiNodeJson> = emptyList(),
)

/** Runtime form of a patch; injected nodes are already [MenuNode]s. */
data class UiMenuPatch(
    val sort: List<String>? = null,
    val filter: Regex? = null,
    val dropUnlisted: Boolean = false,
    val inject: List<MenuNode> = emptyList(),
)

data class UiExtension(
    val name: String,
    val patches: Map<String, UiMenuPatch>,
    val issues: List<String>,
)

fun interface UiExtensionSource {
    fun patchesFor(menuId: String): List<UiMenuPatch>
}

/**
 * Parses one extension file. Never throws: a broken extension is reported in
 * [UiExtension.issues] and the app keeps working with the built-in menus.
 */
class UiExtensionLoader(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    },
) {

    fun parse(sourceName: String, text: String): UiExtension {
        val file = try {
            json.decodeFromString(UiExtensionFile.serializer(), text)
        } catch (error: kotlinx.serialization.SerializationException) {
            return UiExtension(sourceName, emptyMap(), listOf("$sourceName: ${error.message}"))
        }
        val issues = mutableListOf<String>()
        val patches = LinkedHashMap<String, UiMenuPatch>()
        file.menus.forEach { (menuId, raw) ->
            val regex = raw.filter?.takeIf { it.isNotBlank() }?.let { pattern ->
                runCatching { Regex(pattern) }.getOrElse { error ->
                    issues += "$sourceName/$menuId: invalid filter '$pattern' (${error.message})"
                    null
                }
            }
            val injected = raw.inject.mapNotNull { node ->
                toNode(node, sourceName, menuId, issues)
            }
            patches[menuId] = UiMenuPatch(
                sort = raw.sort?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() },
                filter = regex,
                dropUnlisted = raw.dropUnlisted,
                inject = injected,
            )
        }
        return UiExtension(sourceName, patches, issues)
    }

    private fun toNode(
        raw: UiNodeJson,
        sourceName: String,
        menuId: String,
        issues: MutableList<String>,
    ): MenuNode? {
        if (raw.id.isBlank()) {
            issues += "$sourceName/$menuId: injected node without id"
            return null
        }
        if (raw.label.isNullOrBlank() && raw.labelKey.isNullOrBlank()) {
            issues += "$sourceName/$menuId: node '${raw.id}' needs label or labelKey"
            return null
        }
        val children = raw.children.mapNotNull { toNode(it, sourceName, menuId, issues) }
        return MenuNode(
            id = raw.id,
            labelKey = raw.labelKey,
            label = raw.label.orEmpty(),
            icon = raw.icon,
            iconKey = raw.iconKey,
            detail = raw.detail,
            value = raw.value,
            kind = if (children.isNotEmpty()) MenuNodeKind.SUBMENU else MenuNodeKind.ACTION,
            handler = raw.handler,
            targetMenuId = raw.targetMenuId,
            payload = raw.payloadUrl?.let { MenuPayload.Url(it) } ?: MenuPayload.None,
            children = children,
            position = raw.position,
            keyHint = raw.keyHint,
            tone = if (raw.destructive) MenuTone.DESTRUCTIVE else MenuTone.NORMAL,
            source = MenuSource.EXTENSION,
        )
    }
}

/**
 * Loads `assets/extensions/ui/<name>.ui.json` (bundled examples and anything an APK
 * integrator embeds). SAF-provided folders are layered in F7 through a composite
 * source with the same interface.
 */
class AssetUiExtensionSource(private val appContext: Context) : UiExtensionSource {

    private val loader = UiExtensionLoader()
    private val loaded: List<UiExtension> by lazy { loadAll() }

    override fun patchesFor(menuId: String): List<UiMenuPatch> =
        loaded.mapNotNull { it.patches[menuId] }

    fun extensions(): List<UiExtension> = loaded

    fun issues(): List<String> = loaded.flatMap { it.issues }

    private fun loadAll(): List<UiExtension> {
        val names = try {
            appContext.assets.list(DIR)?.toList().orEmpty()
        } catch (_: java.io.IOException) {
            emptyList()
        }
        return names
            .filter { it.endsWith(SUFFIX) }
            .sorted()
            .mapNotNull { name ->
                try {
                    appContext.assets.open("$DIR/$name").bufferedReader().use { reader ->
                        loader.parse(name, reader.readText())
                    }
                } catch (_: java.io.IOException) {
                    UiExtension(name, emptyMap(), listOf("$name: could not be read"))
                }
            }
    }

    companion object {
        private const val DIR = "extensions/ui"
        private const val SUFFIX = ".ui.json"
    }
}
