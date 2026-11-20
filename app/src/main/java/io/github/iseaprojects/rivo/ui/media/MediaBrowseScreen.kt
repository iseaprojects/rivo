package io.github.iseaprojects.rivo.ui.media

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.iseaprojects.rivo.core.config.ConfigKeys
import io.github.iseaprojects.rivo.core.config.ConfigSnapshot
import io.github.iseaprojects.rivo.core.i18n.Strings
import io.github.iseaprojects.rivo.core.menu.MediaCollection
import io.github.iseaprojects.rivo.core.menu.MenuFrame
import io.github.iseaprojects.rivo.core.menu.MenuIds
import io.github.iseaprojects.rivo.core.menu.MenuPayload
import io.github.iseaprojects.rivo.core.model.FeedKind
import io.github.iseaprojects.rivo.core.model.LibraryKeys
import io.github.iseaprojects.rivo.core.model.LibraryKind
import io.github.iseaprojects.rivo.core.model.SearchKind
import io.github.iseaprojects.rivo.core.search.SearchFilters
import io.github.iseaprojects.rivo.core.ui.Fuzzy
import io.github.iseaprojects.rivo.core.theme.ThemeSpec
import io.github.iseaprojects.rivo.core.ui.KeyBindings
import io.github.iseaprojects.rivo.data.extract.ChannelTab
import io.github.iseaprojects.rivo.data.extract.ChannelTabKind
import io.github.iseaprojects.rivo.data.library.LibraryLimits
import io.github.iseaprojects.rivo.di.AppGraph
import io.github.iseaprojects.rivo.ui.screens.Crumb
import io.github.iseaprojects.rivo.ui.screens.ListPager
import io.github.iseaprojects.rivo.ui.screens.ListStatus
import io.github.iseaprojects.rivo.ui.screens.MenuScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Everything a data screen needs that does not change while it is on top of the stack. */
private class BrowseEnv(
    val graph: AppGraph,
    val strings: Strings,
    val theme: ThemeSpec,
    val config: ConfigSnapshot,
    val frameTitle: String,
    val breadcrumbs: List<Crumb>,
    val canGoBack: Boolean,
    val onBack: () -> Unit,
    val onBreadcrumb: (Int) -> Unit,
    val onOpen: (String, MenuPayload, String) -> Unit,
    val snackbar: SnackbarHostState,
    val keyBindings: KeyBindings,
    val twoPane: Boolean,
    val previewEnabled: Boolean,
    val previewImages: Boolean,
)

/**
 * The four extractor-backed screens: feed, search, channel and playlist.
 *
 * They share [MediaScaffold], so filtering, paging, preview, error handling and the
 * keybindings behave identically on all of them; only the source and the header chips
 * differ. This is the F3 replacement for the terminal's "fetch a list, render it, drop
 * into fzf" loop.
 */
@Composable
fun MediaBrowseScreen(
    graph: AppGraph,
    frame: MenuFrame,
    strings: Strings,
    config: ConfigSnapshot,
    theme: ThemeSpec,
    breadcrumbs: List<Crumb>,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onBreadcrumb: (Int) -> Unit,
    onOpen: (String, MenuPayload, String) -> Unit,
    snackbarHostState: SnackbarHostState,
    keyBindings: KeyBindings,
    twoPane: Boolean,
) {
    val env = BrowseEnv(
        graph = graph,
        strings = strings,
        theme = theme,
        config = config,
        frameTitle = frame.title,
        breadcrumbs = breadcrumbs,
        canGoBack = canGoBack,
        onBack = onBack,
        onBreadcrumb = onBreadcrumb,
        onOpen = onOpen,
        snackbar = snackbarHostState,
        keyBindings = keyBindings,
        twoPane = twoPane,
        previewEnabled = config.bool(ConfigKeys.ENABLE_PREVIEW, true),
        previewImages = config.bool(ConfigKeys.ENABLE_PREVIEW_IMAGES, true),
    )
    val frameKey = frame.toString()

    when (frame.menuId) {
        MenuIds.SEARCH -> SearchBrowse(env, frameKey, frame.payload as? MenuPayload.Search)
        MenuIds.CHANNEL -> ChannelBrowse(env, frameKey, frame.payload as? MenuPayload.Channel)
        MenuIds.PLAYLIST -> PlaylistBrowse(env, frameKey, frame.payload as? MenuPayload.Playlist)
        MenuIds.LIBRARY -> LibraryBrowse(env, frameKey, frame.payload as? MenuPayload.Library)
        MenuIds.LOCAL_PLAYLIST ->
            LocalPlaylistBrowse(env, frameKey, frame.payload as? MenuPayload.LocalPlaylist)
        else -> FeedBrowse(env, frameKey, (frame.payload as? MenuPayload.Feed)?.kind ?: FeedKind.HOME)
    }
}

@Composable
private fun SearchBrowse(env: BrowseEnv, frameKey: String, payload: MenuPayload.Search?) {
    val strings = env.strings
    val kind = payload?.kind ?: SearchKind.VIDEOS
    val channelUrl = payload?.channelUrl
    var query by rememberSaveable(frameKey) { mutableStateOf(payload?.query.orEmpty()) }
    var submitted by rememberSaveable(frameKey) { mutableStateOf(payload?.query.orEmpty()) }
    var filterToken by rememberSaveable(frameKey) { mutableStateOf(payload?.filterToken) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()

    val state = rememberMediaList(env, frameKey)
    val source = remember(submitted, filterToken, kind, channelUrl) {
        MediaSource.Search(submitted.trim(), kind, filterToken, channelUrl)
    }
    LaunchedEffect(source) {
        if (submitted.isNotBlank()) state.open(source)
    }

    /**
     * Runs one query. Upstream's grammar allows one leading or trailing `:token`
     * (`:4k`, `:today`, `:newest`); the token becomes the active chip and the rest is the
     * query. A prompt that is only a token refines the previous query, and the box is
     * cleared afterwards so the submitted text never doubles as a row filter.
     */
    fun applyQuery(value: String) {
        val parsed = if (channelUrl == null) SearchFilters.parse(value) else null
        parsed?.filter?.let { filterToken = it.token }
        val effective = (parsed?.query ?: value).ifBlank { submitted.trim() }
        if (effective.isBlank()) return
        submitted = effective
        query = ""
        if (env.config.bool(ConfigKeys.ENABLE_SEARCH_HISTORY, true)) {
            scope.launch {
                env.graph.library.recordSearch(effective, LibraryLimits.SEARCH_HISTORY)
            }
        }
    }

    /**
     * `!1`, `!2` … recall a previous query, like upstream's prompt does. The index is
     * resolved against the stored history rather than a cached copy, so it stays correct
     * after a delete in the history screen.
     */
    fun submit(raw: String) {
        val typed = raw.trim()
        if (typed.isEmpty()) return
        val recall = if (channelUrl == null) LibraryKeys.recallIndex(typed) else null
        if (recall == null) {
            applyQuery(typed)
            return
        }
        scope.launch {
            val entry = env.graph.library.recall(LibraryLimits.SEARCH_HISTORY).getOrNull(recall)
            if (entry == null) {
                env.snackbar.showSnackbar(strings.text("TXT_ANDROID_HISTORY_EMPTY_ENTRY"))
            } else {
                applyQuery(entry)
            }
        }
    }

    // Suggestions are best-effort: a blocked or slow endpoint must not disturb typing.
    // A channel search has its own completion source upstream; until that is wired, the
    // box stays quiet instead of offering global results that would leave the channel.
    //
    // The user's own history comes first, like the upstream prompt: typing "lof" offers the
    // "lofi" queries that were run before, then whatever the extractor suggests.
    LaunchedEffect(query, channelUrl) {
        val text = query.trim()
        if (channelUrl != null || text.length < 2) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        val needle = Fuzzy.fold(text)
        val history = runCatching { env.graph.library.recall(LibraryLimits.SEARCH_HISTORY) }
            .getOrDefault(emptyList())
            .filter { suggestion ->
                // Same folding as the menu filter: typing "cancion" must recall "canción".
                val folded = Fuzzy.fold(suggestion.trim())
                folded.startsWith(needle) && folded != needle
            }
        val remote = runCatching { env.graph.extractors.default().suggestions(text) }
            .getOrDefault(emptyList())
        suggestions = (history.take(3) + remote).distinct().take(8)
    }

    val open = mediaActivator(env)

    MediaScaffold(
        env = env,
        state = state,
        title = if (submitted.isBlank()) {
            if (channelUrl != null) {
                strings.text("TXT_MENU_CHANNEL_ACTIONS_SEARCH")
            } else {
                env.frameTitle.ifBlank { strings.text("TXT_MENU_MAIN_SEARCH") }
            }
        } else {
            submitted
        },
        stateKey = frameKey,
        // The box is the query editor, not a row filter: Enter — or tapping a row while
        // text is typed — runs the search (or appends a `:token`) and clears the box, so
        // paged results are never post-filtered by the text that produced them. The
        // submitted query stays in the title.
        onActivate = { entry ->
            if (query.isNotBlank()) submit(query) else open(entry)
        },
        filterValue = query,
        onFilterChange = { query = it },
        onSubmit = { submit(query) },
        emptyText = when {
            submitted.isNotBlank() -> null
            channelUrl != null -> strings.text("TXT_MENU_CHANNEL_ACTIONS_SEARCH_PROMPT")
            else -> strings.text("TXT_ANDROID_SEARCH_HINT_INLINE")
        },
        header = {
            Column {
                if (channelUrl == null) {
                    SearchFilterRow(
                        activeToken = filterToken,
                        strings = strings,
                    ) { filter ->
                        filterToken = if (filterToken == filter.token) null else filter.token
                        if (query.isNotBlank()) {
                            submitted = query.trim()
                            query = ""
                        }
                    }
                }
                if (suggestions.isNotEmpty()) {
                    SuggestionRow(suggestions = suggestions) { picked ->
                        query = picked
                        submit(picked)
                        suggestions = emptyList()
                    }
                }
            }
        },
        actions = {
            IconButton(onClick = { submit(query) }) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = strings.text("TXT_MENU_MAIN_SEARCH"),
                )
            }
        },
    )
}

@Composable
private fun ChannelBrowse(env: BrowseEnv, frameKey: String, payload: MenuPayload.Channel?) {
    if (payload == null) {
        FeedBrowse(env, frameKey, FeedKind.HOME)
        return
    }
    var tabName by rememberSaveable(frameKey) { mutableStateOf(payload.tab) }
    val tabKind = tabName?.let { name -> runCatching { ChannelTabKind.valueOf(name) }.getOrNull() }
    val state = rememberMediaList(env, frameKey)
    val source = remember(payload.summary.url, tabKind) {
        MediaSource.Channel(payload.summary.url, tabKind)
    }
    LaunchedEffect(source) { state.open(source) }

    val tabs = state.channel?.tabs.orEmpty()
    val channelName = state.channel?.summary?.name?.takeIf { it.isNotBlank() } ?: payload.summary.name
    val open = mediaActivator(env)
    MediaScaffold(
        env = env,
        state = state,
        title = channelName,
        stateKey = frameKey,
        onActivate = open,
        header = {
            if (tabs.isNotEmpty()) {
                ChannelTabRow(
                    tabs = tabs,
                    active = tabKind,
                    strings = env.strings,
                ) { tab: ChannelTab -> tabName = tab.kind.name }
            }
        },
        actions = {
            // Upstream "Search" in menu_channel_actions: a fresh search frame scoped to
            // this channel, so the channel list itself keeps its local fuzzy filter.
            IconButton(
                onClick = {
                    env.onOpen(
                        MenuIds.SEARCH,
                        MenuPayload.Search(
                            query = "",
                            channelUrl = payload.summary.url,
                        ),
                        channelName,
                    )
                },
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = env.strings.text("TXT_MENU_CHANNEL_ACTIONS_SEARCH"),
                )
            }
        },
    )
}

@Composable
private fun PlaylistBrowse(env: BrowseEnv, frameKey: String, payload: MenuPayload.Playlist?) {
    if (payload == null) {
        FeedBrowse(env, frameKey, FeedKind.HOME)
        return
    }
    val state = rememberMediaList(env, frameKey)
    val source = remember(payload.summary.url) { MediaSource.Playlist(payload.summary.url) }
    LaunchedEffect(source) { state.open(source) }

    val open = mediaActivator(env)
    MediaScaffold(
        env = env,
        state = state,
        title = state.playlist?.summary?.title?.takeIf { it.isNotBlank() } ?: payload.summary.title,
        stateKey = frameKey,
        onActivate = open,
    )
}

@Composable
private fun FeedBrowse(env: BrowseEnv, frameKey: String, kind: FeedKind) {
    val state = rememberMediaList(env, frameKey + ":" + kind.name)
    val source = remember(kind) { MediaSource.Feed(kind) }
    LaunchedEffect(source) { state.open(source) }

    val open = mediaActivator(env)
    MediaScaffold(
        env = env,
        state = state,
        title = feedTitle(kind, env.strings),
        stateKey = frameKey + ":" + kind.name,
        onActivate = open,
    )
}

/**
 * Saved videos and (from F6) downloads. Local rows come from Room, so the list is
 * reloaded when the library changes — unsaving an item from its actions menu updates the
 * list that is still on the back stack.
 */
@Composable
private fun LibraryBrowse(env: BrowseEnv, frameKey: String, payload: MenuPayload.Library?) {
    val kind = payload?.kind ?: LibraryKind.SAVED
    LocalBrowse(
        env = env,
        frameKey = frameKey + ":" + kind.name,
        source = MediaSource.Library(kind),
        title = libraryTitle(kind, env.strings),
        emptyText = libraryEmpty(kind, env.strings),
    )
}

@Composable
private fun LocalPlaylistBrowse(
    env: BrowseEnv,
    frameKey: String,
    payload: MenuPayload.LocalPlaylist?,
) {
    if (payload == null) {
        FeedBrowse(env, frameKey, FeedKind.HOME)
        return
    }
    LocalBrowse(
        env = env,
        frameKey = frameKey + ":" + payload.id,
        source = MediaSource.LocalPlaylist(payload.id),
        title = payload.title,
        emptyText = env.strings.text("TXT_ANDROID_PLAYLIST_EMPTY"),
        collection = payload,
    )
}

/** One local list: repository-backed paging, live reload, per-item actions. */
@Composable
private fun LocalBrowse(
    env: BrowseEnv,
    frameKey: String,
    source: MediaSource,
    title: String,
    emptyText: String?,
    collection: MenuPayload.LocalPlaylist? = null,
) {
    val state = rememberMediaList(env, frameKey)
    LaunchedEffect(source) { state.open(source) }

    val changes = env.graph.library.changes
    LaunchedEffect(source) {
        changes.collect { if (state.loadedOnce && !state.loading) state.retry() }
    }

    val open = mediaActivator(env, collection)
    MediaScaffold(
        env = env,
        state = state,
        title = title,
        stateKey = frameKey,
        onActivate = open,
        emptyText = emptyText,
    )
}

@Composable
private fun MediaScaffold(
    env: BrowseEnv,
    state: MediaListState,
    title: String,
    stateKey: String,
    onActivate: (MediaEntry) -> Unit,
    filterValue: String? = null,
    onFilterChange: ((String) -> Unit)? = null,
    emptyText: String? = null,
    onSubmit: (() -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    val strings = env.strings
    val rows = remember(state.rows, strings) { state.rows.map { it.toListRow(strings) } }
    val failure = state.failure
    val status: ListStatus = when {
        failure != null -> ListStatus.Failed(
            summary = failure.summary(strings),
            detail = failure.message,
            retryable = failure.retryable,
        )
        state.loading && !state.loadedOnce -> ListStatus.Loading
        else -> ListStatus.Ready
    }
    val pager = if (state.loadedOnce && (state.rows.isNotEmpty() || state.hasPrevious)) {
        ListPager(
            page = state.page,
            itemCount = state.rows.size,
            pageCount = state.pageCount,
            totalCount = state.totalCount,
            hasPrevious = state.hasPrevious,
            hasNext = state.hasNext,
            loading = state.loading,
            onPrevious = state::previousPage,
            onNext = state::nextPage,
        )
    } else {
        null
    }

    MenuScreen(
        title = title,
        breadcrumbs = env.breadcrumbs,
        rows = rows,
        strings = strings,
        theme = env.theme,
        canGoBack = env.canGoBack,
        onBack = env.onBack,
        onBreadcrumb = env.onBreadcrumb,
        onActivate = { row -> onActivate(row.item) },
        snackbarHostState = env.snackbar,
        stateKey = stateKey,
        keyBindings = env.keyBindings,
        previewEnabled = env.previewEnabled,
        previewImages = env.previewImages,
        twoPane = env.twoPane,
        status = status,
        pager = pager,
        onRefresh = state::retry,
        onSubmit = onSubmit,
        emptyText = emptyText,
        filterValue = filterValue,
        onFilterChange = onFilterChange,
        header = header,
        actions = actions,
        preview = { row -> MediaPreview(row, strings, env.previewImages) },
    )
}

@Composable
private fun rememberMediaList(env: BrowseEnv, key: String): MediaListState {
    val scope = rememberCoroutineScope()
    val pageSize = rememberUpdatedState(
        env.config.int(ConfigKeys.PER_PAGE, 30).coerceIn(5, 200),
    )
    val loader = remember(env.graph) {
        MediaLoader(env.graph.extractors, env.graph.library, env.graph.downloads)
    }
    val state = remember(key, loader) {
        MediaListState(loader, scope) { pageSize.value }
    }
    DisposableEffect(state) {
        onDispose { state.close() }
    }
    return state
}

/**
 * What tapping a row does.
 *
 * Opening a video is also what makes it "recent": upstream records history when the media
 * actions open, which is why the list fills up before the player exists. Playlists and
 * channels open their action frames (playlist actions include "Open playlist") and are not
 * recorded.
 */
@Composable
private fun mediaActivator(
    env: BrowseEnv,
    collection: MenuPayload.LocalPlaylist? = null,
): (MediaEntry) -> Unit {
    val scope = rememberCoroutineScope()
    val recentLimit = remember(env.config) {
        env.config.int(ConfigKeys.NO_OF_RECENT, 10).coerceIn(1, LibraryLimits.RECENTS_CEILING)
    }
    return remember(env, recentLimit, collection) {
        { entry ->
            when (entry) {
                is MediaEntry.Video -> {
                    scope.launch { env.graph.library.recordRecent(entry.media, recentLimit) }
                    env.onOpen(
                        MenuIds.MEDIA_ACTIONS,
                        MenuPayload.Media(
                            item = entry.media,
                            collection = if (collection != null) {
                                MediaCollection.CUSTOM_PLAYLIST
                            } else {
                                MediaCollection.NONE
                            },
                            collectionId = collection?.id,
                            collectionTitle = collection?.title,
                        ),
                        entry.media.title,
                    )
                }

                is MediaEntry.Playlist -> env.onOpen(
                    // Upstream prompts for playlist actions, and one of them is Save; the
                    // contents are one row away ("Open playlist").
                    MenuIds.MEDIA_ACTIONS,
                    MenuPayload.Playlist(entry.playlist),
                    entry.playlist.title,
                )

                is MediaEntry.Channel -> env.onOpen(
                    MenuIds.CHANNEL_ACTIONS,
                    MenuPayload.Channel(entry.channel),
                    entry.channel.name,
                )
            }
        }
    }
}

private fun libraryTitle(kind: LibraryKind, strings: Strings): String = strings.text(
    when (kind) {
        LibraryKind.SAVED -> "TXT_ANDROID_MENU_SAVED"
        LibraryKind.RECENT -> "TXT_MENU_MAIN_MY_RECENTS"
        LibraryKind.DOWNLOADS -> "TXT_ANDROID_MENU_DOWNLOADS"
    },
)

private fun libraryEmpty(kind: LibraryKind, strings: Strings): String? = when (kind) {
    LibraryKind.SAVED -> strings.text("TXT_ANDROID_LIBRARY_EMPTY_SAVED")
    LibraryKind.RECENT -> strings.text("TXT_ANDROID_LIBRARY_EMPTY_RECENT")
    // The Downloads screen shows the extractor's UNSUPPORTED reason instead.
    LibraryKind.DOWNLOADS -> null
}

private fun feedTitle(kind: FeedKind, strings: Strings): String = strings.text(
    when (kind) {
        FeedKind.HOME -> "TXT_MENU_MAIN_FEED"
        FeedKind.SUBSCRIPTIONS -> "TXT_MENU_MAIN_SUBS_FEED"
        FeedKind.TRENDING -> "TXT_ANDROID_MENU_TRENDING"
        FeedKind.WATCH_LATER -> "TXT_MENU_MAIN_WATCH_LATER"
        FeedKind.LIKED -> "TXT_MENU_MAIN_LIKED"
        FeedKind.HISTORY -> "TXT_MENU_MAIN_HISTORY"
        FeedKind.CLIPS -> "TXT_ANDROID_MENU_CLIPS"
        FeedKind.PLAYLISTS -> "TXT_MENU_MAIN_PLAYLISTS"
        FeedKind.CUSTOM_PLAYLISTS -> "TXT_ANDROID_MENU_CUSTOM_PLAYLISTS"
        FeedKind.CHANNELS -> "TXT_MENU_MAIN_CHANNELS"
        FeedKind.EXPLORE -> "TXT_MENU_MISC_EXPLORE_CHANNELS"
    },
)
