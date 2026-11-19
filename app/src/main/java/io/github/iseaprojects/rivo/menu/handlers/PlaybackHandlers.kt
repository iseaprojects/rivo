package io.github.iseaprojects.rivo.menu.handlers

import android.content.Context
import io.github.iseaprojects.rivo.core.menu.MenuContext
import io.github.iseaprojects.rivo.core.menu.MenuHandler
import io.github.iseaprojects.rivo.core.menu.MenuNode
import io.github.iseaprojects.rivo.core.menu.MenuPayload
import io.github.iseaprojects.rivo.core.menu.MenuResult
import io.github.iseaprojects.rivo.player.LaunchOutcome
import io.github.iseaprojects.rivo.player.describe
import io.github.iseaprojects.rivo.player.PlayMode
import io.github.iseaprojects.rivo.player.PlaybackActions

/**
 * Player actions: the four ways yt-x starts playback plus the two handoffs.
 *
 * Watch/Listen on a queue row open the item alone; Play All/Listen All on a playlist open
 * a queue. Mix is a playlist whose id is derived from the seed video, so it takes the same
 * path. Every failure is reported with a language-pack key rather than swallowed, because
 * a tap that does nothing is the one behaviour the port refuses to ship.
 */
object PlaybackHandlers {

    fun watch(appContext: Context, playback: PlaybackActions): MenuHandler =
        play(appContext, playback, PlayMode.VIDEO)

    fun listen(appContext: Context, playback: PlaybackActions): MenuHandler =
        play(appContext, playback, PlayMode.AUDIO)

    fun playAll(appContext: Context, playback: PlaybackActions): MenuHandler =
        playQueue(appContext, playback, PlayMode.VIDEO)

    fun listenAll(appContext: Context, playback: PlaybackActions): MenuHandler =
        playQueue(appContext, playback, PlayMode.AUDIO)

    fun mix(appContext: Context, playback: PlaybackActions): MenuHandler =
        MenuHandler { ctx, node ->
            val media = (node.payload as? MenuPayload.Media)?.item
                ?: return@MenuHandler MenuResult.Unhandled("player.mix")
            report(ctx, playback.playMix(appContext, media, PlayMode.VIDEO))
        }

    fun external(appContext: Context, playback: PlaybackActions): MenuHandler =
        MenuHandler { ctx, node ->
            when (val payload = node.payload) {
                is MenuPayload.Media -> report(
                    ctx,
                    playback.sendToExternal(appContext, payload.item, PlayMode.VIDEO),
                )

                is MenuPayload.Url -> report(
                    ctx,
                    playback.sendToExternal(appContext, payload.url, PlayMode.VIDEO),
                )

                else -> MenuResult.Unhandled("player.external")
            }
        }

    fun shell(appContext: Context, playback: PlaybackActions): MenuHandler =
        MenuHandler { ctx, node ->
            when (val payload = node.payload) {
                is MenuPayload.Media -> report(
                    ctx,
                    playback.openShell(appContext, payload.item),
                )

                is MenuPayload.Url -> report(
                    ctx,
                    playback.openShellForUrl(appContext, payload.url),
                )

                else -> MenuResult.Unhandled("player.shell")
            }
        }

    private fun play(
        appContext: Context,
        playback: PlaybackActions,
        mode: PlayMode,
    ): MenuHandler = MenuHandler { ctx, node ->
        when (val payload = node.payload) {
            is MenuPayload.Media -> report(ctx, playback.play(appContext, payload.item, mode))
            is MenuPayload.Url -> report(ctx, playback.playUrl(appContext, payload.url, mode))
            else -> MenuResult.Unhandled("player")
        }
    }

    private fun playQueue(
        appContext: Context,
        playback: PlaybackActions,
        mode: PlayMode,
    ): MenuHandler = MenuHandler { ctx, node ->
        when (val payload = node.payload) {
            is MenuPayload.Playlist -> report(
                ctx,
                playback.playPlaylist(appContext, payload.summary, mode),
            )

            // A video row can still be the source of a Mix queue.
            is MenuPayload.Media -> report(ctx, playback.playMix(appContext, payload.item, mode))
            else -> MenuResult.Unhandled("player.play_all")
        }
    }

    private fun report(ctx: MenuContext, outcome: LaunchOutcome): MenuResult = when (outcome) {
        LaunchOutcome.Started -> MenuResult.Consumed
        is LaunchOutcome.Failed -> MenuResult.Message(
            outcome.describe(ctx.strings),
            isError = true,
        )
    }
}
