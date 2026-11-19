package io.github.iseaprojects.rivo.player

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.iseaprojects.rivo.ui.player.PlayerActivity

/**
 * Intent contract between the menu layer, the player activity and the playback service.
 *
 * A queue is always handed to the service, never to the activity. The activity is a
 * renderer for whatever the session is playing, which is why reopening it from the
 * notification lands on the current item instead of a stale request.
 */
object PlayerIntents {

    const val ACTION_PLAY = "io.github.iseaprojects.rivo.player.action.PLAY"
    const val ACTION_STOP = "io.github.iseaprojects.rivo.player.action.STOP"
    const val EXTRA_REQUEST = "io.github.iseaprojects.rivo.player.request"

    fun serviceIntent(context: Context, request: PlaybackRequest): Intent =
        Intent(context, PlaybackService::class.java)
            .setAction(ACTION_PLAY)
            .putExtra(EXTRA_REQUEST, PlaybackRequestCodec.encode(request))

    fun stopIntent(context: Context): Intent =
        Intent(context, PlaybackService::class.java).setAction(ACTION_STOP)

    fun activityIntent(context: Context): Intent =
        Intent(context, PlayerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /**
     * Starts the queue and opens the player.
     *
     * `startForegroundService` must be followed by [PlayerActivity] in the same foreground
     * turn: the service is allowed to begin because the caller is a visible activity, and
     * it posts its provisional notification immediately.
     */
    fun open(context: Context, request: PlaybackRequest) {
        ContextCompat.startForegroundService(context, serviceIntent(context, request))
        context.startActivity(activityIntent(context))
    }

    fun stop(context: Context) {
        context.startService(stopIntent(context))
    }
}
