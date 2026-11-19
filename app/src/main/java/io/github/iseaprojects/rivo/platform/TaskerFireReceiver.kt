package io.github.iseaprojects.rivo.platform

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.github.iseaprojects.rivo.R
import io.github.iseaprojects.rivo.ui.MainActivity

/**
 * Tasker entry point: `io.github.iseaprojects.rivo.action.TASKER_FIRE` with a `rivo_uri` extra (or a
 * `rivo://` data URI). The receiver forwards the link to [MainActivity] as a normal VIEW
 * intent, so a Tasker task has exactly the deep-link API surface and nothing more.
 *
 * Bringing an activity forward from a receiver is restricted since Android 10. The receiver
 * therefore starts the activity directly only when the platform allows it: always on
 * 26..28, and on 29+ only after the user granted "Display over other apps" (the single
 * entitlement a normal app can hold that exempts it from the background-start block).
 * Otherwise the request becomes a notification whose content intent opens the app on tap,
 * so the automation still delivers its result instead of being dropped.
 */
class TaskerFireReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.getStringExtra(EXTRA_URI)
            ?: intent.getStringExtra(EXTRA_URI_UPPER)
            ?: intent.dataString
        if (uri.isNullOrBlank()) return

        val launch = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(uri.toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        // Only the documented RIVO_* overrides are forwarded; the receiver is exported, so
        // anything else could otherwise become a property injector.
        intent.extras?.keySet()?.forEach { key ->
            if (!key.startsWith("RIVO_")) return@forEach
            launch.putExtra(key, intent.extras?.get(key)?.toString())
        }
        intent.getStringExtra("rivo_action")?.takeIf { it.isNotBlank() }?.let { action ->
            launch.putExtra("RIVO_ACTION", action)
        }

        when {
            // "Display over other apps" is the only entitlement a normal app can be granted
            // that exempts it from the background-activity-start block, so a Tasker launch
            // needs it. Without it the request is surfaced as a notification instead of
            // being silently dropped by the platform.
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                Settings.canDrawOverlays(context) -> {
                runCatching { context.startActivity(launch) }
                    .onFailure { notifyUser(context, uri, pendingIntent(context, launch)) }
            }

            else -> {
                notifyUser(context, uri, pendingIntent(context, launch))
            }
        }
    }

    private fun pendingIntent(
        context: Context,
        launch: Intent,
    ): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, REQUEST_CODE, launch, flags)
    }

    private fun notifyUser(context: Context, uri: String, pending: PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Tasker launch needs either notifications or the overlay permission")
            return
        }
        val notification = NotificationCompat.Builder(context, NotificationChannels.GENERAL)
            .setSmallIcon(R.drawable.ic_stat_search)
            .setContentTitle(context.getString(R.string.tasker_notification_title))
            .setContentText(context.getString(R.string.tasker_notification_text, uri))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val ACTION_FIRE = "io.github.iseaprojects.rivo.action.TASKER_FIRE"
        const val EXTRA_URI = "rivo_uri"
        const val EXTRA_URI_UPPER = "RIVO_URI"
        private const val REQUEST_CODE = 41
        private const val NOTIFICATION_ID = 41
        private const val TAG = "RivoTasker"
    }
}
