package io.github.iseaprojects.rivo.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import io.github.iseaprojects.rivo.data.extract.DeliveryKind

/**
 * Turns a resolved [PlannedStream] into the Media3 source it describes.
 *
 * The service resolves stream URLs off the main thread and stores the plan in the
 * `MediaItem`'s tag, so building the timeline never blocks on the extractor. This factory
 * is where the plan becomes one of four shapes:
 *
 *  - progressive muxed: one `ProgressiveMediaSource`
 *  - progressive video + audio: `MergingMediaSource`, the fix for YouTube's separated
 *    DASH audio that used to play silently in mpv-based setups
 *  - HLS: `HlsMediaSource`, including live windows
 *  - DASH: `DashMediaSource` with its own adaptive selection
 */
@UnstableApi
class StreamMediaSourceFactory(context: Context) : MediaSource.Factory {

    private val fallback = DefaultMediaSourceFactory(context)

    override fun setDrmSessionManagerProvider(
        provider: DrmSessionManagerProvider,
    ): MediaSource.Factory {
        fallback.setDrmSessionManagerProvider(provider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(
        policy: LoadErrorHandlingPolicy,
    ): MediaSource.Factory {
        fallback.setLoadErrorHandlingPolicy(policy)
        return this
    }

    override fun getSupportedTypes(): IntArray = fallback.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val plan = mediaItem.localConfiguration?.tag as? PlannedStream
            ?: return fallback.createMediaSource(mediaItem)

        val dataSource = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setDefaultRequestProperties(plan.headers)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)

        fun itemFor(url: String, mime: String?): MediaItem = mediaItem.buildUpon()
            .setUri(url)
            .setMimeType(mime)
            // The tag describes the whole pair; a child source must not see it again.
            .setTag(null)
            .build()

        val source = when (plan.delivery) {
            DeliveryKind.HLS -> HlsMediaSource.Factory(dataSource)
                .createMediaSource(itemFor(plan.manifestUrl ?: plan.videoUrl.orEmpty(), plan.videoMime))

            DeliveryKind.DASH -> DashMediaSource.Factory(dataSource)
                .createMediaSource(itemFor(plan.manifestUrl ?: plan.videoUrl.orEmpty(), plan.videoMime))

            else -> {
                val video = plan.videoUrl
                val audio = plan.audioUrl
                when {
                    video != null && audio != null -> MergingMediaSource(
                        true,
                        ProgressiveMediaSource.Factory(dataSource)
                            .createMediaSource(itemFor(video, plan.videoMime)),
                        ProgressiveMediaSource.Factory(dataSource)
                            .createMediaSource(itemFor(audio, plan.audioMime ?: "audio/mp4")),
                    )

                    video != null -> ProgressiveMediaSource.Factory(dataSource)
                        .createMediaSource(itemFor(video, plan.videoMime))

                    audio != null -> ProgressiveMediaSource.Factory(dataSource)
                        .createMediaSource(itemFor(audio, plan.audioMime ?: "audio/mp4"))

                    plan.manifestUrl != null -> ProgressiveMediaSource.Factory(dataSource)
                        .createMediaSource(itemFor(plan.manifestUrl, plan.videoMime))

                    // Nothing in the plan to play: the default factory still handles the
                    // item, subtitles included.
                    else -> return fallback.createMediaSource(mediaItem)
                }
            }
        }
        return withSubtitles(source, mediaItem, dataSource)
    }

    /**
     * Attaches the item's subtitle configurations to the source built above.
     *
     * `DefaultMediaSourceFactory` does this itself, but every branch above builds its
     * source directly, so the configurations the coordinator sets were parsed and dropped:
     * the captions sheet listed languages, the chip flipped to `CC es`, and no caption was
     * ever rendered.
     *
     * The sidecars are `SingleSampleMediaSource`, which is what Media3 1.11.1's own
     * `DefaultMediaSourceFactory.createMediaSource` constructs for a side-loaded subtitle
     * (verified in its bytecode: it passes the `SubtitleConfiguration` straight to
     * `SingleSampleMediaSource.Factory`). The class is deprecated, not broken, so the
     * warning is suppressed: any replacement would have to come with the default factory.
     */
    @Suppress("DEPRECATION")
    private fun withSubtitles(
        source: MediaSource,
        mediaItem: MediaItem,
        dataSource: DataSource.Factory,
    ): MediaSource {
        val configurations = mediaItem.localConfiguration?.subtitleConfigurations.orEmpty()
        if (configurations.isEmpty()) return source
        val text = SingleSampleMediaSource.Factory(dataSource)
        val sidecars = configurations.map { configuration ->
            text.createMediaSource(configuration, C.TIME_UNSET)
        }
        return MergingMediaSource(source, *sidecars.toTypedArray())
    }

    private companion object {
        /**
         * Browser-like UA: googlevideo rejects some player UAs and the CDN signs the URL
         * for the UA that extracted it, which is what both backends use.
         */
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"

        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 25_000
    }
}
