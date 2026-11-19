package io.github.iseaprojects.rivo.data.extract

import android.content.Context
import io.github.iseaprojects.rivo.core.config.ConfigSnapshot
import io.github.iseaprojects.rivo.data.download.DownloadFallback
import io.github.iseaprojects.rivo.data.download.NoDownloadFallback
import io.github.iseaprojects.rivo.data.net.CookieProvider

/**
 * `fast` flavor stub: NewPipeExtractor only.
 *
 * The `ytdlp` flavor replaces this object with a real implementation. Keeping the exact
 * same FQN and signature in both source sets means the extractor router is written once
 * and never has to know which flavor it was compiled into.
 */
object YtdlpSupport {

    const val BUNDLED: Boolean = false

    fun create(
        context: Context,
        cookies: CookieProvider,
        config: () -> ConfigSnapshot,
    ): VideoExtractor? = null

    fun version(context: Context): String? = null

    fun updateChannels(): List<String> = emptyList()

    suspend fun update(context: Context, channel: String): UpdateOutcome = UpdateOutcome.UNSUPPORTED

    /** No extractor means no fallback either: HLS/DASH downloads name the other build. */
    fun downloadFallback(
        context: Context,
        cookies: CookieProvider,
        config: () -> ConfigSnapshot,
    ): DownloadFallback = NoDownloadFallback
}
