package io.github.iseaprojects.rivo.core.menu

import io.github.iseaprojects.rivo.core.config.ConfigKeys
import io.github.iseaprojects.rivo.core.menu.extension.UiExtensionSource

/**
 * Resolves declarative menus for a frame and dispatches leaf actions.
 *
 * Resolution order matches yt-x: build -> filter -> sort -> inject -> localize.
 * Localization is last so extensions never have to know about languages, and toggles
 * read their checked state from the live config snapshot.
 */
class MenuEngine(
    private val catalog: MenuCatalog,
    private val handlers: MenuHandlerRegistry,
    private val extensions: UiExtensionSource = UiExtensionSource { emptyList() },
    private val onIssue: (String) -> Unit = {},
) {

    fun hasMenu(menuId: String): Boolean = catalog.contains(menuId)

    fun nodes(menuId: String, ctx: MenuContext): List<MenuNode> {
        val definition = catalog[menuId] ?: return placeholder(menuId, ctx)
        val built = definition.build(ctx.rebuild(menuId = menuId))
        val patches = extensions.patchesFor(menuId)
        val patched = MenuPatcher.applyAll(built, patches)
        val unique = LinkedHashMap<String, MenuNode>()
        patched.forEach { node ->
            if (unique.putIfAbsent(node.id, node) != null) {
                onIssue("menu $menuId: duplicate node id '${node.id}' (later entry dropped)")
            }
        }
        return unique.values.map { localize(it, ctx) }
    }

    /**
     * Menus owned by later phases (media actions, channel tabs, downloads) are not in
     * the F1 catalog yet. Instead of an empty screen the frame explains itself and
     * names the payload, which also covers extension nodes pointing at an unknown
     * `targetMenuId`.
     */
    private fun placeholder(menuId: String, ctx: MenuContext): List<MenuNode> {
        val payload = ctx.payload
        val detail = when (payload) {
            is MenuPayload.Url -> payload.url
            is MenuPayload.Search -> payload.query
            is MenuPayload.Feed -> payload.kind.name.lowercase()
            is MenuPayload.Library -> payload.kind.name.lowercase()
            is MenuPayload.ConfigValue -> payload.key
            is MenuPayload.Text -> payload.value
            is MenuPayload.Media -> payload.item.title
            is MenuPayload.Playlist -> payload.summary.title
            is MenuPayload.LocalPlaylist -> payload.title
            is MenuPayload.Channel -> payload.summary.name
            is MenuPayload.Download -> payload.id
            is MenuPayload.DownloadFile -> payload.id
            is MenuPayload.Command -> payload.id
            is MenuPayload.CommandDraft -> payload.name.ifBlank { payload.url }
            is MenuPayload.SiteActionRef -> "${payload.siteId}/${payload.actionId}"
            MenuPayload.None -> menuId
        }
        return listOf(
            localize(
                MenuNode(
                    id = "app.pending",
                    labelKey = "TXT_ANDROID_HANDLER_MISSING",
                    detail = detail,
                    iconKey = "TXT_ICON_MENU_MISC_BACK",
                    kind = MenuNodeKind.INFO,
                    enabled = false,
                    tone = MenuTone.MUTED,
                ),
                ctx,
            ),
        )
    }

    fun title(menuId: String, ctx: MenuContext): String? {
        // A playlist's contents are named after the playlist, not after the row that
        // opened them ("Open playlist"): the payload is the only thing that knows.
        if (menuId == MenuIds.LOCAL_PLAYLIST) {
            return (ctx.payload as? MenuPayload.LocalPlaylist)?.title
        }
        val definition = catalog[menuId] ?: return null
        definition.titleKey?.let { return ctx.strings.get(it) }
        return definition.title
    }

    suspend fun dispatch(ctx: MenuContext, node: MenuNode): MenuResult {
        node.targetMenuId?.let { target ->
            // A catalog menu re-resolves its own title on every render, so a language
            // change relabels the header and breadcrumb. Menus without a catalog
            // definition (extension-provided) keep the clicked node's label.
            return MenuResult.Open(
                menuId = target,
                payload = node.payload,
                title = if (catalog[target]?.titleKey != null) "" else node.label.takeIf { it.isNotBlank() },
            )
        }
        if (node.children.isNotEmpty()) {
            return MenuResult.Open(
                menuId = node.id,
                payload = node.payload,
                title = node.label.takeIf { it.isNotBlank() },
                nodes = node.children,
            )
        }
        val handlerId = node.handler ?: return MenuResult.Unhandled(node.id)
        val handler = handlers.handlerFor(handlerId)
            ?: return MenuResult.Unhandled(handlerId)
        return try {
            handler.handle(ctx, node)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            onIssue("handler $handlerId failed: ${error.message}")
            MenuResult.Message(error.message ?: handlerId, isError = true)
        }
    }

    /** Re-localizes an already-built node list (inline extension submenus, cached frames). */
    fun localize(nodes: List<MenuNode>, ctx: MenuContext): List<MenuNode> =
        nodes.map { localize(it, ctx) }

    private fun localize(node: MenuNode, ctx: MenuContext): MenuNode {
        val nerdFonts = ctx.nerdFontAvailable && ctx.config.bool(ConfigKeys.NERD_FONTS, true)
        val label = node.labelKey?.let { key -> ctx.strings.get(key) ?: key } ?: node.label
        val icon = if (nerdFonts) {
            node.iconKey?.let { key -> ctx.strings.get(key) } ?: node.icon
        } else {
            IconCatalog.asciiFor(node)
        }
        val detail = node.detailKey?.let { ctx.strings.get(it) } ?: node.detail
        val checked = node.toggleKey?.let { key -> ctx.config.bool(key, node.checked ?: false) }
            ?: node.checked
        return node.copy(
            label = label.ifBlank { node.id },
            icon = icon,
            detail = detail,
            checked = checked,
            children = node.children.map { localize(it, ctx) },
        )
    }
}
