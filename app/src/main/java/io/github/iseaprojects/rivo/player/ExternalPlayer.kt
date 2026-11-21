package io.github.iseaprojects.rivo.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.iseaprojects.rivo.core.config.ConfigKeys
import io.github.iseaprojects.rivo.core.config.ConfigSnapshot
import io.github.iseaprojects.rivo.core.model.MediaSummary
import io.github.iseaprojects.rivo.data.extract.ExtractorException
import io.github.iseaprojects.rivo.data.extract.VideoDetails
import io.github.iseaprojects.rivo.di.AppGraph
import kotlinx.coroutines.CancellationException

/**
 * `ACTION_VIEW` handoff to an external player.
 *
 * `CONFIG_PLAYER=external` and the "Send to External Player" action both land here. The
 * choice of URL is the part that matters: `CONFIG_EXTERNAL_FORMAT=muxed` avoids the
 * separate-DASH-audio trap, while `adaptive` prefers a manifest for players that can
 * adapt. Only the resolved media URL leaves the app; cookies and authorization headers
 * stay inside its own network stack, which is why handoff works for public media and
 * fails honestly for account-only media.
 */
object ExternalPlayer {

    suspend fun handOff(
        context: Context,
        graph: AppGraph,
        media: MediaSummary,
        mode: PlayMode,
        config: ConfigSnapshot,
    ): LaunchOutcome {
        val details = try {
            graph.extractors.video(media.url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ExtractorException) {
            return LaunchOutcome.Failed("TXT_PLAYER_NOT_FOUND", error.message)
        } catch (error: Throwable) {
            return LaunchOutcome.Failed("TXT_PLAYER_NOT_FOUND", error.message)
        }

        val configured = config.string(ConfigKeys.EXTERNAL_PLAYER, DEFAULT_PLAYER)
        val adaptive = config.string(ConfigKeys.EXTERNAL_FORMAT, "muxed")
            .equals("adaptive", ignoreCase = true)
        val url = mediaUrl(details, mode, adaptive)
            ?: return LaunchOutcome.Failed("TXT_PLAYER_NOT_FOUND", configured)

        val subtitles = StreamPlanner.subtitles(
            details,
            config.list(ConfigKeys.SUBTITLES_LANGS),
        ).map { it.url }

        val intent = intentFor(
            configured = configured,
            url = url,
            title = details.summary.title,
            subtitles = subtitles,
            startAtMs = details.startAtSeconds * 1000L,
            audioOnly = mode == PlayMode.AUDIO,
            mpvArgs = config.string(ConfigKeys.MPV_ARGS).takeIf { it.isNotBlank() },
        )

        return try {
            context.startActivity(intent)
            LaunchOutcome.Started
        } catch (_: ActivityNotFoundException) {
            LaunchOutcome.Failed("TXT_PLAYER_NOT_FOUND", configured)
        } catch (_: SecurityException) {
            LaunchOutcome.Failed("TXT_PLAYER_NOT_FOUND", configured)
        }
    }

    /** The stream an external app should open, honouring muxed/adaptive preferences. */
    fun mediaUrl(details: VideoDetails, mode: PlayMode, adaptive: Boolean): String? {
        if (mode == PlayMode.AUDIO) {
            return details.bestAudio()?.url
                ?: details.bestMuxed()?.url
                ?: details.dashManifestUrl
                ?: details.hlsManifestUrl
        }
        if (adaptive) {
            return details.dashManifestUrl
                ?: details.hlsManifestUrl
                ?: details.bestMuxed()?.url
                ?: details.bestVideoOnly()?.url
        }
        return details.bestMuxed()?.url
            ?: details.dashManifestUrl
            ?: details.hlsManifestUrl
            ?: details.bestVideoOnly()?.url
    }

    private fun intentFor(
        configured: String,
        url: String,
        title: String,
        subtitles: List<String>,
        startAtMs: Long,
        audioOnly: Boolean,
        mpvArgs: String?,
    ): Intent {
        val uri = Uri.parse(url)
        val mime = if (audioOnly) "audio/*" else "video/*"
        val packageName = packageFor(configured)

        if (packageName == null) {
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return Intent.createChooser(view, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        when (packageName) {
            MPV -> {
                if (startAtMs > 0) intent.putExtra("position", startAtMs.toInt())
                if (subtitles.isNotEmpty()) {
                    intent.putExtra("subs", subtitles.toTypedArray())
                }
                val args = buildList {
                    if (startAtMs > 0) add("--start=${startAtMs / 1000.0}")
                    mpvArgs?.split(' ')?.filter { it.isNotBlank() }?.forEach { add(it) }
                }
                if (args.isNotEmpty()) intent.putExtra("args", args.toTypedArray())
            }

            // VLC's documented hand-off extras. `position` is milliseconds and only counts
            // when `from_start` is false; without them "resume at" and the title were
            // dropped on hand-off. VLC has no extra for libVLC options and its
            // `subtitles_location` takes a local path, so `CONFIG_VLC_ARGS` and the
            // subtitle sidecars stay internal-only (see docs/PARITY.md).
            VLC -> {
                intent.putExtra("title", title)
                intent.putExtra("from_start", false)
                if (startAtMs > 0) intent.putExtra("position", startAtMs)
            }

            else -> Unit
        }
        return intent
    }

    private fun packageFor(configured: String): String? =
        when (configured.trim().lowercase().substringBefore(' ')) {
            "mpv", "mpv-android", "mpvandroid" -> MPV
            "vlc", "vlc-android" -> VLC
            "mx", "mx-player", "mxplayer", "mx-pro" -> MX
            else -> null
        }

    private const val MPV = "is.xyz.mpv"
    private const val VLC = "org.videolan.vlc"
    private const val MX = "com.mxtech.videoplayer.ad"
    private const val DEFAULT_PLAYER = "mpv-android"
}
