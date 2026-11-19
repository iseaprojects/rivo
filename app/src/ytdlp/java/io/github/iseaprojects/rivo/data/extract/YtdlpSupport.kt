package io.github.iseaprojects.rivo.data.extract

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import io.github.iseaprojects.rivo.core.config.ConfigSnapshot
import io.github.iseaprojects.rivo.data.download.DownloadFallback
import io.github.iseaprojects.rivo.data.extract.ytdlp.YtDlpBackend
import io.github.iseaprojects.rivo.data.extract.ytdlp.YtdlpDownloads
import io.github.iseaprojects.rivo.data.net.CookieProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `ytdlp` flavor implementation: exposes the youtubedl-android runtime.
 *
 * The APK ships yt-dlp 2025.11.12 inside `res/raw/ytdlp`. Because YouTube changes its
 * player far more often than the app can ship, [update] is the supported way to move to a
 * newer yt-dlp without a new build; the pinned bundled version is the offline fallback.
 */
object YtdlpSupport {

    const val BUNDLED: Boolean = true

    fun create(
        context: Context,
        cookies: CookieProvider,
        config: () -> ConfigSnapshot,
    ): VideoExtractor = YtDlpBackend(context, cookies, config)

    fun version(context: Context): String? =
        runCatching { YoutubeDL.getInstance().versionName(context) }.getOrNull()

    fun updateChannels(): List<String> = YtdlpChannels.all

    fun downloadFallback(
        context: Context,
        cookies: CookieProvider,
        config: () -> ConfigSnapshot,
    ): DownloadFallback = YtdlpDownloads(context, cookies, config)

    suspend fun update(context: Context, channel: String): UpdateOutcome {
        val target = YoutubeDL.UpdateChannel(YtdlpChannels.apiUrl(channel))
        return withContext(Dispatchers.IO) {
            runCatching {
                YoutubeDL.getInstance().init(context)
                when (YoutubeDL.getInstance().updateYoutubeDL(context, target)) {
                    YoutubeDL.UpdateStatus.DONE -> UpdateOutcome.UPDATED
                    YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> UpdateOutcome.ALREADY_CURRENT
                    else -> UpdateOutcome.FAILED
                }
            }.getOrDefault(UpdateOutcome.FAILED)
        }
    }
}
