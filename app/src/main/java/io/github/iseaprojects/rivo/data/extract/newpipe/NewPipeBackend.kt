package io.github.iseaprojects.rivo.data.extract.newpipe

import io.github.iseaprojects.rivo.BuildConfig
import io.github.iseaprojects.rivo.core.model.Availability
import io.github.iseaprojects.rivo.core.model.ChannelSummary
import io.github.iseaprojects.rivo.core.model.MediaProvider
import io.github.iseaprojects.rivo.core.model.MediaSummary
import io.github.iseaprojects.rivo.core.model.PlaylistSummary
import io.github.iseaprojects.rivo.data.extract.Chapter
import io.github.iseaprojects.rivo.data.extract.ChannelDetails
import io.github.iseaprojects.rivo.data.extract.ChannelResult
import io.github.iseaprojects.rivo.data.extract.ChannelTab
import io.github.iseaprojects.rivo.data.extract.ChannelTabKind
import io.github.iseaprojects.rivo.data.extract.ContinueToken
import io.github.iseaprojects.rivo.data.extract.DeliveryKind
import io.github.iseaprojects.rivo.data.extract.ExtractorCapabilities
import io.github.iseaprojects.rivo.data.extract.ExtractorException
import io.github.iseaprojects.rivo.data.extract.ExtractorFailures
import io.github.iseaprojects.rivo.data.extract.FailureKind
import io.github.iseaprojects.rivo.data.extract.Kiosk
import io.github.iseaprojects.rivo.data.extract.MediaPage
import io.github.iseaprojects.rivo.data.extract.PlaylistDetails
import io.github.iseaprojects.rivo.data.extract.PlaylistResult
import io.github.iseaprojects.rivo.data.extract.StreamFormat
import io.github.iseaprojects.rivo.data.extract.StreamKind
import io.github.iseaprojects.rivo.data.extract.SubtitleTrack
import io.github.iseaprojects.rivo.data.extract.UrlCanon
import io.github.iseaprojects.rivo.data.extract.VideoDetails
import io.github.iseaprojects.rivo.data.extract.VideoExtractor
import io.github.iseaprojects.rivo.data.net.CookieProvider
import io.github.iseaprojects.rivo.data.net.NewPipeTransport
import io.github.iseaprojects.rivo.data.net.PoTokenSpec
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamSegment
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.extractor.Image

/**
 * NewPipeExtractor backend: the always-present, fast path.
 *
 * Everything is a direct mapping of NewPipe's models into the app's own models. The
 * interesting parts are the ones NewPipe does not model for us:
 *
 *  - **Availability.** `ContentAvailability`, `Privacy` and the exception types each say
 *    part of the story, so they are folded into [Availability] once, here.
 *  - **Audio tracks.** YouTube serves multiple audio languages as separate `AudioStream`s;
 *    the original track is marked so the player can default to it instead of a dub.
 *  - **Paging.** NewPipe's `Page` is serialised by [NewPipePageCodec] so explicit paging
 *    survives process death.
 */
class NewPipeBackend(
    private val cookies: CookieProvider,
    private val poToken: () -> PoTokenSpec?,
    private val locale: () -> Locale = { Locale.getDefault() },
) : VideoExtractor {

    override val id: String = ID
    override val displayName: String = "NewPipeExtractor"

    override val capabilities: ExtractorCapabilities = ExtractorCapabilities(
        search = true,
        searchFilters = true,
        suggestions = true,
        channels = true,
        channelTabs = true,
        playlists = true,
        // Trending (`FEtrending`) was retired by YouTube and now answers HTTP 400 to the
        // InnerTube browse call, so it is not advertised. The other feeds still work.
        kiosks = setOf(Kiosk.MUSIC, Kiosk.GAMING, Kiosk.MOVIES, Kiosk.LIVE, Kiosk.PODCASTS),
        // `streamSegments` are the creator's chapters (the description timestamps), not
        // SponsorBlock data, so they are advertised as chapters and never as segments.
        chapters = true,
        subtitles = true,
        storyboards = true,
        cookies = true,
        poToken = true,
        // NewPipeExtractor speaks the YouTube API only; the router sends non-YouTube URLs
        // to yt-dlp when that flavor is compiled in.
        nonYouTubeSites = false,
        accountFeeds = false,
    )

    private val lock = Any()

    @Volatile
    private var initializedFor: Locale? = null

    @Volatile
    private var registeredToken: PoTokenSpec? = null

    override fun isAvailable(): Boolean = true

    override suspend fun warmUp(): String? = run {
        ensureInitialized()
        BuildConfig.NEWPIPE_VERSION
    }

    override suspend fun video(idOrUrl: String): VideoDetails = run {
        val videoId = UrlCanon.videoId(idOrUrl)
            ?: throw ExtractorException(FailureKind.UNSUPPORTED, "not a video URL: $idOrUrl")
        val info = StreamInfo.getInfo(service, UrlCanon.watchUrl(videoId))
        mapVideo(info, videoId)
    }

    override suspend fun search(
        query: String,
        filters: String?,
        token: ContinueToken?,
    ): MediaPage = run {
        val handler = searchHandler(query, contentFilter(filters))
        val extractor = service.getSearchExtractor(handler)
        // ListExtractor implementations parse lazily: fetchPage() runs onFetchPage, which
        // is what fills the JSON the page methods read. Skipping it yields a null page.
        extractor.fetchPage()
        val page = NewPipePageCodec.decode(token)?.let { extractor.getPage(it) }
            ?: extractor.getInitialPage()
        page.toMediaPage()
    }

    override suspend fun suggestions(query: String): List<String> = run {
        if (query.isBlank()) return@run emptyList()
        service.suggestionExtractor.suggestionList(query).orEmpty()
    }

    override suspend fun channel(
        url: String,
        tab: ChannelTab?,
        token: ContinueToken?,
    ): ChannelResult = run {
        val canonical = UrlCanon.channelUrl(url) ?: url
        val extractor = service.getChannelExtractor(canonical)
        // ChannelInfo.getInfo(extractor) reads extractor.id/name/avatars, and those
        // accessors assert that the HTML page has been fetched.
        extractor.fetchPage()
        val info = ChannelInfo.getInfo(extractor)

        val links = info.tabs.orEmpty()
        val tabs = links.map { link ->
            val kind = ChannelTabKind.fromUrl(link.url)
            ChannelTab(id = link.url, name = tabLabel(kind), url = link.url, kind = kind)
        }
        val selected = selectTab(links, tab)

        // Channels expose their content through tabs only (videos, shorts, playlists...).
        // The extractor itself has no page stream, so a missing tab list means no items.
        val page = if (selected != null) {
            val tabExtractor = service.getChannelTabExtractor(selected)
            tabExtractor.fetchPage()
            val itemsPage = NewPipePageCodec.decode(token)?.let { tabExtractor.getPage(it) }
                ?: tabExtractor.getInitialPage()
            itemsPage.toMediaPage()
        } else {
            MediaPage()
        }

        val details = ChannelDetails(
            summary = ChannelSummary(
                id = channelIdOf(info.url) ?: canonical,
                url = info.url ?: canonical,
                name = info.name.orEmpty(),
                avatarUrl = bestImage(info.avatars),
                subscriberCount = info.subscriberCount.takeIf { it >= 0 },
                description = info.description,
                isSubscribed = false,
            ),
            bannerUrl = bestImage(info.banners),
            tabs = tabs,
        )
        ChannelResult(details, page)
    }

    override suspend fun playlist(url: String, token: ContinueToken?): PlaylistResult = run {
        val canonical = UrlCanon.playlistId(url)
            ?.let { "https://www.youtube.com/playlist?list=$it" }
            ?: url
        val extractor = service.getPlaylistExtractor(canonical)
        extractor.fetchPage()
        val info = PlaylistInfo.getInfo(extractor)
        val page = NewPipePageCodec.decode(token)?.let {
            PlaylistInfo.getMoreItems(service, info.url ?: canonical, it)
        } ?: extractor.getInitialPage()

        val details = PlaylistDetails(
            summary = PlaylistSummary(
                id = info.id ?: UrlCanon.playlistId(canonical) ?: canonical,
                url = info.url ?: canonical,
                title = info.name.orEmpty(),
                channelName = info.uploaderName,
                channelId = channelIdOf(info.uploaderUrl),
                itemCount = info.streamCount.takeIf { it >= 0 }?.toInt(),
                thumbnailUrl = bestImage(info.thumbnails),
                description = info.description?.content,
            ),
            description = info.description?.content,
            bannerUrl = bestImage(info.banners),
        )
        PlaylistResult(details, page.toMediaPage())
    }

    override suspend fun kiosk(kiosk: Kiosk, token: ContinueToken?): MediaPage = run {
        if (kiosk !in capabilities.kiosks) {
            throw ExtractorException(
                FailureKind.REMOVED,
                when (kiosk) {
                    Kiosk.TRENDING ->
                        "YouTube retired its Trending page; Music, Gaming, Live and Movies " +
                            "feeds are still available"
                    else -> "kiosk ${kiosk.name} is not available"
                },
            )
        }
        val kioskId = kiosk.newPipeId
            ?: throw ExtractorException(FailureKind.UNSUPPORTED, "kiosk ${kiosk.name} unavailable")
        val page = NewPipePageCodec.decode(token)
        val extractor = service.kioskList.getExtractorById(kioskId, page)
        extractor.fetchPage()
        extractor.getInitialPage().toMediaPage()
    }

    // -- initialisation -------------------------------------------------------------

    private val service: StreamingService get() = NewPipe.getService(ServiceList.YouTube.serviceId)

    private fun ensureInitialized() {
        val desiredLocale = locale()
        val desiredToken = poToken()
        synchronized(lock) {
            if (initializedFor != desiredLocale) {
                val country = desiredLocale.country.takeIf { it.isNotBlank() } ?: "US"
                NewPipe.init(
                    NewPipeTransport(cookies),
                    Localization.fromLocale(desiredLocale),
                    ContentCountry(country),
                )
                initializedFor = desiredLocale
            }
            if (registeredToken != desiredToken) {
                YoutubeStreamExtractor.setPoTokenProvider { _ ->
                    poToken()?.let { PoTokenResult(it.visitorData, it.playerToken, it.streamingToken) }
                }
                registeredToken = desiredToken
            }
        }
    }

    private suspend fun <T> run(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            ensureInitialized()
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw ExtractorFailures.fromThrowable(error)
        }
    }

    // -- mapping --------------------------------------------------------------------

    private fun searchHandler(query: String, contentFilter: String?): SearchQueryHandler {
        val factory = service.searchQHFactory
        val filters = contentFilter?.let { listOf(it) } ?: emptyList()
        return factory.fromQuery(query, filters, "")
    }

    /**
     * NewPipe's search handler takes a *content-filter name* (`videos`, `channels`,
     * `playlists`), not the `sp` protobuf, and maps it to YouTube's params itself. Pass a
     * raw `sp` and every scope flag stays false, so the collector drops the whole page:
     * that is why an unfiltered-looking search used to come back empty.
     *
     * Only the three scope codes have an equivalent. Date, duration, feature and sort
     * chips are protobuf the extractor cannot express, so they report UNSUPPORTED and
     * [ExtractorRouter] hands the request to yt-dlp when that backend is compiled in.
     */
    private fun contentFilter(sp: String?): String? = when (sp) {
        null, "" -> null
        SP_VIDEOS -> "videos"
        SP_CHANNELS -> "channels"
        SP_PLAYLISTS -> "playlists"
        else -> throw ExtractorException(
            FailureKind.UNSUPPORTED,
            "NewPipeExtractor can only search videos, channels and playlists; " +
                "this filter needs the yt-dlp extractor",
        )
    }

    private fun selectTab(
        links: List<ListLinkHandler>,
        requested: ChannelTab?,
    ): ListLinkHandler? {
        if (requested == null) {
            return links.firstOrNull { ChannelTabKind.fromUrl(it.url) == ChannelTabKind.VIDEOS }
                ?: links.firstOrNull()
        }
        return links.firstOrNull { it.url == requested.url }
            ?: links.firstOrNull { ChannelTabKind.fromUrl(it.url) == requested.kind }
    }

    private fun mapVideo(info: StreamInfo, requestedId: String): VideoDetails {
        val videoId = info.id?.takeIf { it.isNotBlank() } ?: requestedId
        val uploaderUrl = info.uploaderUrl
        val summary = MediaSummary(
            id = videoId,
            url = info.url?.takeIf { it.isNotBlank() } ?: UrlCanon.watchUrl(videoId),
            title = info.name.orEmpty(),
            provider = MediaProvider.YOUTUBE,
            channelId = channelIdOf(uploaderUrl),
            channelName = info.uploaderName,
            channelUrl = uploaderUrl,
            description = info.description?.content,
            durationSeconds = info.duration.takeIf { it > 0 },
            viewCount = info.viewCount.takeIf { it >= 0 },
            publishedAtEpochMs = epochMillis(info.uploadDate),
            publishedText = info.textualUploadDate,
            thumbnailUrl = bestImage(info.thumbnails) ?: UrlCanon.thumbnailUrl(videoId),
            isLive = info.streamType == StreamType.LIVE_STREAM ||
                info.streamType == StreamType.AUDIO_LIVE_STREAM,
            isShort = info.isShortFormContent,
            isUpcoming = info.contentAvailability == ContentAvailability.UPCOMING,
            availability = availabilityOf(info.contentAvailability, info.privacy),
        )

        val formats = buildList {
            info.videoStreams.orEmpty().forEach { add(it.toFormat(StreamKind.MUXED)) }
            info.videoOnlyStreams.orEmpty().forEach { add(it.toFormat(StreamKind.VIDEO_ONLY)) }
            info.audioStreams.orEmpty().forEach { add(it.toFormat()) }
        }.distinctBy { "${it.kind}|${it.formatId}|${it.itag}|${it.audioTrackKey}" }

        val subtitles = info.subtitles.orEmpty().mapNotNull { subtitle ->
            subtitle.toTrack()
        }

        // `streamSegments` carry a start and a title only; the end of one chapter is the start
        // of the next, and the last one runs to the end of the video. They are chapters, not
        // SponsorBlock segments (those come from the SponsorBlock API in the coordinator).
        val chapters = chaptersOf(info.streamSegments.orEmpty(), info.duration)

        val storyboard = info.previewFrames?.firstOrNull()
            ?.urls?.firstOrNull()

        return VideoDetails(
            summary = summary,
            formats = formats,
            subtitles = subtitles,
            chapters = chapters,
            segments = emptyList(),
            heatmap = emptyList(),
            hlsManifestUrl = info.hlsUrl,
            dashManifestUrl = info.dashMpdUrl,
            storyboardUrl = storyboard,
            startAtSeconds = info.startPosition.takeIf { it > 0 } ?: 0,
            extractor = id,
        )
    }

    private fun VideoStream.toFormat(kind: StreamKind): StreamFormat {
        val itag = runCatching { itag }.getOrNull()?.takeIf { it > 0 }
        return StreamFormat(
            formatId = itag?.toString() ?: resolution,
            kind = kind,
            delivery = deliveryOf(deliveryMethod),
            url = url,
            mimeType = format?.mimeType,
            container = format?.suffix,
            codec = codec,
            qualityLabel = resolution,
            height = height.takeIf { it > 0 },
            width = width.takeIf { it > 0 },
            fps = fps.takeIf { it > 0 },
            videoBitrateKbps = bitrate.takeIf { it > 0 },
            totalBitrateKbps = bitrate.takeIf { it > 0 },
            itag = itag,
            manifestUrl = manifestUrl,
        )
    }

    private fun AudioStream.toFormat(): StreamFormat {
        val itag = runCatching { itag }.getOrNull()?.takeIf { it > 0 }
        val trackType = runCatching { audioTrackType }.getOrNull()
        return StreamFormat(
            formatId = itag?.toString() ?: quality ?: averageBitrate.toString(),
            kind = StreamKind.AUDIO_ONLY,
            delivery = deliveryOf(deliveryMethod),
            url = url,
            mimeType = format?.mimeType,
            container = format?.suffix,
            codec = codec,
            qualityLabel = quality,
            audioBitrateKbps = averageBitrate.takeIf { it > 0 } ?: bitrate.takeIf { it > 0 },
            totalBitrateKbps = bitrate.takeIf { it > 0 },
            audioLanguage = runCatching { audioLocale?.toLanguageTag() }.getOrNull(),
            audioTrackId = runCatching { audioTrackId }.getOrNull(),
            audioTrackName = runCatching { audioTrackName }.getOrNull(),
            isOriginalAudio = trackType == AudioTrackType.ORIGINAL,
            isDefaultAudio = trackType == AudioTrackType.ORIGINAL,
            itag = itag,
            manifestUrl = manifestUrl,
        )
    }

    private fun SubtitlesStream.toTrack(): SubtitleTrack? {
        val target = url ?: content ?: return null
        return SubtitleTrack(
            languageTag = languageTag ?: this.locale?.toLanguageTag().orEmpty(),
            label = displayLanguageName ?: languageTag.orEmpty(),
            url = target,
            container = extension ?: "vtt",
            isAutoGenerated = isAutoGenerated,
            isTranslatable = false,
        )
    }

    private fun ListExtractor.InfoItemsPage<*>.toMediaPage(): MediaPage {
        val videos = mutableListOf<MediaSummary>()
        val playlists = mutableListOf<PlaylistSummary>()
        val channels = mutableListOf<ChannelSummary>()
        items.orEmpty().forEach { item ->
            when (item) {
                is StreamInfoItem -> videos += item.toSummary()
                is PlaylistInfoItem -> playlists += item.toSummary()
                is ChannelInfoItem -> channels += item.toSummary()
                else -> Unit
            }
        }
        return MediaPage(
            items = videos,
            playlists = playlists,
            channels = channels,
            nextToken = NewPipePageCodec.encode(nextPage),
            partialErrors = errors.orEmpty().mapNotNull { it.message },
        )
    }

    private fun StreamInfoItem.toSummary(): MediaSummary {
        // The URL is the authoritative identity: StreamInfoItem has no video id of its own,
        // and an unqualified `id` would resolve to NewPipeBackend.id (the literal "newpipe").
        val videoId = UrlCanon.videoId(url) ?: url
        return MediaSummary(
            id = videoId,
            url = url ?: UrlCanon.watchUrl(videoId),
            title = name.orEmpty(),
            channelId = channelIdOf(uploaderUrl),
            channelName = uploaderName,
            channelUrl = uploaderUrl,
            durationSeconds = duration.takeIf { it > 0 },
            viewCount = viewCount.takeIf { it >= 0 },
            publishedAtEpochMs = epochMillis(uploadDate),
            publishedText = textualUploadDate,
            thumbnailUrl = bestImage(thumbnails) ?: UrlCanon.thumbnailUrl(videoId),
            isLive = streamType == StreamType.LIVE_STREAM ||
                streamType == StreamType.AUDIO_LIVE_STREAM,
            isShort = isShortFormContent,
            isUpcoming = contentAvailability == ContentAvailability.UPCOMING,
            availability = availabilityOf(contentAvailability, null),
        )
    }

    private fun ChannelInfoItem.toSummary(): ChannelSummary = ChannelSummary(
        id = channelIdOf(url) ?: url,
        url = url.orEmpty(),
        name = name.orEmpty(),
        avatarUrl = bestImage(thumbnails),
        subscriberCount = subscriberCount.takeIf { it >= 0 },
        description = description,
    )

    private fun PlaylistInfoItem.toSummary(): PlaylistSummary = PlaylistSummary(
        // PlaylistInfoItem has no id member either: derive it from the list URL.
        id = UrlCanon.playlistId(url) ?: url.orEmpty(),
        url = url.orEmpty(),
        title = name.orEmpty(),
        channelName = uploaderName,
        channelId = channelIdOf(uploaderUrl),
        itemCount = streamCount.takeIf { it >= 0 }?.toInt(),
        thumbnailUrl = bestImage(thumbnails),
        description = description?.content,
    )

    private fun bestImage(images: List<Image>?): String? = images.orEmpty()
        .maxByOrNull { it.height }
        ?.url

    private fun epochMillis(date: DateWrapper?): Long? =
        runCatching { date?.instant?.toEpochMilli() }.getOrNull()

    private fun availabilityOf(
        availability: ContentAvailability?,
        privacy: StreamExtractor.Privacy?,
    ): Availability = when {
        privacy == StreamExtractor.Privacy.PRIVATE -> Availability.PRIVATE
        availability == ContentAvailability.MEMBERSHIP -> Availability.MEMBERS_ONLY
        availability == ContentAvailability.PAID -> Availability.MEMBERS_ONLY
        availability == ContentAvailability.UPCOMING -> Availability.UPCOMING
        else -> Availability.AVAILABLE
    }

    private fun deliveryOf(method: DeliveryMethod?): DeliveryKind = when (method) {
        DeliveryMethod.PROGRESSIVE_HTTP -> DeliveryKind.PROGRESSIVE
        DeliveryMethod.DASH -> DeliveryKind.DASH
        DeliveryMethod.HLS -> DeliveryKind.HLS
        DeliveryMethod.SS, DeliveryMethod.TORRENT -> DeliveryKind.OTHER
        null -> DeliveryKind.PROGRESSIVE
    }

    private fun channelIdOf(url: String?): String? = url
        ?.substringAfter("/channel/", "")
        ?.substringBefore('/')
        ?.takeIf { it.isNotBlank() }

    private fun tabLabel(kind: ChannelTabKind): String = when (kind) {
        ChannelTabKind.VIDEOS -> "Videos"
        ChannelTabKind.SHORTS -> "Shorts"
        ChannelTabKind.LIVE -> "Live"
        ChannelTabKind.PLAYLISTS -> "Playlists"
        ChannelTabKind.FEATURED -> "Featured"
        ChannelTabKind.PODCASTS -> "Podcasts"
        ChannelTabKind.RELEASES -> "Releases"
        ChannelTabKind.OTHER -> "More"
    }

    companion object {
        const val ID = "newpipe"

        /** The three scope codes NewPipe can express as a content-filter name. */
        private const val SP_VIDEOS = "EgIQAQ%253D%253D"
        private const val SP_CHANNELS = "EgIQAg%253D%253D"
        private const val SP_PLAYLISTS = "EgIQAw%253D%253D"
    }
}

/**
 * Chapters from NewPipe's stream segments.
 *
 * A segment is a start and a title; NewPipe does not compute an end, so each chapter ends where
 * the next one begins and the last one runs to the end of the video. Untitled segments are
 * dropped and equal starts are collapsed, which keeps the chapter sheet usable on descriptions
 * that repeat a timestamp.
 */
internal fun chaptersOf(segments: List<StreamSegment>, durationSeconds: Long): List<Chapter> {
    val sorted = segments
        .mapNotNull { segment ->
            val title = segment.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Chapter(
                startMs = segment.startTimeSeconds.coerceAtLeast(0) * 1000L,
                endMs = null,
                title = title,
            )
        }
        .distinctBy { it.startMs }
        .sortedBy { it.startMs }
    return sorted.mapIndexed { index, chapter ->
        val end = sorted.getOrNull(index + 1)?.startMs
            ?: (durationSeconds * 1000L).takeIf { it > chapter.startMs }
        chapter.copy(endMs = end)
    }
}
