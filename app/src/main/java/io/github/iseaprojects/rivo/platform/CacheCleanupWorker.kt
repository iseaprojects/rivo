package io.github.iseaprojects.rivo.platform

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.iseaprojects.rivo.core.cache.CacheRetention
import io.github.iseaprojects.rivo.core.config.ConfigKeys
import io.github.iseaprojects.rivo.core.config.ConfigStore
import java.util.concurrent.TimeUnit

/**
 * Daily `CONFIG_CACHE_RETENTION_DAYS` sweep.
 *
 * WorkManager (not a boot receiver) because the cleanup is idempotent and may run whenever
 * the device is idle; nothing user-visible depends on it finishing at a particular moment.
 * A retention of zero cancels the schedule instead of queuing a worker that does nothing.
 */
class CacheCleanupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val days = days(applicationContext)
        if (days <= 0) return Result.success()
        CacheCleaner.clean(applicationContext, days)
        return Result.success()
    }

    companion object {

        fun days(context: Context): Int {
            // Read the store directly: this is also reachable from a scheduled worker whose
            // process was started by WorkManager, where no graph exists yet.
            val snapshot = ConfigStore(context).snapshot()
            return CacheRetention.normaliseDays(
                snapshot.int(ConfigKeys.CACHE_RETENTION_DAYS, CacheRetention.DEFAULT_DAYS),
            )
        }

        /** One unique worker; changing the setting replaces the previous schedule. */
        fun schedule(context: Context, days: Int = days(context)) {
            val manager = WorkManager.getInstance(context)
            if (days <= 0) {
                manager.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<CacheCleanupWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
            manager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        const val WORK_NAME = "rivo-cache-retention"
    }
}
