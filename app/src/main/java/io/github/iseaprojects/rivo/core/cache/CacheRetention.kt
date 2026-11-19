package io.github.iseaprojects.rivo.core.cache

/**
 * `CONFIG_CACHE_RETENTION_DAYS` policy.
 *
 * Pure so the boundary cases (0 = keep forever, a negative value, a file exactly at the
 * cutoff) are unit-tested instead of discovered on a device with a full cache partition.
 */
object CacheRetention {

    const val DEFAULT_DAYS = 3

    /** Negative values disable cleanup; 0 is treated as "keep everything" as well. */
    fun normaliseDays(configured: Int): Int = configured.coerceIn(MIN_DAYS, MAX_DAYS)

    /** Epoch millis before which a cache entry is considered stale. */
    fun cutoff(now: Long, days: Int): Long {
        val safe = normaliseDays(days)
        if (safe <= 0) return Long.MIN_VALUE
        return now - safe.toLong() * DAY_MS
    }

    fun isExpired(lastModified: Long, now: Long, days: Int): Boolean {
        if (normaliseDays(days) <= 0) return false
        return lastModified < cutoff(now, days)
    }

    const val MIN_DAYS = 0
    const val MAX_DAYS = 365
    const val DAY_MS: Long = 24 * 60 * 60 * 1000L
}
