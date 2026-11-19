package io.github.iseaprojects.rivo.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import io.github.iseaprojects.rivo.R

/**
 * Channels are created once at process start; services (F5 playback, F6 downloads)
 * only reference the ids.
 */
object NotificationChannels {
    const val PLAYBACK = "playback"
    const val DOWNLOADS = "downloads"
    const val GENERAL = "general"

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val playback = NotificationChannel(
            PLAYBACK,
            context.getString(R.string.notification_channel_playback),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_playback_desc)
            setShowBadge(false)
            enableVibration(false)
        }

        val downloads = NotificationChannel(
            DOWNLOADS,
            context.getString(R.string.notification_channel_downloads),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_downloads_desc)
            setShowBadge(true)
        }

        val general = NotificationChannel(
            GENERAL,
            context.getString(R.string.notification_channel_general),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_general_desc)
            setShowBadge(false)
        }

        manager.createNotificationChannels(listOf(playback, downloads, general))
    }
}
