package io.github.iseaprojects.rivo.ui.media

import io.github.iseaprojects.rivo.core.model.FeedKind
import io.github.iseaprojects.rivo.core.model.LibraryKind
import io.github.iseaprojects.rivo.core.model.MediaSummary
import io.github.iseaprojects.rivo.core.model.MediaProvider
import io.github.iseaprojects.rivo.core.model.PlaylistSummary
import io.github.iseaprojects.rivo.core.model.ChannelSummary
import io.github.iseaprojects.rivo.core.model.SearchKind
import io.github.iseaprojects.rivo.core.search.SearchFilters
import io.github.iseaprojects.rivo.core.ui.CountFormat
import io.github.iseaprojects.rivo.data.extract.AccountFeed
import io.github.iseaprojects.rivo.data.extract.ChannelDetails
import io.github.iseaprojects.rivo.data.extract.ChannelTab
import io.github.iseaprojects.rivo.data.extract.ChannelTabKind
import io.github.iseaprojects.rivo.data.extract.ContinueToken
import io.github.iseaprojects.rivo.data.extract.ExtractorException
import io.github.iseaprojects.rivo.data.extract.ExtractorRouter
import io.github.iseaprojects.rivo.data.extract.FailureKind
import io.github.iseaprojects.rivo.data.extract.Kiosk
import io.github.iseaprojects.rivo.data.extract.MediaPage
import io.github.iseaprojects.rivo.data.extract.PlaylistDetails
import io.github.iseaprojects.rivo.data.download.DownloadRepository
import io.github.iseaprojects.rivo.data.library.LibraryLimits
import io.github.iseaprojects.rivo.data.library.LibraryRepository

/**
 * Where a data screen gets its rows.
 *
 * This is the Android equivalent of the URL a yt-x menu function passes to `yt-dlp`
 * before re-entering the menu loop: the screen owns only the source, so the same
 * composable serves feeds, searches, channels and playlists.
 */
sealed interface MediaSource {

    data class Feed(val kind: FeedKind) : MediaSource

    data class Search(
        val query: String,
        val kind: SearchKind,
        val filterToken: String? = null,
        /** Set for a channel's own Search tab; the `sp` codes do not apply there. */
        val channelUrl: String? = null,
    ) : MediaSource {
        /**
         * YouTube `sp` for this scope plus the one active chip. A channel search has no
         * filter codes at all: the channel's Search tab takes only the query.
         */
        val sp: String?
            get() = if (channelUrl != null) {
                null
            } else {
                SearchFilters.byToken(filterToken)?.sp ?: SearchFilters.kindSp(kind)
            }
    }

    data class Channel(val url: String, val tab: ChannelTabKind? = null) : MediaSource

    data class Playlist(val url: String) : MediaSource

    /** Videos the library owns: saved, recent, or (F6) downloaded. */
    data class Library(val kind: LibraryKind) : MediaSource

    /** The contents of one user-created playlist. */
    data class LocalPlaylist(val id: Long) : MediaSource
}

/** One loaded page plus the metadata that only arrives with it. */
data class MediaLoad(
    val page: MediaPage,
    val channel: ChannelDetails? = null,
    val playlist: PlaylistDetails? = null,
)

/**
 * Backend call per source.
 *
 * Local sources (saved, recent, a custom playlist) read Room instead of the network and
 * return everything in one page: [MediaListState] re-chunks it, so Previous/Next stay
 * instant and the pager reports a real page count.
 *
 * Account feeds are only served when `CONFIG_ACCOUNT_FEEDS` is on and cookies exist; the
 * router raises a precise UNSUPPORTED otherwise, which the screen shows verbatim.
 */
class MediaLoader(
    private val router: ExtractorRouter,
    private val library: LibraryRepository,
    private val downloads: DownloadRepository,
) {

    suspend fun load(source: MediaSource, token: ContinueToken?): MediaLoad = when (source) {
        is MediaSource.Search -> {
            val channelUrl = source.channelUrl
            if (channelUrl != null) {
                MediaLoad(page = router.channelSearch(channelUrl, source.query, token))
            } else {
                MediaLoad(page = router.search(source.query, source.sp, token))
            }
        }

        is MediaSource.Channel -> {
            val result = router.forUrl(source.url)
                .channel(source.url, source.tab?.let { tab(it, source.url) }, token)
            MediaLoad(page = result.page, channel = result.details)
        }

        is MediaSource.Playlist -> {
            val result = router.forUrl(source.url).playlist(source.url, token)
            // The playlist title becomes the download folder, so it has to travel with
            // every row: the actions menu only sees the item, never the page.
            val page = result.page.copy(
                items = result.page.items.map { item -> item.copy(playlistTitle = result.details.summary.title) },
            )
            MediaLoad(page = page, playlist = result.details)
        }

        is MediaSource.Library -> MediaLoad(page = localPage(source.kind))
        is MediaSource.LocalPlaylist -> MediaLoad(
            page = MediaPage(items = library.playlistItemsOnce(source.id)),
        )

        is MediaSource.Feed -> when (source.kind) {
            // The only feed YouTube still serves without cookies; NewPipe reports the
            // retirement of `/feed/trending` as REMOVED, which the UI shows verbatim.
            FeedKind.TRENDING -> MediaLoad(page = router.kiosk(Kiosk.TRENDING, token))
            FeedKind.SUBSCRIPTIONS ->
                MediaLoad(page = router.accountFeed(AccountFeed.SUBSCRIPTIONS, token))
            FeedKind.HISTORY ->
                MediaLoad(page = router.accountFeed(AccountFeed.HISTORY, token))
            FeedKind.WATCH_LATER ->
                MediaLoad(page = router.accountFeed(AccountFeed.WATCH_LATER, token))
            FeedKind.LIKED ->
                MediaLoad(page = router.accountFeed(AccountFeed.LIKED, token))
            FeedKind.PLAYLISTS ->
                MediaLoad(page = router.accountFeed(AccountFeed.PLAYLISTS, token))
            // The recommendation feed is an InnerTube construct no extractor exposes.
            FeedKind.HOME -> throw ExtractorException(
                FailureKind.UNSUPPORTED,
                "YouTube's home feed is not exposed to third-party clients; use Trending, " +
                    "Subscriptions or Search",
            )
            else -> throw ExtractorException(
                FailureKind.UNSUPPORTED,
                "feed ${source.kind.name} requires a signed-in extractor",
            )
        }
    }

    private suspend fun localPage(kind: LibraryKind): MediaPage = when (kind) {
        LibraryKind.SAVED -> library.savedOnce().let { items ->
            MediaPage(items = items, totalCount = items.size.toLong())
        }

        LibraryKind.RECENT -> library.recentsOnce(MAX_RECENTS).let { items ->
            MediaPage(items = items, totalCount = items.size.toLong())
        }

        LibraryKind.DOWNLOADS -> downloads.filesOnce().map { file ->
            MediaSummary(
                id = file.id,
                url = file.uri.toString(),
                title = file.name.substringBeforeLast('.', file.name),
                provider = MediaProvider.OTHER,
            )
        }.let { items ->
            MediaPage(items = items, totalCount = items.size.toLong())
        }
    }

    /**
     * A tab handle for the first request, before the channel's own tab list is known.
     * Both backends resolve it by kind or by the URL suffix.
     */
    private fun tab(kind: ChannelTabKind, channelUrl: String): ChannelTab = ChannelTab(
        id = kind.name,
        name = kind.name,
        url = channelUrl.trimEnd('/') + "/" + slug(kind),
        kind = kind,
    )

    private fun slug(kind: ChannelTabKind): String = when (kind) {
        ChannelTabKind.VIDEOS -> "videos"
        ChannelTabKind.SHORTS -> "shorts"
        ChannelTabKind.LIVE -> "streams"
        ChannelTabKind.PLAYLISTS -> "playlists"
        ChannelTabKind.FEATURED -> "featured"
        ChannelTabKind.PODCASTS -> "podcasts"
        ChannelTabKind.RELEASES -> "releases"
        ChannelTabKind.OTHER -> ""
    }

    companion object {
        /**
         * Hard ceiling for the recents screen. `CONFIG_NO_OF_RECENT` decides how many rows
         * are *kept* (the table is trimmed on every open); this only bounds the read.
         */
        const val MAX_RECENTS = LibraryLimits.RECENTS_CEILING
    }
}

/**
 * The three kinds of row a list screen can show. One sealed type keeps paging, filtering
 * and preview generic without losing the concrete model on activation.
 */
sealed interface MediaEntry {

    val key: String

    /** Text the fuzzy filter sees; yt-x filters on the rendered line, this is wider. */
    val searchText: String

    data class Video(val media: MediaSummary) : MediaEntry {
        override val key: String get() = "v:" + media.id
        override val searchText: String get() = listOfNotNull(
            media.title,
            media.channelName,
            media.publishedText,
            media.durationText,
            CountFormat.compact(media.viewCount).takeIf { it.isNotEmpty() },
        ).joinToString(" ")
    }

    data class Playlist(val playlist: PlaylistSummary) : MediaEntry {
        override val key: String get() = "p:" + playlist.id
        override val searchText: String get() = listOfNotNull(
            playlist.title,
            playlist.channelName,
            playlist.itemCount?.toString(),
        ).joinToString(" ")
    }

    data class Channel(val channel: ChannelSummary) : MediaEntry {
        override val key: String get() = "c:" + channel.id
        override val searchText: String get() = listOfNotNull(
            channel.name,
            CountFormat.compact(channel.subscriberCount).takeIf { it.isNotEmpty() },
        ).joinToString(" ")
    }
}

/** Flatten a page into entries, dropping entries whose identity is unusable. */
fun MediaPage.entries(): List<MediaEntry> = buildList {
    items.forEach { if (it.id.isNotBlank() || it.url.isNotBlank()) add(MediaEntry.Video(it)) }
    playlists.forEach { if (it.id.isNotBlank() || it.url.isNotBlank()) add(MediaEntry.Playlist(it)) }
    channels.forEach { if (it.id.isNotBlank() || it.url.isNotBlank()) add(MediaEntry.Channel(it)) }
}

/** A YouTube mix is just a playlist whose id is `RD` + the seed video id. */
fun MediaSummary.mixPlaylist(): PlaylistSummary = PlaylistSummary(
    id = "RD$id",
    url = "https://www.youtube.com/watch?v=$id&list=RD$id",
    title = title,
    provider = provider,
    channelName = channelName,
    channelId = channelId,
)

