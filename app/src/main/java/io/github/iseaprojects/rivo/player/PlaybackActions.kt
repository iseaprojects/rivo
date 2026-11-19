package io.github.iseaprojects.rivo.player

import android.content.Context
import io.github.iseaprojects.rivo.core.config.ConfigKeys
import io.github.iseaprojects.rivo.core.config.ConfigSnapshot
import io.github.iseaprojects.rivo.core.model.MediaSummary
import io.github.iseaprojects.rivo.core.model.PlaylistSummary
import io.github.iseaprojects.rivo.core.model.mixPlaylist
import io.github.iseaprojects.rivo.data.extract.ExtractorException
import io.github.iseaprojects.rivo.di.AppGraph
import kotlinx.coroutines.CancellationException

/** Result of an action that may have to talk to the network before it can start. */
sealed interface LaunchOutcome {

    data object Started : LaunchOutcome

    /** [messageKey] is a `TXT_*` language-pack key; [detail] is appended when present. */
    data class Failed(val messageKey: String, val detail: String? = null) : LaunchOutcome
}

/**
 * The sentence shown when an action fails.
 *
 * Several language-pack strings already end in a colon ("currently set to:"), so the detail
 * is joined after the base with the punctuation normalised instead of producing "to:: mpv".
 */
fun LaunchOutcome.Failed.describe(strings: io.github.iseaprojects.rivo.core.i18n.Strings): String {
    val base = strings.text(messageKey, messageKey).trimEnd(':', '：', ' ')
    return listOfNotNull(base.takeIf { it.isNotBlank() }, detail?.takeIf { it.isNotBlank() })
        .joinToString(": ")
}

/**
 * Turns menu actions and launch intents into player sessions.
 *
 * The queue is built before the player opens so "Play All" means all of it, not the first
 * page: `CONFIG_PER_PAGE` is a UI window, not a playlist length. The service still
 * resolves stream URLs lazily, one item at a time.
 */
class PlaybackActions(private val graph: AppGraph) {

    suspend fun play(context: Context, media: MediaSummary, mode: PlayMode): LaunchOutcome {
        // A downloaded file is readable only through this app's FileProvider/MediaStore
        // grant, so handing it to an external player would produce "cannot open file".
        if (isLocalUri(media.url)) {
            return playLocal(context, media.url, media.title, mode)
        }
        val config = graph.runtime.snapshot.value
        if (config.isExternal()) {
            return ExternalPlayer.handOff(context, graph, media, mode, config)
        }
        val item = media.toQueueItem()
        PlayerIntents.open(context, request(listOf(item), mode = mode, title = media.title))
        return LaunchOutcome.Started
    }

    /**
     * Plays a file that is already on the device.
     *
     * No extractor call, no quality ladder: the bytes are local, so the coordinator builds
     * the item straight from the URI. The queue type is still used so next/previous,
     * the notification and the player screen keep working unchanged.
     */
    fun playLocal(context: Context, url: String, title: String, mode: PlayMode): LaunchOutcome {
        if (!isLocalUri(url)) return LaunchOutcome.Failed("TXT_PLAYER_NOT_FOUND")
        val item = QueueItem(
            id = url,
            url = url,
            title = title,
        )
        PlayerIntents.open(
            context,
            request(listOf(item), mode = mode, title = title),
        )
        return LaunchOutcome.Started
    }

    suspend fun playPlaylist(
        context: Context,
        playlist: PlaylistSummary,
        mode: PlayMode,
    ): LaunchOutcome {
        val items = playlistItems(playlist) ?: return LaunchOutcome.Failed(EMPTY_PLAYLIST)
        if (items.isEmpty()) return LaunchOutcome.Failed(EMPTY_PLAYLIST)
        val config = graph.runtime.snapshot.value
        if (config.isExternal()) {
            return ExternalPlayer.handOff(context, graph, items.first(), mode, config)
        }
        val queue = items.map { it.toQueueItem(playlistTitle = playlist.title) }
        PlayerIntents.open(
            context,
            request(queue, mode = mode, title = playlist.title),
        )
        return LaunchOutcome.Started
    }

    /** Mix is a normal playlist whose id is `RD<video id>`; nothing special to resolve. */
    suspend fun playMix(context: Context, media: MediaSummary, mode: PlayMode): LaunchOutcome =
        playPlaylist(context, media.mixPlaylist(), mode)

    /** Queue for an already-loaded set of items, e.g. a local playlist or saved videos. */
    fun playItems(
        context: Context,
        items: List<MediaSummary>,
        mode: PlayMode,
        title: String,
    ): LaunchOutcome {
        if (items.isEmpty()) return LaunchOutcome.Failed(EMPTY_PLAYLIST)
        PlayerIntents.open(
            context,
            request(items.map { it.toQueueItem() }, mode = mode, title = title),
        )
        return LaunchOutcome.Started
    }

    /** Deep links and shared URLs: resolve first, then watch or queue the whole playlist. */
    suspend fun playUrl(context: Context, url: String, mode: PlayMode): LaunchOutcome =
        when (val target = MediaLinkResolver.resolve(graph.extractors, url)) {
            is ResolvedLink.Video -> play(context, target.item, mode)
            is ResolvedLink.Playlist -> playPlaylist(context, target.summary, mode)
            is ResolvedLink.Channel -> LaunchOutcome.Failed(NOT_PLAYABLE)
            is ResolvedLink.Unreadable -> unreadable(target)
            null -> LaunchOutcome.Failed(NOT_PLAYABLE)
        }

    suspend fun sendToExternal(
        context: Context,
        media: MediaSummary,
        mode: PlayMode,
    ): LaunchOutcome = ExternalPlayer.handOff(
        context,
        graph,
        media,
        mode,
        graph.runtime.snapshot.value,
    )

    suspend fun sendToExternal(
        context: Context,
        url: String,
        mode: PlayMode,
    ): LaunchOutcome = when (val target = MediaLinkResolver.resolve(graph.extractors, url)) {
        is ResolvedLink.Video -> sendToExternal(context, target.item, mode)
        is ResolvedLink.Playlist -> {
            val first = playlistItems(target.summary)?.firstOrNull()
                ?: return LaunchOutcome.Failed(EMPTY_PLAYLIST)
            sendToExternal(context, first, mode)
        }

        is ResolvedLink.Unreadable -> unreadable(target)

        else -> LaunchOutcome.Failed(NOT_PLAYABLE)
    }

    /**
     * A page none of the backends could read. The extractor's own sentence is the detail,
     * so a yt-dlp URL that needs an extractor update says so instead of blaming
     * `CONFIG_PLAYER`.
     */
    private fun unreadable(target: ResolvedLink.Unreadable): LaunchOutcome.Failed =
        LaunchOutcome.Failed("TXT_ANDROID_LOADING", target.error.message)

    /**
     * Termux handoff: opens a login shell with the session variables yt-x exposes, so a
     * power user can follow up with yt-dlp, mpv-cli, or their own script.
     */
    suspend fun openShell(context: Context, media: MediaSummary): LaunchOutcome {
        val details = try {
            graph.extractors.video(media.url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ExtractorException) {
            return LaunchOutcome.Failed("TXT_ANDROID_LOADING", error.message)
        } catch (error: Throwable) {
            return LaunchOutcome.Failed("TXT_ANDROID_LOADING", error.message)
        }
        return TermuxShell.open(
            context = context,
            url = details.summary.url,
            title = details.summary.title,
            videoId = details.summary.id,
            streamUrl = StreamPlanner.plan(details, PlayMode.VIDEO)
                ?.let { it.manifestUrl ?: it.videoUrl },
        )
    }

    /** Shell handoff for a link: resolve the page to a video first, then export it. */
    suspend fun openShellForUrl(context: Context, url: String): LaunchOutcome =
        when (val target = MediaLinkResolver.resolve(graph.extractors, url)) {
            is ResolvedLink.Video -> openShell(context, target.item)

            is ResolvedLink.Playlist -> {
                val first = playlistItems(target.summary)?.firstOrNull()
                    ?: return LaunchOutcome.Failed(EMPTY_PLAYLIST)
                openShell(context, first)
            }

            is ResolvedLink.Unreadable -> unreadable(target)

            else -> LaunchOutcome.Failed(NOT_PLAYABLE)
        }

    private suspend fun playlistItems(playlist: PlaylistSummary): List<MediaSummary>? {
        if (playlist.url.isBlank()) return emptyList()
        val backend = graph.extractors.forUrl(playlist.url)
        return try {
            val items = LinkedHashMap<String, MediaSummary>()
            var token: io.github.iseaprojects.rivo.data.extract.ContinueToken? = null
            do {
                val result = backend.playlist(playlist.url, token)
                result.page.items.forEach { item ->
                    if (items.size < MAX_QUEUE) items.putIfAbsent(item.id.ifBlank { item.url }, item)
                }
                token = result.page.nextToken
            } while (token != null && items.size < MAX_QUEUE)
            items.values.toList()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }

    private fun request(
        items: List<QueueItem>,
        mode: PlayMode,
        title: String,
    ): PlaybackRequest = PlaybackRequest(
        items = items,
        startIndex = 0,
        mode = mode,
        title = title,
        qualityHeight = null,
        audioTrack = null,
    )

    private fun ConfigSnapshot.isExternal(): Boolean =
        string(ConfigKeys.PLAYER, "internal").lowercase() in EXTERNAL_VALUES

    private companion object {
        const val EMPTY_PLAYLIST = "TXT_ANDROID_PLAYLIST_EMPTY"
        const val NOT_PLAYABLE = "TXT_PLAYER_NOT_FOUND"
        const val MAX_QUEUE = 200
        val EXTERNAL_VALUES = setOf("external", "mpv", "mpv-android", "vlc", "mx")

        /** `content://` from MediaStore/SAF/FileProvider and `file://` from legacy storage. */
        fun isLocalUri(url: String): Boolean = when {
            url.startsWith("content://", ignoreCase = true) -> true
            url.startsWith("file://", ignoreCase = true) -> true
            else -> false
        }
    }
}
