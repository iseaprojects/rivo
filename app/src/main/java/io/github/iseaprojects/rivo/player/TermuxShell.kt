package io.github.iseaprojects.rivo.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent

/**
 * Hands a playback session to Termux through its public `RUN_COMMAND` API.
 *
 * yt-x's shell action gives the user variables (`YT_X_URL`, `YT_X_TITLE`, the resolved
 * stream) inside a terminal. On Android the equivalent is a login shell started with
 * those variables exported, so `mpv "$YT_X_STREAM"` or a yt-dlp invocation works without
 * retyping the URL. Termux must have `allow-external-apps=true`; without it the intent is
 * refused and the caller reports [TERMUX_UNAVAILABLE].
 */
object TermuxShell {

    const val TERMUX_UNAVAILABLE = "TXT_ANDROID_SHELL_UNAVAILABLE"

    private const val TERMUX_PACKAGE = "com.termux"
    private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    private const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"
    private const val HOME = "/data/data/com.termux/files/home"

    fun open(
        context: Context,
        url: String,
        title: String,
        videoId: String,
        streamUrl: String?,
        options: String? = null,
    ): LaunchOutcome {
        val script = buildString {
            append("export YT_X_URL=").append(quote(url)).append("; ")
            append("export YT_X_TITLE=").append(quote(title)).append("; ")
            append("export YT_X_ID=").append(quote(videoId)).append("; ")
            if (!streamUrl.isNullOrBlank()) {
                append("export YT_X_STREAM=").append(quote(streamUrl)).append("; ")
            }
            if (!options.isNullOrBlank()) {
                append("export YT_X_YTDLP_OPTS=").append(quote(options)).append("; ")
            }
            append("cd ").append(quote(HOME)).append("; ")
            append("exec bash")
        }

        val intent = Intent(ACTION_RUN_COMMAND)
            .setPackage(TERMUX_PACKAGE)
            .putExtra(EXTRA_PATH, BASH)
            .putExtra(EXTRA_ARGUMENTS, arrayOf("-lc", script))
            .putExtra(EXTRA_WORKDIR, HOME)
            .putExtra(EXTRA_BACKGROUND, false)
            .putExtra(EXTRA_SESSION_ACTION, "1")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(intent)
            LaunchOutcome.Started
        } catch (_: ActivityNotFoundException) {
            LaunchOutcome.Failed(TERMUX_UNAVAILABLE)
        } catch (_: SecurityException) {
            LaunchOutcome.Failed(TERMUX_UNAVAILABLE)
        }
    }

    /** POSIX single-quote escaping: `it's` becomes `'it'\''s'`. */
    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
