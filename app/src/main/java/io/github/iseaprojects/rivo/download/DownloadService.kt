package io.github.iseaprojects.rivo.download

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.github.iseaprojects.rivo.R
import io.github.iseaprojects.rivo.RivoApp
import io.github.iseaprojects.rivo.core.config.ConfigKeys
import io.github.iseaprojects.rivo.data.download.DownloadEngine
import io.github.iseaprojects.rivo.data.download.DownloadKind
import io.github.iseaprojects.rivo.data.download.DownloadRepository
import io.github.iseaprojects.rivo.data.download.DownloadState
import io.github.iseaprojects.rivo.data.download.DownloadTask
import io.github.iseaprojects.rivo.di.AppGraph
import io.github.iseaprojects.rivo.platform.NotificationChannels
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs the download queue in the foreground.
 *
 * One service, N workers: `CONFIG_DOWNLOAD_CONCURRENCY` decides how many tasks run at
 * once, and the notification always describes the aggregate (count, current title,
 * percent) instead of one notification per file. The queue itself is persistent, so the
 * service is free to die; the repository restarts it and the store re-queues whatever was
 * interrupted.
 *
 * `CONFIG_DOWNLOAD_WIFI_ONLY` is enforced here rather than with a WorkManager constraint
 * because the work is already running: the loop parks, the notification says so, and it
 * resumes the moment a Wi-Fi or Ethernet network is the active one (D40).
 */
class DownloadService : android.app.Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = ConcurrentHashMap<String, Job>()

    /**
     * Task ids a worker has been launched for. The queue row is flipped to `RUNNING`
     * inside the coroutine, so without this the scheduling loop could pick the same task
     * again on its next pass before that write lands.
     */
    private val claimed = ConcurrentHashMap.newKeySet<String>()

    private lateinit var graph: AppGraph
    private lateinit var repository: DownloadRepository
    private lateinit var engine: DownloadEngine

    private var loop: Job? = null
    private var foreground = false
    private var lastError: String? = null

    override fun onCreate() {
        super.onCreate()
        graph = (application as RivoApp).graph
        repository = graph.downloads
        engine = graph.downloadEngine
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL_ONE -> intent.getStringExtra(EXTRA_ID)?.let { cancelNow(it, pause = false) }
            ACTION_PAUSE_ONE -> intent.getStringExtra(EXTRA_ID)?.let { cancelNow(it, pause = true) }
            ACTION_CANCEL_ALL -> scope.launch {
                graph.downloads.snapshot.value.tasks
                    .filter { it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING }
                    .forEach { graph.downloads.cancel(it.id) }
            }
        }
        ensureForeground()
        startLoop()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onDestroy() {
        loop?.cancel()
        jobs.values.forEach { it.cancel() }
        scope.cancel()
        super.onDestroy()
    }

    private fun startLoop() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            repository.store.load()
            while (isActive) {
                if (!networkAllowed()) {
                    publish(waitingForWifi = true)
                    delay(WIFI_POLL_MS)
                    continue
                }
                val limit = graph.runtime.snapshot.value
                    .int(ConfigKeys.DOWNLOAD_CONCURRENCY, 2)
                    .coerceIn(1, 4)
                if (claimed.size >= limit) {
                    delay(TICK_MS)
                    continue
                }
                val next = repository.store.tasks.value.firstOrNull {
                    it.state == DownloadState.QUEUED && it.id !in claimed
                }
                if (next == null) {
                    if (jobs.isEmpty() && claimed.isEmpty()) {
                        finish()
                        return@launch
                    }
                    delay(TICK_MS)
                    continue
                }
                claimed += next.id
                startTask(next)
            }
        }
    }

    private fun startTask(task: DownloadTask) {
        lastError = null
        val job = scope.launch {
            // The row may have been paused between the loop's claim and this launch; the
            // state flip below is the only place that decides a task actually runs.
            var started = false
            repository.store.update(task.id) { current ->
                if (current.state == DownloadState.QUEUED) {
                    started = true
                    current.copy(state = DownloadState.RUNNING, error = null)
                } else {
                    current
                }
            }
            if (!started) return@launch
            publish()
            val finished = engine.run(task) { fraction ->
                repository.store.setProgress(task.id, fraction)
                publish()
            }
            // Once the bytes are written, the bookkeeping must land: a pause arriving in
            // the last moments loses to `DONE`, which is the truth on disk.
            withContext(NonCancellable) {
                repository.store.clearProgress(task.id)
                val state = repository.store.tasks.value.firstOrNull { it.id == task.id }?.state
                if (state != DownloadState.CANCELLED) {
                    repository.store.update(task.id) { finished }
                    if (finished.state == DownloadState.DONE) {
                        repository.archive.add(finished)
                        repository.refresh()
                    } else if (finished.state == DownloadState.FAILED) {
                        lastError = finished.error
                    }
                }
                publish()
            }
        }
        jobs[task.id] = job
        job.invokeOnCompletion {
            jobs.remove(task.id)
            claimed -= task.id
            if (repository.store.tasks.value.none { it.state == DownloadState.QUEUED }) {
                scope.launch { finishIfIdle() }
            }
        }
    }

    private suspend fun finishIfIdle() {
        if (jobs.isNotEmpty() || claimed.isNotEmpty()) return
        if (repository.store.tasks.value.any { it.state == DownloadState.QUEUED }) return
        finish()
    }

    /**
     * Stops one worker. [pause] keeps the part files so Resume continues the transfer;
     * cancel discards them, because "cancel" means the bytes are not wanted any more.
     */
    private fun cancelNow(id: String, pause: Boolean) {
        jobs[id]?.cancel()
        jobs.remove(id)
        claimed -= id
        scope.launch {
            repository.store.update(id) { current ->
                // A transfer that already reached DONE owns its file; neither pause nor
                // cancel may relabel finished work.
                if (current.state == DownloadState.DONE) {
                    current
                } else {
                    current.copy(
                        state = if (pause) DownloadState.PAUSED else DownloadState.CANCELLED,
                        error = null,
                    )
                }
            }
            repository.store.clearProgress(id)
            if (!pause) {
                withContext(Dispatchers.IO) {
                    DownloadEngine.workDir(this@DownloadService, id).deleteRecursively()
                }
            }
            publish()
        }
    }

    private fun finish() {
        postSummary()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    private fun ensureForeground() {
        if (foreground) return
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(waitingForWifi = false),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
        foreground = true
    }

    /** Progress and queue state, coalesced: the loop can call this dozens of times a second. */
    private var lastPublishedAt = 0L

    private fun publish(waitingForWifi: Boolean = false) {
        if (!foreground) return
        val now = System.currentTimeMillis()
        if (now - lastPublishedAt < PUBLISH_MS) return
        lastPublishedAt = now
        val notification = buildNotification(waitingForWifi)
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(waitingForWifi: Boolean): Notification {
        val snapshot = repository.snapshot.value
        val tasks = snapshot.tasks
        val running = tasks.filter { it.state == DownloadState.RUNNING }
        val queued = tasks.count { it.state == DownloadState.QUEUED }
        val progress = repository.progress.value
        val current = running.firstOrNull()
        val percent = current?.let { progress[it.id] }?.let { (it * 100).toInt() }
            ?: if (waitingForWifi) 0 else running.mapNotNull { progress[it.id] }.average().takeIf { !it.isNaN() }?.let { (it * 100).toInt() }
            ?: 0

        val title = current?.title?.takeIf { it.isNotBlank() }
            ?: getString(R.string.download_notification_title)
        val text = when {
            waitingForWifi -> getString(R.string.download_notification_waiting)
            running.isNotEmpty() && queued > 0 -> getString(
                R.string.download_notification_queue,
                percent,
                running.size,
                running.size + queued,
            )

            running.isNotEmpty() -> getString(R.string.download_notification_progress, percent)
            else -> getString(R.string.download_notification_preparing)
        }

        return NotificationCompat.Builder(this, NotificationChannels.DOWNLOADS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, percent.coerceIn(0, 100), percent <= 0)
            .setContentIntent(openDownloads())
            .addAction(
                0,
                getString(R.string.download_notification_cancel),
                serviceIntent(ACTION_CANCEL_ALL),
            )
            .build()
    }

    /** A one-shot notification after the queue drains, so a failure is not silent. */
    private fun postSummary() {
        val failed = repository.snapshot.value.tasks.filter { it.state == DownloadState.FAILED }
        val error = lastError
        if (error == null && failed.isEmpty()) return
        val text = error ?: failed.first().error ?: getString(R.string.download_notification_failed)
        val notification = NotificationCompat.Builder(this, NotificationChannels.DOWNLOADS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(getString(R.string.download_notification_failed))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openDownloads())
            .build()
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(SUMMARY_ID, notification)
        }
    }

    private fun openDownloads(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(Intent.ACTION_VIEW, "rivo://downloads".toUri())
            .setClassName(packageName, "io.github.iseaprojects.rivo.ui.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun serviceIntent(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        Intent(this, DownloadService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun networkAllowed(): Boolean {
        if (!graph.runtime.snapshot.value.bool(ConfigKeys.DOWNLOAD_WIFI_ONLY, false)) return true
        val manager = getSystemService(ConnectivityManager::class.java) ?: return true
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    companion object {
        const val NOTIFICATION_ID = 1206
        const val SUMMARY_ID = 1207

        private const val ACTION_START = "io.github.iseaprojects.rivo.download.START"
        private const val ACTION_CANCEL_ALL = "io.github.iseaprojects.rivo.download.CANCEL_ALL"
        private const val ACTION_CANCEL_ONE = "io.github.iseaprojects.rivo.download.CANCEL_ONE"
        private const val ACTION_PAUSE_ONE = "io.github.iseaprojects.rivo.download.PAUSE_ONE"
        private const val EXTRA_ID = "id"

        private const val TICK_MS = 400L
        private const val WIFI_POLL_MS = 10_000L
        private const val PUBLISH_MS = 500L

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, DownloadService::class.java).setAction(ACTION_START),
                )
            }
        }

        fun cancel(context: Context, id: String) = send(context, ACTION_CANCEL_ONE, id)

        /** Stops one running transfer and keeps its bytes for Resume. */
        fun pause(context: Context, id: String) = send(context, ACTION_PAUSE_ONE, id)

        fun cancelAll(context: Context) = send(context, ACTION_CANCEL_ALL, null)

        private fun send(context: Context, action: String, id: String?) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(action)
                .apply { if (id != null) putExtra(EXTRA_ID, id) }
            runCatching { context.startService(intent) }
        }
    }
}


