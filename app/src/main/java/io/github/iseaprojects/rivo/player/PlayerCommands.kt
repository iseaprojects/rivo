package io.github.iseaprojects.rivo.player

import android.os.Bundle
import androidx.media3.session.SessionCommand

/**
 * The two control-plane messages the activity sends to the playback service.
 *
 * Everything else (play, pause, seek, next, speed, track selection) is a normal Media3
 * player command and works from the notification and Bluetooth too. These two exist
 * because they need the service's extraction results: starting a queue, and rebuilding
 * the current item for a different quality or audio track.
 */
object PlayerCommands {

    const val SET_QUEUE = "io.github.iseaprojects.rivo.player.SET_QUEUE"
    const val SET_STREAM = "io.github.iseaprojects.rivo.player.SET_STREAM"
    const val STOP = "io.github.iseaprojects.rivo.player.STOP"

    const val EXTRA_REQUEST = "request"
    const val EXTRA_HEIGHT = "height"
    const val EXTRA_AUDIO_TRACK = "audio_track"

    val setQueue: SessionCommand = SessionCommand(SET_QUEUE, Bundle.EMPTY)
    val setStream: SessionCommand = SessionCommand(SET_STREAM, Bundle.EMPTY)
    val stop: SessionCommand = SessionCommand(STOP, Bundle.EMPTY)

    val all: List<SessionCommand> = listOf(setQueue, setStream, stop)
}

/** Broadcasts the service sends to the visible player when extraction cannot recover. */
object PlayerEvents {
    const val ACTION_ERROR = "io.github.iseaprojects.rivo.player.ERROR"
    const val EXTRA_MESSAGE = "message"
}
