package io.github.iseaprojects.rivo.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.iseaprojects.rivo.R
import io.github.iseaprojects.rivo.RivoApp
import io.github.iseaprojects.rivo.core.config.ConfigKeys
import io.github.iseaprojects.rivo.di.AppGraph
import io.github.iseaprojects.rivo.platform.NotificationChannels
import io.github.iseaprojects.rivo.ui.player.PlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The playback session.
 *
 * Everything a phone expects from a music/video app lives behind one Media3 session:
 * the notification, Bluetooth and headset buttons, Android Auto, Assistant, PiP and the
 * in-app controller all drive the same [ExoPlayer]. The activity is only a view; closing
 * it does not interrupt playback unless `CONFIG_DISOWN_PLAYER` is off, which is the
 * upstream `--disown-player` switch.
 *
 * Two things need extraction results and therefore travel as custom commands: a new queue
 * ([PlayerCommands.SET_QUEUE]) and a stream rebuild after the user picks another quality
 * or audio track ([PlayerCommands.SET_STREAM]). Resolving happens in [PlaybackCoordinator]
 * off the main thread so the session never blocks on the network.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var graph: AppGraph
    private lateinit var player: ExoPlayer
    private lateinit var coordinator: PlaybackCoordinator
    private var session: MediaSession? = null

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            coordinator.onMediaItemTransition(mediaItem)
            session?.setSessionActivity(playerActivityIntent())
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            coordinator.onPlaybackStateChanged(playbackState)
        }

        override fun onPlayerError(error: PlaybackException) {
            coordinator.onPlayerError(error)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Media3 replaces the provisional notification with the media notification on
            // the same id as soon as the player has a prepared item.
            if (isPlaying) triggerNotificationUpdate()
        }
    }

    override fun onCreate() {
        super.onCreate()
        graph = (application as RivoApp).graph

        player = ExoPlayer.Builder(this, StreamMediaSourceFactory(this))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                // Audio focus is what pauses playback for a call or another app; without
                // it two players talk over each other.
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .also { it.addListener(playerListener) }

        coordinator = PlaybackCoordinator(this, player, graph, scope)

        session = MediaSession.Builder(this, player)
            .setId(SESSION_ID)
            .setSessionActivity(playerActivityIntent())
            .setCallback(SessionCallback())
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(NotificationChannels.PLAYBACK)
                .setNotificationId(NOTIFICATION_ID)
                .build(),
        )
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            PlayerIntents.ACTION_PLAY -> {
                val request = PlaybackRequestCodec.decode(
                    intent.getStringExtra(PlayerIntents.EXTRA_REQUEST),
                )
                if (request == null) {
                    stopPlayback()
                } else {
                    startPreparingNotification(request)
                    coordinator.start(request)
                }
            }

            PlayerIntents.ACTION_STOP -> stopPlayback()
        }
        return result
    }

    /**
     * `startForegroundService` from the activity must reach `startForeground` within a few
     * seconds, but the first item needs a network round trip. This provisional
     * notification holds the slot Media3 will later replace (same id and channel), so the
     * system never kills the service while it is resolving.
     */
    private fun startPreparingNotification(request: PlaybackRequest) {
        val title = request.current?.title?.takeIf { it.isNotBlank() }
            ?: getString(R.string.app_name)
        val text = getString(
            if (request.mode == PlayMode.AUDIO) {
                R.string.playback_preparing_audio
            } else {
                R.string.playback_preparing_video
            },
        )
        val notification = NotificationCompat.Builder(this, NotificationChannels.PLAYBACK)
            .setSmallIcon(R.drawable.ic_stat_playback)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(playerActivityIntent())
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    /** Stops playback, drops the queue and leaves the process as it was before F5 ran. */
    fun stopPlayback() {
        coordinator.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val disown = graph.runtime.snapshot.value.bool(ConfigKeys.DISOWN_PLAYER, true)
        if (!disown) {
            stopPlayback()
            return
        }
        // Swiping the app away is not a stop button: audio keeps playing, which is what
        // "disown" means upstream. Media3 stops the service itself once the player idles.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        player.removeListener(playerListener)
        session?.release()
        session = null
        coordinator.stop()
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun playerActivityIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, PlayerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private inner class SessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .addSessionCommands(PlayerCommands.all)
                        .build(),
                )
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: androidx.media3.session.SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val code = when (customCommand.customAction) {
                PlayerCommands.SET_QUEUE -> {
                    val request = PlaybackRequestCodec.decode(
                        args.getString(PlayerCommands.EXTRA_REQUEST),
                    )
                    if (request == null) {
                        SessionResult.RESULT_ERROR_BAD_VALUE
                    } else {
                        coordinator.start(request)
                        SessionResult.RESULT_SUCCESS
                    }
                }

                PlayerCommands.SET_STREAM -> {
                    if (args.containsKey(PlayerCommands.EXTRA_HEIGHT)) {
                        val height = args.getInt(PlayerCommands.EXTRA_HEIGHT)
                        coordinator.setQuality(height.takeIf { it > 0 })
                    }
                    if (args.containsKey(PlayerCommands.EXTRA_AUDIO_TRACK)) {
                        coordinator.setAudio(args.getString(PlayerCommands.EXTRA_AUDIO_TRACK))
                    }
                    SessionResult.RESULT_SUCCESS
                }

                PlayerCommands.STOP -> {
                    stopPlayback()
                    SessionResult.RESULT_SUCCESS
                }

                else -> SessionResult.RESULT_ERROR_NOT_SUPPORTED
            }
            return Futures.immediateFuture(SessionResult(code))
        }
    }

    companion object {
        const val SESSION_ID = "io.github.iseaprojects.rivo.playback"
        const val NOTIFICATION_ID = 1107
    }
}
