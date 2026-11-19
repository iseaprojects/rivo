package io.github.iseaprojects.rivo.core.menu.extension

import io.github.iseaprojects.rivo.core.menu.MenuCommand
import io.github.iseaprojects.rivo.core.menu.MenuCommandSource
import io.github.iseaprojects.rivo.core.menu.MenuSite
import io.github.iseaprojects.rivo.core.menu.MenuSiteAction
import java.net.URLEncoder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `sites/<name>.sites.json` and `cmds/<name>.cmds.json` - the declarative replacements for
 * yt-x's shell site/command extensions.
 *
 * A site extension describes prompt-driven URL actions:
 *
 * ```json
 * {
 *   "schema": "rivo.sites/1",
 *   "sites": [
 *     {
 *       "id": "dailymotion",
 *       "name": "Dailymotion",
 *       "actions": [
 *         {
 *           "id": "search",
 *           "label": "Search Dailymotion",
 *           "prompt": "Search query",
 *           "url": "https://www.dailymotion.com/search/{query}/videos"
 *         }
 *       ]
 *     }
 *   ]
 * }
 * ```
 *
 * A command extension is the same data the in-app editor writes:
 *
 * ```json
 * { "schema": "rivo.cmds/1",
 *   "commands": [ { "name": "Dailymotion", "url": "https://www.dailymotion.com", "yt_dlp_opts": "--no-flat-playlist" } ] }
 * ```
 *
 * Neither can execute code. The URL is the whole capability, which is the sandbox: an
 * extension can name a destination for the extractor or the browser, not run a command.
 */
@Serializable
data class SitesExtensionFile(
    val schema: String = "",
    val sites: List<SiteJson> = emptyList(),
)

@Serializable
data class SiteJson(
    val id: String = "",
    val name: String = "",
    val icon: String? = null,
    val actions: List<SiteActionJson> = emptyList(),
)

@Serializable
data class SiteActionJson(
    val id: String = "",
    val label: String = "",
    val prompt: String? = null,
    val promptHint: String? = null,
    val url: String = "",
)

@Serializable
data class CommandsExtensionFile(
    val schema: String = "",
    val commands: List<CommandJson> = emptyList(),
)

@Serializable
data class CommandJson(
    val name: String = "",
    val url: String = "",
    @SerialName("yt_dlp_opts") val options: String = "",
)

data class ParsedSites(
    val sites: List<MenuSite> = emptyList(),
    val issues: List<String> = emptyList(),
)

data class ParsedCommands(
    val commands: List<MenuCommand> = emptyList(),
    val issues: List<String> = emptyList(),
)

class ExtensionFileParser(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    },
) {

    fun parseSites(sourceName: String, sourceKey: String, text: String): ParsedSites {
        val file = try {
            json.decodeFromString(SitesExtensionFile.serializer(), text)
        } catch (error: kotlinx.serialization.SerializationException) {
            return ParsedSites(issues = listOf("$sourceName: ${error.message}"))
        }
        val issues = mutableListOf<String>()
        val sites = file.sites.mapNotNull { raw ->
            val name = raw.name.trim()
            if (name.isEmpty()) {
                issues += "$sourceName: a site has no name"
                return@mapNotNull null
            }
            val siteId = identifier(raw.id, name, sourceKey)
            val actions = raw.actions.mapNotNull { action ->
                val label = action.label.trim()
                val url = action.url.trim()
                if (label.isEmpty() || url.isEmpty()) {
                    issues += "$sourceName/$name: an action needs a label and a url"
                    return@mapNotNull null
                }
                if (action.prompt.isNullOrBlank() && url.contains(QUERY_TOKEN)) {
                    issues += "$sourceName/$name: '$label' uses {query} without a prompt"
                }
                MenuSiteAction(
                    id = slug(action.id.ifBlank { label }),
                    label = label,
                    prompt = action.prompt?.takeIf { it.isNotBlank() },
                    promptHint = action.promptHint?.takeIf { it.isNotBlank() },
                    urlTemplate = url,
                )
            }
            if (actions.isEmpty()) {
                issues += "$sourceName/$name: no usable actions"
                return@mapNotNull null
            }
            MenuSite(
                id = siteId,
                name = name,
                icon = raw.icon?.takeIf { it.isNotBlank() },
                actions = actions,
            )
        }.distinctBy { it.id }
        return ParsedSites(sites = sites, issues = issues)
    }

    fun parseCommands(sourceName: String, sourceKey: String, text: String): ParsedCommands {
        val file = try {
            json.decodeFromString(CommandsExtensionFile.serializer(), text)
        } catch (error: kotlinx.serialization.SerializationException) {
            return ParsedCommands(issues = listOf("$sourceName: ${error.message}"))
        }
        val issues = mutableListOf<String>()
        val commands = file.commands.mapNotNull { raw ->
            val name = raw.name.trim()
            val url = raw.url.trim()
            if (name.isEmpty() || url.isEmpty()) {
                issues += "$sourceName: a command needs a name and a url"
                return@mapNotNull null
            }
            MenuCommand(
                id = "ext:$sourceKey:${slug(name)}",
                name = name,
                url = url,
                options = raw.options.trim(),
                source = MenuCommandSource.EXTENSION,
            )
        }.distinctBy { it.id }
        return ParsedCommands(commands = commands, issues = issues)
    }

    private fun identifier(declared: String, name: String, sourceKey: String): String {
        val clean = declared.trim()
        return if (clean.isEmpty()) "$sourceKey:${slug(name)}" else "$sourceKey:${slug(clean)}"
    }

    companion object {
        const val QUERY_TOKEN = "{query}"

        private val NON_SLUG = Regex("[^a-z0-9._-]+")

        fun slug(value: String): String =
            value.trim().lowercase().replace(NON_SLUG, "-").trim('-').ifEmpty { "item" }
    }
}

/**
 * Expands a site action URL. Only `{query}` is substituted, URL-encoded as a single path
 * or query component; a template without the token opens as-is. No other expansion is
 * performed, so an extension cannot smuggle a second destination into the URL.
 */
object SiteTemplate {

    fun expand(template: String, query: String?): String {
        if (!template.contains(ExtensionFileParser.QUERY_TOKEN)) return template
        val encoded = encode(query.orEmpty())
        return template.replace(ExtensionFileParser.QUERY_TOKEN, encoded)
    }

    fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
