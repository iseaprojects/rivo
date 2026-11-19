package io.github.iseaprojects.rivo.player

import io.github.iseaprojects.rivo.core.model.ChannelSummary
import io.github.iseaprojects.rivo.core.model.MediaSummary
import io.github.iseaprojects.rivo.core.model.PlaylistSummary
import io.github.iseaprojects.rivo.data.extract.ExtractorException
import io.github.iseaprojects.rivo.data.extract.ExtractorRouter
import io.github.iseaprojects.rivo.data.extract.FailureKind
import io.github.iseaprojects.rivo.data.extract.UrlCanon
import kotlinx.coroutines.CancellationException

/** What a pasted/deep-linked URL actually names. */
sealed interface ResolvedLink {

    data class Video(val item: MediaSummary) : ResolvedLink

    data class Playlist(val summary: PlaylistSummary) : ResolvedLink

    data class Channel(val summary: ChannelSummary) : ResolvedLink

    /** A page no backend could read; [error] is the extractor's own verdict. */
    data class Unreadable(val error: ExtractorException) : ResolvedLink
}

/**
 * Resolves a YouTube URL before the UI decides what to do with it.
 *
 * A shared link can be a video, a playlist or a channel, and treating all three as
 * "video actions on an unparsed URL" is what used to leave a deep-linked watch page with
 * an empty action menu. Video ids win over `list=`: a watch page with a playlist context
 * still plays the video, exactly as the browser does.
 *
 * A page that no backend can read comes back as [ResolvedLink.Unreadable] rather than
 * `null`: the deep-link route shows the extractor's verdict, and "no suitable media
 * player" (an external-player setting) is not the sentence a broken link deserves.
 */
object MediaLinkResolver {

    suspend fun resolve(router: ExtractorRouter, url: String): ResolvedLink? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null

        UrlCanon.videoId(trimmed)?.let { id -> return video(router, id) }

        UrlCanon.playlistId(trimmed)?.let { listId ->
            val canonical = "https://www.youtube.com/playlist?list=$listId"
            val result = guarded { router.forUrl(canonical).playlist(canonical) }
            val summary = result?.details?.summary ?: PlaylistSummary(
                id = listId,
                url = canonical,
                title = listId,
            )
            return ResolvedLink.Playlist(summary)
        }

        UrlCanon.channelUrl(trimmed)?.let { channelUrl ->
            val result = guarded { router.forUrl(channelUrl).channel(channelUrl) }
            val summary = result?.details?.summary ?: ChannelSummary(
                id = channelUrl,
                url = channelUrl,
                name = channelUrl.substringAfterLast('/'),
            )
            return ResolvedLink.Channel(summary)
        }

        // Non-YouTube sites (SoundCloud, PeerTube) are videos as far as the player is
        // concerned; only the yt-dlp backend can resolve them.
        return video(router, trimmed)
    }

    private suspend fun video(router: ExtractorRouter, idOrUrl: String): ResolvedLink = try {
        ResolvedLink.Video(router.video(idOrUrl).summary)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: ExtractorException) {
        ResolvedLink.Unreadable(error)
    } catch (error: Throwable) {
        ResolvedLink.Unreadable(
            ExtractorException(FailureKind.UNKNOWN, error.message ?: error.javaClass.simpleName, error),
        )
    }

    private suspend fun <T> guarded(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: ExtractorException) {
        null
    } catch (_: Throwable) {
        null
    }
}
