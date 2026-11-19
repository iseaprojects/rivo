package io.github.iseaprojects.rivo.ui.player

import android.Manifest
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.iseaprojects.rivo.RivoApp
import io.github.iseaprojects.rivo.core.config.ConfigKeys
import io.github.iseaprojects.rivo.di.AppGraph
import io.github.iseaprojects.rivo.player.PlayerEvents
import io.github.iseaprojects.rivo.player.PlayerExtras
import io.github.iseaprojects.rivo.player.PlayerExtrasCodec
import io.github.iseaprojects.rivo.player.PlayerIntents
import io.github.iseaprojects.rivo.player.PlaybackService
import io.github.iseaprojects.rivo.ui.theme.RivoTheme

/**
 * The in-app player window.
 *
 * The window is only a controller: the `ExoPlayer` lives in [PlaybackService], so rotating,
 * entering PiP or closing this activity never interrupts the stream. That is also why the
 * activity does not carry a request: it renders whatever the session currently plays, which
 * makes the notification, Android Auto and the app agree by construction.
 */
class PlayerActivity : ComponentActivity() {

    private lateinit var graph: AppGraph
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val connection = mutableStateOf<MediaController?>(null)
    private val inPip = mutableStateOf(false)
    private val pendingError = mutableStateOf<String?>(null)
    private var receiverRegistered = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val errorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra(PlayerEvents.EXTRA_MESSAGE) ?: return
            pendingError.value = message
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        graph = (application as RivoApp).graph
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersive(true)
        requestNotificationPermission()

        setContent {
            val theme by graph.themes.current.collectAsStateWithLifecycle()
            val strings by graph.strings.strings.collectAsStateWithLifecycle()
            RivoTheme(theme = theme) {
                val controller = connection.value
                if (controller == null) {
                    PlayerBootScreen(strings)
                } else {
                    PlayerScreen(
                        graph = graph,
                        strings = strings,
                        controller = controller,
                        inPip = inPip.value,
                        pendingError = pendingError.value,
                        onErrorShown = { pendingError.value = null },
                        onClose = { closePlayer() },
                        onEnterPip = { enterPip() },
                        onImmersive = { enabled -> applyImmersive(enabled) },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        connect()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                errorReceiver,
                IntentFilter(PlayerEvents.ACTION_ERROR),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
    }

    override fun onStop() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(errorReceiver) }
            receiverRegistered = false
        }
        // Take the surface away before the controller is released. The decoder renders into
        // the `TextureView`; if the view is torn down while the codec still points at it, the
        // codec throws (`MediaCodecVideoRenderer error`) and takes the whole playback with it
        // — which would make closing the player kill the audio that `CONFIG_DISOWN_PLAYER`
        // promises to keep. Detaching first leaves the session playing with no video output;
        // reconnecting attaches a fresh surface and the picture comes back.
        connection.value?.let { controller -> runCatching { controller.clearVideoSurface() } }
        connection.value = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    override fun onUserLeaveHint() {
        // Home or a notification tap: shrink into PiP instead of cutting the picture,
        // which is what every video app does and what F5 promised.
        if (!inPip.value && isVideoPlaying()) enterPip()
        super.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        inPip.value = isInPictureInPictureMode
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    }

    private fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(
            this,
            ComponentName(this, PlaybackService::class.java),
        )
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                val controller = runCatching { future.get() }.getOrNull()
                if (controller != null) connection.value = controller
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun isVideoPlaying(): Boolean {
        val controller = connection.value ?: return false
        if (!controller.isPlaying) return false
        val metadata = controller.currentMediaItem?.mediaMetadata
        val extras = PlayerExtrasCodec.decode(
            metadata?.extras?.getString(PlayerExtras.KEY),
        )
        return !extras.isAudioOnly
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val controller = connection.value
        val size = controller?.videoSize
        val width = size?.width?.takeIf { it > 0 } ?: 16
        val height = size?.height?.takeIf { it > 0 } ?: 9
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(width, height))
            .build()
        val entered = runCatching {
            setPictureInPictureParams(params)
            enterPictureInPictureMode(params)
        }.getOrDefault(false)
        if (!entered) {
            // Devices that disallow PiP (or a system that just refused) must not leave a
            // black window: keep playing in the full activity instead.
            inPip.value = false
        }
    }

    private fun applyImmersive(enabled: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (enabled) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun closePlayer() {
        finish()
        val disown = graph.runtime.snapshot.value.bool(ConfigKeys.DISOWN_PLAYER, true)
        if (!disown) {
            runCatching { startService(PlayerIntents.stopIntent(this)) }
        }
    }
}
