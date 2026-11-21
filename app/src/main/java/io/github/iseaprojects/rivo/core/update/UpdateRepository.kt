package io.github.iseaprojects.rivo.core.update

import io.github.iseaprojects.rivo.BuildConfig
import io.github.iseaprojects.rivo.core.config.ConfigKeys
import io.github.iseaprojects.rivo.core.config.ConfigSnapshot
import io.github.iseaprojects.rivo.data.net.HttpClients
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** What the update checker currently knows. */
sealed interface UpdateState {

    data object Idle : UpdateState

    data object Checking : UpdateState

    data class Current(val version: String, val checkedAt: Long) : UpdateState

    data class Available(
        val release: ReleaseInfo,
        val currentVersion: String,
        val checkedAt: Long,
    ) : UpdateState

    data class Failed(val message: String, val checkedAt: Long) : UpdateState
}

/**
 * GitHub release checker.
 *
 * `UPDATE_MANIFEST` points at the Rivo repository. The check is deliberately
 * conservative: at most once every [MIN_INTERVAL_MS] unless the user presses "Check again",
 * disabled by `CONFIG_CHECK_FOR_UPDATES`, and it never installs anything. `UPSTREAM_REPO`
 * stays in the about screen as the credit for the yt-x port this project grew out of.
 *
 * The interval survives process death through [lastUpToDateCheck]/[rememberUpToDateCheck]:
 * a timestamp in the state object alone would be forgotten on every cold start, which is
 * exactly when the app used to hit the GitHub API.
 */
class UpdateRepository(
    private val config: () -> ConfigSnapshot,
    private val scope: CoroutineScope,
    private val currentVersion: String = BuildConfig.RIVO_VERSION,
    private val endpoint: String = BuildConfig.UPDATE_MANIFEST,
    private val client: OkHttpClient = HttpClients.shared,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Epoch millis of the last check that found nothing new, or 0. Persisted by the graph. */
    private val lastUpToDateCheck: () -> Long = { 0L },
    /** Stores the value above; called with 0 when a release is available or the check failed. */
    private val rememberUpToDateCheck: (Long) -> Unit = {},
) {

    private val mutex = Mutex()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    init {
        scope.launch { check(force = false) }
    }

    /**
     * Checks unless the last successful check is recent. Called on startup and when the
     * Update screen opens; a failure is not cached, so a later attempt can recover.
     */
    suspend fun check(force: Boolean) {
        if (!config().bool(ConfigKeys.CHECK_FOR_UPDATES, true)) {
            _state.value = UpdateState.Idle
            return
        }
        val previous = _state.value
        // A failed check is deliberately not remembered, so an offline start can retry.
        val lastSuccess = when (previous) {
            is UpdateState.Available -> previous.checkedAt
            is UpdateState.Current -> previous.checkedAt
            else -> lastUpToDateCheck()
        }
        if (!force && lastSuccess > 0 && clock() - lastSuccess < MIN_INTERVAL_MS) {
            // Coming from a previous process: report the remembered result instead of an
            // idle screen, and say when it was checked.
            if (previous is UpdateState.Idle) {
                _state.value = UpdateState.Current(currentVersion, lastSuccess)
            }
            return
        }

        mutex.withLock {
            _state.value = UpdateState.Checking
            val now = clock()
            val result = withContext(Dispatchers.IO) { fetch() }
            _state.value = when (result) {
                is ReleaseFeedResult.Failed -> {
                    rememberUpToDateCheck(0L)
                    UpdateState.Failed(result.message, now)
                }

                is ReleaseFeedResult.Ok -> {
                    val includePrerelease = config()
                        .string(ConfigKeys.UPDATE_CHANNEL, "stable")
                        .equals("prerelease", ignoreCase = true) ||
                        config().string(ConfigKeys.UPDATE_CHANNEL).equals("beta", ignoreCase = true)
                    val release = ReleaseSelection.newerThan(
                        releases = result.releases,
                        currentVersion = currentVersion,
                        includePrerelease = includePrerelease,
                    )
                    if (release == null) {
                        rememberUpToDateCheck(now)
                        UpdateState.Current(currentVersion, now)
                    } else {
                        // Do not remember: a pending update is worth one more lookup on the
                        // next start rather than a six-hour blind spot.
                        rememberUpToDateCheck(0L)
                        UpdateState.Available(release, currentVersion, now)
                    }
                }
            }
        }
    }

    fun ensureFresh() {
        scope.launch { check(force = false) }
    }

    private fun fetch(): ReleaseFeedResult {
        val url = endpoint.toHttpUrlOrNull()
            ?: return ReleaseFeedResult.Failed("the update endpoint is not a valid URL")
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Rivo-Android/${BuildConfig.VERSION_NAME}")
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use ReleaseFeedResult.Failed("GitHub answered HTTP ${response.code}")
                }
                val body = response.body.string()
                if (body.isBlank()) ReleaseFeedResult.Failed("GitHub returned an empty release list")
                else ReleaseFeed.parse(body)
            }
        } catch (error: Throwable) {
            ReleaseFeedResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    companion object {
        /** Six hours: often enough to notice a release, rare enough not to be a poller. */
        const val MIN_INTERVAL_MS: Long = 6 * 60 * 60 * 1000L
    }
}
