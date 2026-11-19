package io.github.iseaprojects.rivo.platform

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import androidx.core.net.toUri
import io.github.iseaprojects.rivo.R
import io.github.iseaprojects.rivo.RivoApp
import io.github.iseaprojects.rivo.core.model.MediaSummary
import io.github.iseaprojects.rivo.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Home-screen list of recently watched items.
 *
 * The widget reads the same Room table the Recent screen does. It uses `goAsync` because a
 * widget update has a short broadcast window and a Room query must not run on the main
 * thread; the provider finishes the broadcast as soon as the rows are set.
 *
 * Tapping a row opens the actions screen for that media, not playback: a widget tap should
 * never start audio in the user's pocket. The title opens the Recent screen.
 */
class RecentWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pending = goAsync()
        val appContext = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val recents = runCatching {
                    (appContext as RivoApp).graph.library.recentsOnce(MAX_ROWS)
                }.getOrDefault(emptyList())

                val views = buildViews(appContext, recents)
                appWidgetIds.forEach { id ->
                    runCatching { appWidgetManager.updateAppWidget(id, views) }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun buildViews(context: Context, recents: List<MediaSummary>): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_recent)
        views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_label))
        views.setOnClickPendingIntent(R.id.widget_title, open(context, "rivo://recent", 100))

        if (recents.isEmpty()) {
            views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
            ROW_IDS.forEach { views.setViewVisibility(it, View.GONE) }
            return views
        }

        views.setViewVisibility(R.id.widget_empty, View.GONE)
        ROW_IDS.forEachIndexed { index, rowId ->
            val media = recents.getOrNull(index)
            if (media == null) {
                views.setViewVisibility(rowId, View.GONE)
            } else {
                views.setViewVisibility(rowId, View.VISIBLE)
                views.setTextViewText(rowId, rowText(media))
                views.setOnClickPendingIntent(
                    rowId,
                    open(context, "rivo://media?url=${Uri.encode(media.url)}", index),
                )
            }
        }
        return views
    }

    private fun rowText(media: MediaSummary): String {
        val channel = media.channelName?.takeIf { it.isNotBlank() }
        return if (channel == null) media.title else "${media.title} · $channel"
    }

    private fun open(context: Context, uri: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(uri.toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val MAX_ROWS = 4
        val ROW_IDS = intArrayOf(
            R.id.widget_row_1,
            R.id.widget_row_2,
            R.id.widget_row_3,
            R.id.widget_row_4,
        )
    }
}
