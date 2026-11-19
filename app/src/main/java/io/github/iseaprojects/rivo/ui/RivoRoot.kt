package io.github.iseaprojects.rivo.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iseaprojects.rivo.core.config.ConfigKeys
import io.github.iseaprojects.rivo.core.i18n.Strings
import io.github.iseaprojects.rivo.core.launch.LaunchAction
import io.github.iseaprojects.rivo.core.launch.LaunchInbox
import io.github.iseaprojects.rivo.core.launch.LaunchRoute
import io.github.iseaprojects.rivo.core.launch.LaunchRouter
import io.github.iseaprojects.rivo.core.menu.MenuDownloadState
import io.github.iseaprojects.rivo.core.menu.MenuIds
import io.github.iseaprojects.rivo.core.menu.MenuNavController
import io.github.iseaprojects.rivo.core.menu.MenuNode
import io.github.iseaprojects.rivo.core.menu.MenuPayload
import io.github.iseaprojects.rivo.core.menu.MenuResult
import io.github.iseaprojects.rivo.core.ui.KeyBindings
import io.github.iseaprojects.rivo.data.extract.ExtractorException
import io.github.iseaprojects.rivo.di.AppGraph
import io.github.iseaprojects.rivo.player.LaunchOutcome
import io.github.iseaprojects.rivo.player.describe
import io.github.iseaprojects.rivo.player.MediaLinkResolver
import io.github.iseaprojects.rivo.player.PlayMode
import io.github.iseaprojects.rivo.player.ResolvedLink
import io.github.iseaprojects.rivo.ui.media.MediaBrowseScreen
import io.github.iseaprojects.rivo.ui.media.summary
import io.github.iseaprojects.rivo.ui.screens.ConfirmDialog
import io.github.iseaprojects.rivo.ui.screens.Crumb
import io.github.iseaprojects.rivo.ui.screens.ListRow
import io.github.iseaprojects.rivo.ui.screens.MenuRowPreview
import io.github.iseaprojects.rivo.ui.screens.MenuScreen
import io.github.iseaprojects.rivo.ui.screens.TextPromptDialog
import io.github.iseaprojects.rivo.ui.theme.FontRepository
import io.github.iseaprojects.rivo.ui.theme.RivoTheme
import kotlinx.coroutines.launch

/**
 * The whole app is a single Compose tree: navigation is a plain menu stack, not a
 * NavHost per feature, because every screen in yt-x is "a filtered list of entries".
 *
 * Two renderers share the stack. Catalog menus (`main`, `misc`, `config`,
 * `media_actions`, `channel_actions`) become [MenuScreen]s of [MenuNode]s; feed, search,
 * channel and playlist frames are extractor-backed and go through
 * [MediaBrowseScreen]. Both use the same filter, pager, preview and keybindings.
 */
@Composable
fun RivoRoot(
    graph: AppGraph,
    nav: MenuNavController,
    inbox: LaunchInbox,
    onExit: () -> Unit,
) {
    val strings by graph.strings.strings.collectAsStateWithLifecycle()
    val theme by graph.themes.current.collectAsStateWithLifecycle()
    val themes by graph.themes.themes.collectAsStateWithLifecycle()
    val config by graph.runtime.snapshot.collectAsStateWithLifecycle()
    val library by graph.librarySnapshot.collectAsStateWithLifecycle()
    val downloads by graph.downloadSnapshot.collectAsStateWithLifecycle()
    val extensions by graph.extensionsSnapshot.collectAsStateWithLifecycle()
    val updates by graph.updatesSnapshot.collectAsStateWithLifecycle()
    val languages by graph.strings.languageList.collectAsStateWithLifecycle()

    val stack by nav.stack.collectAsStateWithLifecycle()
    val frame = stack.last()

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val router = remember { LaunchRouter() }

    val context = LocalContext.current
    val fonts = remember(context) { FontRepository(context) }

    val fontFamily = remember(config, fonts) {
        val nerd = config.bool(ConfigKeys.NERD_FONTS, true)
        if (nerd) fonts.nerdFontFamily ?: FontFamily.Monospace else FontFamily.Monospace
    }

    // The queue runs in a foreground service, and on Android 13+ its progress is only
    // visible once the notification permission is granted. Ask for it when a download
    // actually starts, not at launch.
    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val downloading = downloads.tasks.any {
        it.state == MenuDownloadState.QUEUED || it.state == MenuDownloadState.RUNNING
    }
    LaunchedEffect(downloading, context) {
        if (!downloading || askedNotifications) return@LaunchedEffect
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            askedNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val menuContext = remember(
        frame, strings, config, theme, themes, nav, library, downloads, extensions, updates,
        languages,
    ) {
        graph.menuContext(
            menuId = frame.menuId,
            payload = frame.payload,
            strings = strings,
            snapshot = config,
            theme = theme,
            themes = themes,
            navigator = nav,
            languages = languages,
            nerdFontAvailable = fonts.hasNerdFont,
            library = library,
            downloads = downloads,
            extensions = extensions,
            updates = updates,
        )
    }

    val isDataScreen = frame.menuId in DATA_MENUS

    val nodes = remember(frame, menuContext) {
        if (isDataScreen) {
            emptyList()
        } else {
            val resolved = frame.nodes
                ?.let { inline -> graph.menus.localize(inline, menuContext) }
                ?: graph.menus.nodes(frame.menuId, menuContext)
            resolved.distinctBy { it.id }
        }
    }

    val title = remember(frame, menuContext) {
        frame.title.ifBlank {
            graph.menus.title(frame.menuId, menuContext)
                ?: strings.text("TXT_ANDROID_BREADCRUMB_ROOT")
        }
    }

    val breadcrumbs = buildList<Crumb> {
        stack.forEachIndexed { index, frame ->
            val label = if (index == 0) {
                strings.text("TXT_ANDROID_BREADCRUMB_ROOT")
            } else {
                frame.title.ifBlank {
                    graph.menus.title(
                        frame.menuId,
                        menuContext.rebuild(menuId = frame.menuId, payload = frame.payload),
                    ) ?: frame.menuId
                }
            }
            // A frame whose label matches the previous one (a playlist's action menu leading
            // into its contents) owns no visible crumb: "Trip / Trip" names one place twice.
            // The index still points at that frame, so the crumb below pops to the right one.
            if (lastOrNull()?.title != label) add(Crumb(label, index))
        }
    }

    val keyBindings = remember(config) {
        KeyBindings.parse(config.string(ConfigKeys.KEYBINDINGS, KeyBindings.DEFAULT_SPEC))
    }
    val previewEnabled = config.bool(ConfigKeys.ENABLE_PREVIEW, true)
    val previewImages = config.bool(ConfigKeys.ENABLE_PREVIEW_IMAGES, true)
    val twoPaneMode = config.string(ConfigKeys.TWO_PANE, "auto").lowercase()

    // -- intent / deep-link handling -------------------------------------------------
    val event by inbox.events.collectAsStateWithLifecycle()
    var handledEventId by rememberSaveable { mutableLongStateOf(-1L) }
    val latestStrings = rememberUpdatedState(strings)

    LaunchedEffect(event) {
        val launchEvent = event ?: return@LaunchedEffect
        if (launchEvent.id == handledEventId) return@LaunchedEffect
        handledEventId = launchEvent.id
        val spec = launchEvent.spec
        if (spec.isEmpty) return@LaunchedEffect

        if (spec.overrides.isNotEmpty()) {
            graph.runtime.apply(spec.overrides)
        }

        val route = router.route(spec)?.let { resolveRoute(it, graph) }
        when (route) {
            null -> Unit
            is ResolvedRoute.Unreadable ->
                snackbar.showSnackbar(route.linkFailure(latestStrings.value))

            is ResolvedRoute.Ready -> {
                val target = route.route
                val handled = runLaunchAction(
                    action = spec.action,
                    route = target,
                    graph = graph,
                    appContext = context.applicationContext,
                    strings = latestStrings.value,
                    snackbar = snackbar,
                )
                if (!handled) {
                    val routeTitle = if (graph.menus.title(target.menuId, menuContext) != null) {
                        ""
                    } else {
                        target.title ?: target.titleKey?.let { latestStrings.value.get(it) } ?: ""
                    }
                    nav.open(target.menuId, target.payload, routeTitle)
                }
            }
        }
    }

    LaunchedEffect(nav) {
        nav.exits.collect { onExit() }
    }

    BackHandler {
        if (!nav.back()) onExit()
    }

    var prompt by remember { mutableStateOf<MenuResult.Prompt?>(null) }
    var confirmation by remember { mutableStateOf<MenuResult.Confirm?>(null) }
    var folderPick by remember { mutableStateOf<MenuResult.PickFolder?>(null) }
    var filePick by remember { mutableStateOf<MenuResult.PickFile?>(null) }
    val latestContext = rememberUpdatedState(menuContext)

    /**
     * Applies one dispatch result. Prompt/Confirm do not execute anything: they park the
     * request in state, and the dialog re-dispatches the *same handler* with the answer,
     * which is how a confirmed destructive action stays stateless.
     */
    val applyResult: suspend (MenuResult, MenuNode) -> Unit = { result, node ->
        when (result) {
            MenuResult.Consumed -> Unit
            is MenuResult.Open -> nav.open(
                menuId = result.menuId,
                payload = result.payload,
                title = result.title ?: node.label,
                nodes = result.nodes,
            )

            is MenuResult.Message -> {
                // Navigate first: showSnackbar suspends until the toast is dismissed, and a
                // row that no longer exists must not stay on screen for four seconds.
                if (result.pop) nav.back()
                snackbar.showSnackbar(result.text)
            }
            MenuResult.Exit -> onExit()
            is MenuResult.Unhandled -> snackbar.showSnackbar(
                strings.text("TXT_ANDROID_HANDLER_MISSING") + ": " + result.handlerId,
            )

            is MenuResult.Prompt -> prompt = result
            is MenuResult.Confirm -> confirmation = result
            // The picker is an activity result, so it parks here and is launched from a
            // composable effect below; the answer re-dispatches the same handler.
            is MenuResult.PickFolder -> folderPick = result
            is MenuResult.PickFile -> filePick = result
        }
    }

    val dispatch: (MenuNode) -> Unit = { node ->
        scope.launch {
            applyResult(graph.menus.dispatch(latestContext.value, node), node)
        }
    }

    val onNode: (MenuNode) -> Unit = { node ->
        val target = node.targetMenuId
        val handler = node.handler
        when {
            target != null -> nav.open(
                menuId = target,
                payload = node.payload,
                // Catalog menus resolve their own (localized) title at render time;
                // only extension-provided menus need the label frozen into the frame.
                title = if (graph.menus.title(target, menuContext) != null) "" else node.label,
            )

            node.children.isNotEmpty() -> nav.open(
                menuId = node.id,
                payload = node.payload,
                title = node.label,
                nodes = node.children,
            )

            handler != null -> dispatch(node)

            else -> scope.launch {
                snackbar.showSnackbar(strings.text("TXT_ANDROID_NO_HANDLER"))
            }
        }
    }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val request = folderPick
        folderPick = null
        if (uri != null && request != null) {
            // The handler persists the grant; doing it here too would duplicate the
            // permission bookkeeping the F6 folder code already owns.
            dispatch(
                MenuNode(
                    id = request.handler,
                    handler = request.handler,
                    payload = request.payload,
                    value = uri.toString(),
                ),
            )
        }
    }

    LaunchedEffect(folderPick) {
        if (folderPick != null) folderLauncher.launch(null)
    }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val request = filePick
        filePick = null
        if (uri != null && request != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            dispatch(
                MenuNode(
                    id = request.handler,
                    handler = request.handler,
                    payload = request.payload,
                    value = uri.toString(),
                ),
            )
        }
    }

    LaunchedEffect(filePick) {
        filePick?.let { request -> fileLauncher.launch(request.mimeTypes.toTypedArray()) }
    }

    RivoTheme(theme = theme, fontFamily = fontFamily) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            // The panel layout is a function of the window, not of the device: a tablet in
            // split screen behaves like a phone, a phone rotated into landscape does not.
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val twoPane = maxWidth >= 720.dp && twoPaneMode !in DISABLED_MODES
                if (isDataScreen) {
                    MediaBrowseScreen(
                        graph = graph,
                        frame = frame,
                        strings = strings,
                        config = config,
                        theme = theme,
                        breadcrumbs = breadcrumbs,
                        canGoBack = stack.size > 1,
                        onBack = { nav.back() },
                        onBreadcrumb = { index -> nav.popTo(index) },
                        onOpen = { menuId, payload, targetTitle ->
                            nav.open(menuId, payload, targetTitle)
                        },
                        snackbarHostState = snackbar,
                        keyBindings = keyBindings,
                        twoPane = twoPane,
                    )
                } else {
                    val rows = remember(nodes) { nodes.map { it.toRow() } }
                    MenuScreen(
                        title = title,
                        breadcrumbs = breadcrumbs,
                        rows = rows,
                        strings = strings,
                        theme = theme,
                        canGoBack = stack.size > 1,
                        onBack = { nav.back() },
                        onBreadcrumb = { index -> nav.popTo(index) },
                        onActivate = { row -> onNode(row.item) },
                        snackbarHostState = snackbar,
                        stateKey = frame.toString(),
                        keyBindings = keyBindings,
                        previewEnabled = previewEnabled,
                        previewImages = previewImages,
                        twoPane = twoPane,
                        preview = { row -> MenuRowPreview(row, strings) },
                    )
                }
            }

            prompt?.let { request ->
                TextPromptDialog(
                    title = request.title,
                    hint = request.hint,
                    initial = request.initial,
                    strings = strings,
                    allowEmpty = request.allowEmpty,
                    onDismiss = { prompt = null },
                ) { text ->
                    prompt = null
                    dispatch(
                        MenuNode(
                            id = request.handler,
                            handler = request.handler,
                            payload = request.payload,
                            value = text,
                        ),
                    )
                }
            }

            confirmation?.let { request ->
                ConfirmDialog(
                    message = request.message,
                    strings = strings,
                    onDismiss = { confirmation = null },
                ) {
                    confirmation = null
                    dispatch(
                        MenuNode(
                            id = request.handler,
                            handler = request.handler,
                            payload = request.payload,
                            value = request.value,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * Turns a URL route into the menu it actually names.
 *
 * `MEDIA_ACTIONS` on a raw URL used to be an empty frame: the actions panel needs a
 * `MediaSummary`, not a string. Resolution happens before navigation, so a shared watch
 * link, playlist link or channel link all land on the right screen. A URL no backend can
 * read keeps the extractor's verdict so the caller can say what went wrong, instead of
 * opening a dead menu or blaming an unrelated setting.
 */
private sealed interface ResolvedRoute {

    data class Ready(val route: LaunchRoute) : ResolvedRoute

    /** [error] is null when the URL does not even name a video, playlist or channel. */
    data class Unreadable(val error: ExtractorException?) : ResolvedRoute
}

private suspend fun resolveRoute(route: LaunchRoute, graph: AppGraph): ResolvedRoute {
    val payload = route.payload as? MenuPayload.Url ?: return ResolvedRoute.Ready(route)
    return when (val target = MediaLinkResolver.resolve(graph.extractors, payload.url)) {
        is ResolvedLink.Video -> ResolvedRoute.Ready(
            route.copy(
                menuId = MenuIds.MEDIA_ACTIONS,
                payload = MenuPayload.Media(target.item),
                title = target.item.title,
            ),
        )

        is ResolvedLink.Playlist -> ResolvedRoute.Ready(
            route.copy(
                menuId = MenuIds.PLAYLIST,
                payload = MenuPayload.Playlist(target.summary),
                title = target.summary.title,
            ),
        )

        is ResolvedLink.Channel -> ResolvedRoute.Ready(
            route.copy(
                menuId = MenuIds.CHANNEL,
                payload = MenuPayload.Channel(target.summary),
                title = target.summary.name,
            ),
        )

        is ResolvedLink.Unreadable -> ResolvedRoute.Unreadable(target.error)
        null -> ResolvedRoute.Unreadable(null)
    }
}

/**
 * The snackbar for a link that led nowhere: the category, then the extractor's first line
 * (a traceback's head is the only part that names the problem).
 */
private fun ResolvedRoute.Unreadable.linkFailure(strings: Strings): String {
    val base = (error?.summary(strings) ?: strings.text("TXT_ANDROID_LINK_UNREADABLE"))
        .trimEnd('.', ':', ' ')
    val detail = error?.message
        ?.lineSequence()
        ?.firstOrNull { it.isNotBlank() }
        ?.trim()
        ?.take(200)
    return listOfNotNull(base, detail).joinToString(": ")
}

/**
 * Runs the `action=` part of a launch spec.
 *
 * Returns true when the action was executed (or explained), which suppresses navigation
 * to the actions menu: `rivo://watch?v=X&action=play` should play, not show a list of
 * buttons. Download actions stay unimplemented until F6 and say so.
 */
private suspend fun runLaunchAction(
    action: LaunchAction,
    route: LaunchRoute,
    graph: AppGraph,
    appContext: Context,
    strings: Strings,
    snackbar: SnackbarHostState,
): Boolean {
    if (action == LaunchAction.NONE) return false
    val payload = route.payload
    val playback = graph.playback

    suspend fun report(outcome: LaunchOutcome) {
        when (outcome) {
            LaunchOutcome.Started -> Unit
            is LaunchOutcome.Failed -> snackbar.showSnackbar(outcome.describe(strings))
        }
    }

    when (action) {
        LaunchAction.PLAY, LaunchAction.LISTEN, LaunchAction.PLAY_ALL, LaunchAction.LISTEN_ALL -> {
            val mode = when (action) {
                LaunchAction.LISTEN, LaunchAction.LISTEN_ALL -> PlayMode.AUDIO
                else -> PlayMode.VIDEO
            }
            when (payload) {
                is MenuPayload.Media -> report(playback.play(appContext, payload.item, mode))
                is MenuPayload.Playlist -> report(
                    playback.playPlaylist(appContext, payload.summary, mode),
                )

                else -> return false
            }
        }

        LaunchAction.SAVE -> {
            val media = (payload as? MenuPayload.Media)?.item ?: return false
            val added = graph.library.save(media)
            snackbar.showSnackbar(
                strings.text(
                    if (added) "TXT_ANDROID_SAVED_TO_LIBRARY" else "TXT_ANDROID_ALREADY_SAVED",
                ),
            )
        }

        LaunchAction.SAVE_PLAYLIST -> {
            val playlist = (payload as? MenuPayload.Playlist)?.summary ?: return false
            val added = graph.library.savePlaylist(playlist)
            snackbar.showSnackbar(
                strings.text(
                    if (added) "TXT_ANDROID_PLAYLIST_SAVED" else "TXT_ANDROID_ALREADY_SAVED",
                ),
            )
        }

        LaunchAction.DOWNLOAD, LaunchAction.DOWNLOAD_AUDIO, LaunchAction.DOWNLOAD_ALL -> {
            snackbar.showSnackbar(
                strings.text("TXT_ANDROID_HANDLER_MISSING") + ": " + action.name.lowercase(),
            )
        }

        LaunchAction.NONE -> return false
    }
    return true
}

/** Frames served by the extractor layer or the local library instead of a node list. */
private val DATA_MENUS = setOf(    MenuIds.FEED,
    MenuIds.SEARCH,
    MenuIds.CHANNEL,
    MenuIds.PLAYLIST,
    MenuIds.LIBRARY,
    MenuIds.LOCAL_PLAYLIST,
)

private val DISABLED_MODES = setOf("false", "off", "never", "0", "no")

private fun MenuNode.toRow(): ListRow<MenuNode> = ListRow(
    id = id,
    item = this,
    label = label,
    detail = detail,
    icon = icon,
    badge = badge,
    hint = keyHint,
    tone = tone,
    enabled = enabled,
    checked = checked,
    submenu = isSubmenu,
)
