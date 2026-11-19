package io.github.iseaprojects.rivo.core.launch

import io.github.iseaprojects.rivo.core.config.ConfigKeys
import io.github.iseaprojects.rivo.core.config.ConfigParser
import java.net.URLDecoder

/**
 * The Android equivalent of yt-x's CLI flags and `YT_X_*` environment variables.
 *
 * Every path into the app (launcher, `am start`, Tasker, a YouTube link, a share
 * sheet) is normalized to this one structure before any UI or extractor sees it.
 */
data class LaunchSpec(
    val deepLink: DeepLink? = null,
    val externalUrl: String? = null,
    val sharedText: String? = null,
    val action: LaunchAction = LaunchAction.NONE,
    val input: String? = null,
    val exit: Boolean = false,
    val playlistSkip: Boolean = false,
    val mediaExit: Boolean = false,
    val cmdExit: Boolean = false,
    val overrides: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = deepLink == null &&
            externalUrl == null &&
            sharedText == null &&
            action == LaunchAction.NONE &&
            input == null &&
            overrides.isEmpty()
}

enum class LaunchAction {
    NONE,
    PLAY,
    LISTEN,
    DOWNLOAD,
    DOWNLOAD_AUDIO,
    SAVE,
    PLAY_ALL,
    LISTEN_ALL,
    DOWNLOAD_ALL,
    SAVE_PLAYLIST,
    ;

    companion object {
        fun fromToken(token: String?): LaunchAction {
            if (token.isNullOrBlank()) return NONE
            return when (token.trim().lowercase().replace('_', '-')) {
                "play", "watch" -> PLAY
                "listen", "audio" -> LISTEN
                "download" -> DOWNLOAD
                "download-audio", "downloadaudio" -> DOWNLOAD_AUDIO
                "save" -> SAVE
                "play-all", "playall" -> PLAY_ALL
                "listen-all", "listenall" -> LISTEN_ALL
                "download-all", "downloadall" -> DOWNLOAD_ALL
                "save-playlist", "saveplaylist" -> SAVE_PLAYLIST
                else -> NONE
            }
        }
    }
}

/** Parsed `rivo://` URI; pure so it can be unit-tested without Android. */
data class DeepLink(
    val host: String,
    val path: String,
    val params: Map<String, String>,
) {
    fun param(vararg names: String): String? {
        names.forEach { name -> params[name]?.takeIf { it.isNotBlank() }?.let { return it } }
        return null
    }

    companion object {
        fun parse(raw: String?): DeepLink? {
            if (raw.isNullOrBlank()) return null
            val schemeEnd = raw.indexOf("://")
            if (schemeEnd <= 0) return null
            if (!raw.substring(0, schemeEnd).equals("rivo", ignoreCase = true)) return null
            val rest = raw.substring(schemeEnd + 3).substringBefore('#')
            val queryIndex = rest.indexOf('?')
            val pathPart = if (queryIndex >= 0) rest.substring(0, queryIndex) else rest
            val queryPart = if (queryIndex >= 0) rest.substring(queryIndex + 1) else ""
            val segments = pathPart.split('/').filter { it.isNotBlank() }
            val host = segments.firstOrNull()?.lowercase() ?: return null
            val path = segments.drop(1).joinToString("/")
            val params = LinkedHashMap<String, String>()
            queryPart.split('&').forEach { pair ->
                if (pair.isBlank()) return@forEach
                val separator = pair.indexOf('=')
                val key = if (separator >= 0) pair.substring(0, separator) else pair
                val value = if (separator >= 0) pair.substring(separator + 1) else ""
                params[decode(key)] = decode(value)
            }
            return DeepLink(host = host, path = path, params = params)
        }

        private fun decode(value: String): String = try {
            URLDecoder.decode(value, "UTF-8")
        } catch (_: IllegalArgumentException) {
            value
        }
    }
}

object LaunchSpecParser {

    private const val ACTION_SEND = "android.intent.action.SEND"
    private const val EXTRA_TEXT = "android.intent.extra.TEXT"

    private val flagActions: List<Pair<String, LaunchAction>> = listOf(
        "RIVO_PLAY" to LaunchAction.PLAY,
        "RIVO_WATCH" to LaunchAction.PLAY,
        "RIVO_LISTEN" to LaunchAction.LISTEN,
        "RIVO_DOWNLOAD_AUDIO" to LaunchAction.DOWNLOAD_AUDIO,
        "RIVO_DOWNLOAD_ALL" to LaunchAction.DOWNLOAD_ALL,
        "RIVO_DOWNLOAD" to LaunchAction.DOWNLOAD,
        "RIVO_SAVE_PLAYLIST" to LaunchAction.SAVE_PLAYLIST,
        "RIVO_SAVE" to LaunchAction.SAVE,
        "RIVO_PLAY_ALL" to LaunchAction.PLAY_ALL,
        "RIVO_LISTEN_ALL" to LaunchAction.LISTEN_ALL,
    )

    fun parse(
        dataUri: String?,
        intentAction: String?,
        mimeType: String?,
        extras: Map<String, Any?>,
    ): LaunchSpec {
        val deepLink = DeepLink.parse(dataUri)
        val externalUrl = dataUri?.takeIf {
            deepLink == null && (it.startsWith("http://", true) || it.startsWith("https://", true))
        }
        val sharedText = if (intentAction == ACTION_SEND && (mimeType == null || mimeType.startsWith("text/"))) {
            (extras[EXTRA_TEXT] as? CharSequence)?.toString()
                ?: (extras["android.intent.extra.SUBJECT"] as? CharSequence)?.toString()
        } else {
            null
        }

        val flagAction = flagActions.firstOrNull { (key, _) -> truthy(extras[key]) }?.second
        val declaredAction = LaunchAction.fromToken(extras["RIVO_ACTION"] as? String)
        val deepLinkAction = LaunchAction.fromToken(deepLink?.param("action"))
        val action = when {
            declaredAction != LaunchAction.NONE -> declaredAction
            deepLinkAction != LaunchAction.NONE -> deepLinkAction
            flagAction != null -> flagAction
            else -> LaunchAction.NONE
        }

        return LaunchSpec(
            deepLink = deepLink,
            externalUrl = externalUrl,
            sharedText = sharedText,
            action = action,
            input = (extras["RIVO_INPUT"] as? CharSequence)?.toString()?.takeIf { it.isNotBlank() },
            exit = truthy(extras["RIVO_EXIT"]) || truthy(deepLink?.param("exit")),
            playlistSkip = truthy(extras["RIVO_PLAYLIST_SKIP"]) || truthy(deepLink?.param("playlist-skip")),
            mediaExit = truthy(extras["RIVO_MEDIA_EXIT"]),
            cmdExit = truthy(extras["RIVO_CMD_EXIT"]),
            overrides = overridesOf(extras),
        )
    }

    /**
     * `--es RIVO_PER_PAGE 60` and `--es RIVO_CONFIG "CONFIG_PER_PAGE=60;CONFIG_THEME=catppuccin-mocha"`.
     * Unknown names are ignored rather than injected into the config.
     */
    fun overridesOf(extras: Map<String, Any?>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        extras.forEach { (key, value) ->
            if (!key.startsWith("RIVO_") || key == "RIVO_CONFIG" || value == null) return@forEach
            if (key in flagActions.map { it.first }) return@forEach
            if (key in setOf("RIVO_ACTION", "RIVO_INPUT", "RIVO_EXIT", "RIVO_PLAYLIST_SKIP", "RIVO_MEDIA_EXIT", "RIVO_CMD_EXIT")) {
                return@forEach
            }
            val configKey = ConfigKeys.keyForOverride(key) ?: return@forEach
            result[configKey] = value.toString()
        }
        val blob = extras["RIVO_CONFIG"] as? String
        if (!blob.isNullOrBlank()) {
            blob.split(';', '\n')
                .map { it.trim() }
                .filter { it.contains('=') }
                .forEach { assignment ->
                    val parsed = ConfigParser.parse(assignment).entries.firstOrNull() ?: return@forEach
                    if (parsed.key in ConfigKeys.known) result[parsed.key] = parsed.value
                }
        }
        return result
    }

    private fun truthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.trim().equals("true", true) ||
            value.trim() == "1" ||
            value.trim().equals("yes", true) ||
            value.trim().equals("on", true)
        else -> false
    }
}
