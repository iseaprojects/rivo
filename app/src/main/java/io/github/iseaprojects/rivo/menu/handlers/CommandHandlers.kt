package io.github.iseaprojects.rivo.menu.handlers

import android.content.Context
import io.github.iseaprojects.rivo.core.commands.CustomCommandStore
import io.github.iseaprojects.rivo.core.menu.HandlerIds
import io.github.iseaprojects.rivo.core.menu.MenuContext
import io.github.iseaprojects.rivo.core.menu.MenuHandler
import io.github.iseaprojects.rivo.core.menu.MenuIds
import io.github.iseaprojects.rivo.core.menu.MenuNode
import io.github.iseaprojects.rivo.core.menu.MenuPayload
import io.github.iseaprojects.rivo.core.menu.MenuResult
import io.github.iseaprojects.rivo.core.menu.extension.SiteTemplate
import io.github.iseaprojects.rivo.data.extract.ExtractorRouter
import io.github.iseaprojects.rivo.player.LaunchOutcome
import io.github.iseaprojects.rivo.player.MediaLinkResolver
import io.github.iseaprojects.rivo.player.ResolvedLink
import io.github.iseaprojects.rivo.player.TermuxShell
import io.github.iseaprojects.rivo.player.describe
import io.github.iseaprojects.rivo.ui.media.summary

/**
 * Custom commands and declarative site actions.
 *
 * yt-x stores commands as `name`, `url` and extra yt-dlp options, then runs yt-dlp against
 * the URL. The port keeps the same data and does the same thing with the extractor that
 * actually ships on the device: the URL is resolved into a video, playlist or channel and
 * the matching menu opens. That makes a command work in both flavors, including the
 * fast build where yt-dlp is not present.
 */
object CommandHandlers {

    /** Stages 0-2 create a command; 10-12 edit one. Shared so rotation is harmless. */
    private const val CREATE_NAME = 0
    private const val CREATE_URL = 1
    private const val CREATE_OPTIONS = 2
    private const val EDIT_NAME = 10
    private const val EDIT_URL = 11
    private const val EDIT_OPTIONS = 12

    fun create(store: CustomCommandStore): MenuHandler = MenuHandler { ctx, node ->
        val draft = node.payload as? MenuPayload.CommandDraft
            ?: MenuPayload.CommandDraft(stage = CREATE_NAME)
        when (draft.stage) {
            CREATE_NAME -> {
                val name = node.value
                if (name == null) {
                    MenuResult.Prompt(
                        title = ctx.strings.text("TXT_ANDROID_COMMAND_NAME"),
                        hint = ctx.strings.text("TXT_ANDROID_COMMAND_NAME_HINT"),
                        handler = HandlerIds.CUSTOM_CMD_NEW,
                        payload = draft,
                    )
                } else if (name.isBlank()) {
                    MenuResult.Message(
                        ctx.strings.text("TXT_ANDROID_COMMAND_NAME_REQUIRED"),
                        isError = true,
                    )
                } else {
                    MenuResult.Prompt(
                        title = ctx.strings.text("TXT_ANDROID_COMMAND_URL"),
                        hint = "https://",
                        handler = HandlerIds.CUSTOM_CMD_NEW,
                        payload = draft.copy(stage = CREATE_URL, name = name.trim()),
                    )
                }
            }

            CREATE_URL -> {
                val url = node.value
                if (url.isNullOrBlank()) {
                    MenuResult.Message(
                        ctx.strings.text("TXT_ANDROID_COMMAND_URL_REQUIRED"),
                        isError = true,
                    )
                } else {
                    MenuResult.Prompt(
                        title = ctx.strings.text("TXT_ANDROID_COMMAND_OPTIONS"),
                        hint = ctx.strings.text("TXT_ANDROID_COMMAND_OPTIONS_HINT"),
                        handler = HandlerIds.CUSTOM_CMD_NEW,
                        payload = draft.copy(stage = CREATE_OPTIONS, url = url.trim()),
                        allowEmpty = true,
                    )
                }
            }

            CREATE_OPTIONS -> {
                val saved = store.add(draft.name, draft.url, node.value.orEmpty())
                if (saved == null) {
                    MenuResult.Message(
                        ctx.strings.text("TXT_ANDROID_COMMAND_SAVE_FAILED"),
                        isError = true,
                    )
                } else {
                    MenuResult.Message(
                        ctx.strings.format("TXT_ANDROID_COMMAND_ADDED_FMT", saved.name),
                    )
                }
            }

            else -> MenuResult.Message(
                ctx.strings.text("TXT_ANDROID_COMMAND_SAVE_FAILED"),
                isError = true,
            )
        }
    }

    fun edit(store: CustomCommandStore): MenuHandler = MenuHandler { ctx, node ->
        val draft = node.payload as? MenuPayload.CommandDraft ?: run {
            val id = (node.payload as? MenuPayload.Command)?.id
                ?: return@MenuHandler MenuResult.Message(
                    ctx.strings.text("TXT_ANDROID_COMMAND_MISSING"),
                    isError = true,
                    pop = true,
                )
            val command = store.find(id)
                ?: return@MenuHandler MenuResult.Message(
                    ctx.strings.text("TXT_ANDROID_COMMAND_MISSING"),
                    isError = true,
                    pop = true,
                )
            return@MenuHandler MenuResult.Prompt(
                title = ctx.strings.text("TXT_ANDROID_COMMAND_NAME"),
                hint = ctx.strings.text("TXT_ANDROID_COMMAND_NAME_HINT"),
                initial = command.name,
                handler = HandlerIds.CUSTOM_CMD_EDIT,
                payload = MenuPayload.CommandDraft(
                    stage = EDIT_NAME,
                    id = command.id,
                    name = command.name,
                    url = command.url,
                    options = command.options,
                ),
            )
        }

        when (draft.stage) {
            EDIT_NAME -> {
                val name = node.value
                if (name.isNullOrBlank()) {
                    MenuResult.Message(
                        ctx.strings.text("TXT_ANDROID_COMMAND_NAME_REQUIRED"),
                        isError = true,
                    )
                } else {
                    MenuResult.Prompt(
                        title = ctx.strings.text("TXT_ANDROID_COMMAND_URL"),
                        hint = "https://",
                        initial = draft.url,
                        handler = HandlerIds.CUSTOM_CMD_EDIT,
                        payload = draft.copy(stage = EDIT_URL, name = name.trim()),
                    )
                }
            }

            EDIT_URL -> {
                val url = node.value
                if (url.isNullOrBlank()) {
                    MenuResult.Message(
                        ctx.strings.text("TXT_ANDROID_COMMAND_URL_REQUIRED"),
                        isError = true,
                    )
                } else {
                    MenuResult.Prompt(
                        title = ctx.strings.text("TXT_ANDROID_COMMAND_OPTIONS"),
                        hint = ctx.strings.text("TXT_ANDROID_COMMAND_OPTIONS_HINT"),
                        initial = draft.options,
                        handler = HandlerIds.CUSTOM_CMD_EDIT,
                        payload = draft.copy(stage = EDIT_OPTIONS, url = url.trim()),
                        allowEmpty = true,
                    )
                }
            }

            EDIT_OPTIONS -> {
                val id = draft.id
                if (id == null) {
                    MenuResult.Message(
                        ctx.strings.text("TXT_ANDROID_COMMAND_SAVE_FAILED"),
                        isError = true,
                    )
                } else if (store.update(id, draft.name, draft.url, node.value.orEmpty())) {
                    MenuResult.Message(
                        ctx.strings.format("TXT_ANDROID_COMMAND_UPDATED_FMT", draft.name),
                        pop = true,
                    )
                } else {
                    MenuResult.Message(
                        ctx.strings.text("TXT_ANDROID_COMMAND_SAVE_FAILED"),
                        isError = true,
                    )
                }
            }

            else -> MenuResult.Message(
                ctx.strings.text("TXT_ANDROID_COMMAND_SAVE_FAILED"),
                isError = true,
            )
        }
    }

    fun delete(store: CustomCommandStore): MenuHandler = MenuHandler { ctx, node ->
        val id = (node.payload as? MenuPayload.Command)?.id
            ?: return@MenuHandler MenuResult.Message(
                ctx.strings.text("TXT_ANDROID_COMMAND_MISSING"),
                isError = true,
                pop = true,
            )
        if (node.value == null) {
            return@MenuHandler MenuResult.Confirm(
                message = ctx.strings.text("TXT_ANDROID_COMMAND_DELETE_CONFIRM"),
                handler = HandlerIds.CUSTOM_CMD_DELETE,
                payload = node.payload,
                value = "confirmed",
            )
        }
        if (store.remove(id)) {
            MenuResult.Message(
                ctx.strings.text("TXT_ANDROID_COMMAND_DELETED"),
                pop = true,
            )
        } else {
            MenuResult.Message(
                ctx.strings.text("TXT_ANDROID_COMMAND_MISSING"),
                isError = true,
            )
        }
    }

    fun run(router: ExtractorRouter): MenuHandler = MenuHandler { ctx, node ->
        val command = commandOf(ctx, node)
            ?: return@MenuHandler MenuResult.Message(
                ctx.strings.text("TXT_ANDROID_COMMAND_MISSING"),
                isError = true,
                pop = true,
            )
        openDestination(router, ctx, command.url)
    }

    fun shell(context: Context, router: ExtractorRouter): MenuHandler = MenuHandler { ctx, node ->
        val command = commandOf(ctx, node)
            ?: return@MenuHandler MenuResult.Message(
                ctx.strings.text("TXT_ANDROID_COMMAND_MISSING"),
                isError = true,
                pop = true,
            )
        val outcome = TermuxShell.open(
            context = context,
            url = command.url,
            title = command.name,
            videoId = command.id,
            streamUrl = null,
            options = command.options.takeIf { it.isNotBlank() },
        )
        when (outcome) {
            LaunchOutcome.Started -> MenuResult.Consumed
            is LaunchOutcome.Failed -> MenuResult.Message(
                outcome.describe(ctx.strings),
                isError = true,
            )
        }
    }

    /**
     * One row per site action. Actions with a `prompt` ask first; the answer is URL-encoded
     * into `{query}` and nothing else in the template is expanded.
     */
    fun site(router: ExtractorRouter): MenuHandler = MenuHandler { ctx, node ->
        val reference = node.payload as? MenuPayload.SiteActionRef
            ?: return@MenuHandler MenuResult.Message(
                ctx.strings.text("TXT_ANDROID_SITES_EMPTY"),
                isError = true,
            )
        val (site, action) = ctx.extensions.findSiteAction(reference.siteId, reference.actionId)
            ?: return@MenuHandler MenuResult.Message(
                ctx.strings.text("TXT_ANDROID_SITES_EMPTY"),
                isError = true,
                pop = true,
            )
        if (!action.prompt.isNullOrBlank() && node.value == null) {
            return@MenuHandler MenuResult.Prompt(
                title = action.prompt,
                hint = action.promptHint ?: ctx.strings.text("TXT_ANDROID_SITE_QUERY_HINT"),
                handler = HandlerIds.SITE_RUN,
                payload = node.payload,
            )
        }
        val url = SiteTemplate.expand(action.urlTemplate, node.value)
        openDestination(router, ctx, url)
    }

    private fun commandOf(ctx: MenuContext, node: MenuNode): io.github.iseaprojects.rivo.core.menu.MenuCommand? =
        when (val payload = node.payload) {
            is MenuPayload.Command -> ctx.extensions.findCommand(payload.id)
            is MenuPayload.CommandDraft -> payload.id?.let(ctx.extensions::findCommand)
            else -> null
        }

    /**
     * Resolves a command/site URL and pushes the screen it names. A site action that is
     * not media (a search page with no extractor support) still opens the actions menu for
     * the resolved video; an unreadable URL reports the backend's verdict.
     */
    private suspend fun openDestination(
        router: ExtractorRouter,
        ctx: MenuContext,
        url: String,
    ): MenuResult = when (val resolved = MediaLinkResolver.resolve(router, url)) {
        is ResolvedLink.Video -> MenuResult.Open(
            menuId = MenuIds.MEDIA_ACTIONS,
            payload = MenuPayload.Media(resolved.item),
            title = resolved.item.title,
        )

        is ResolvedLink.Playlist -> MenuResult.Open(
            menuId = MenuIds.PLAYLIST,
            payload = MenuPayload.Playlist(resolved.summary),
            title = resolved.summary.title,
        )

        is ResolvedLink.Channel -> MenuResult.Open(
            menuId = MenuIds.CHANNEL,
            payload = MenuPayload.Channel(resolved.summary),
            title = resolved.summary.name,
        )

        is ResolvedLink.Unreadable -> MenuResult.Message(
            resolved.error.summary(ctx.strings).trimEnd('.', ':', ' '),
            isError = true,
        )

        null -> MenuResult.Message(
            ctx.strings.text("TXT_ANDROID_LINK_UNREADABLE"),
            isError = true,
        )
    }
}
