package io.github.iseaprojects.rivo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.iseaprojects.rivo.core.i18n.Strings
import io.github.iseaprojects.rivo.core.menu.MenuTone
import io.github.iseaprojects.rivo.core.theme.ThemeSpec
import io.github.iseaprojects.rivo.core.ui.Fuzzy
import io.github.iseaprojects.rivo.core.ui.KeyAction
import io.github.iseaprojects.rivo.core.ui.KeyBindings
import io.github.iseaprojects.rivo.ui.theme.errorColor
import io.github.iseaprojects.rivo.ui.theme.iconColor

/**
 * One rendered entry. [item] is the caller's own model, which keeps the shell usable for
 * action menus (`MenuNode`) and extractor lists (`MediaEntry`) without a union type.
 *
 * [searchText] defaults to label + detail, which is what yt-x feeds to `fzf`; media
 * screens widen it to include the channel and duration.
 */
data class ListRow<T>(
    val id: String,
    val item: T,
    val label: String,
    val detail: String? = null,
    val icon: String? = null,
    val badge: String? = null,
    val hint: String? = null,
    val tone: MenuTone = MenuTone.NORMAL,
    val enabled: Boolean = true,
    val checked: Boolean? = null,
    val submenu: Boolean = false,
    val thumbnailUrl: String? = null,
    val searchText: String = label + " " + (detail ?: ""),
)

/** Load state of a data-backed list. Action menus stay [ListStatus.Ready]. */
sealed interface ListStatus {
    data object Ready : ListStatus
    data object Loading : ListStatus

    /** [detail] is the extractor's own message, shown under the localized summary. */
    data class Failed(
        val summary: String,
        val detail: String? = null,
        val retryable: Boolean = false,
    ) : ListStatus
}

/**
 * Explicit page window, the Android replacement for scrolling `fzf`.
 *
 * [pageCount] is only known once the feed is exhausted, so the footer shows "Page 2"
 * while more data exists and "Page 2 of 3" afterwards instead of inventing a total.
 */
data class ListPager(
    val page: Int,
    val itemCount: Int,
    val pageCount: Int? = null,
    val totalCount: Long? = null,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val loading: Boolean = false,
    val onPrevious: () -> Unit = {},
    val onNext: () -> Unit = {},
)

/** One breadcrumb: the frame it pops to and the label it shows. */
data class Crumb(val title: String, val index: Int)

/**
 * The single list screen of the app.
 *
 * Every screen in yt-x is a filtered list with a preview and a page window, so this
 * composable owns the whole interaction contract: an always-focused fuzzy filter, arrow
 * and vim navigation that coexists with text entry, selection that follows the filter,
 * an explicit pager, and a preview that is a side panel on wide screens and a bottom
 * sheet on phones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> MenuScreen(
    title: String,
    breadcrumbs: List<Crumb>,
    rows: List<ListRow<T>>,
    strings: Strings,
    theme: ThemeSpec,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onBreadcrumb: (Int) -> Unit,
    onActivate: (ListRow<T>) -> Unit,
    snackbarHostState: SnackbarHostState,
    stateKey: String,
    modifier: Modifier = Modifier,
    keyBindings: KeyBindings = KeyBindings.DEFAULT,
    previewEnabled: Boolean = false,
    previewImages: Boolean = true,
    twoPane: Boolean = false,
    status: ListStatus = ListStatus.Ready,
    pager: ListPager? = null,
    onRefresh: (() -> Unit)? = null,
    onSubmit: (() -> Unit)? = null,
    emptyText: String? = null,
    filterValue: String? = null,
    onFilterChange: ((String) -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
    preview: (@Composable (ListRow<T>) -> Unit)? = null,
) {
    var localQuery by rememberSaveable(stateKey) { mutableStateOf("") }
    var selected by remember(stateKey) { mutableIntStateOf(0) }
    var showPreview by rememberSaveable(stateKey) { mutableStateOf(false) }
    var filterFocused by remember { mutableStateOf(true) }

    // The search screen owns its text (it feeds the extractor), every other screen lets
    // the shell keep it. Passing a value switches the field to controlled mode.
    val query = filterValue ?: localQuery
    val setQuery: (String) -> Unit = { value ->
        if (onFilterChange != null) onFilterChange(value) else localQuery = value
    }

    val filtered = remember(rows, query) {
        Fuzzy.filter(rows, query) { row -> row.searchText }
    }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // The fzf contract: the prompt is focused on arrival, so typing filters immediately.
    LaunchedEffect(stateKey, showPreview) {
        if (!showPreview) runCatching { focusRequester.requestFocus() }
    }
    // A stricter filter can hide the selected row; keep the cursor inside the list.
    LaunchedEffect(filtered.size) {
        if (selected > filtered.lastIndex) selected = filtered.lastIndex.coerceAtLeast(0)
    }
    LaunchedEffect(selected, filtered.size) {
        if (selected in filtered.indices) listState.animateScrollToItem(selected)
    }

    fun move(delta: Int) {
        if (filtered.isEmpty()) return
        selected = (selected + delta).coerceIn(0, filtered.lastIndex)
    }

    fun activate(row: ListRow<T>?) {
        if (row != null && row.enabled) onActivate(row)
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val name = keyName(event) ?: return false
        val action = keyBindings.actionFor(
            key = name,
            ctrl = event.isCtrlPressed,
            alt = event.isAltPressed,
            shift = event.isShiftPressed,
        ) ?: return false

        // Plain letters belong to the text field while it owns focus. Dropping focus
        // (Esc on an empty filter) switches the list into vim mode, which is where the
        // upstream `j`/`k` muscle memory lives.
        if (filterFocused && keyBindings.isPlainCharacter(action)) return false

        when (action) {
            KeyAction.DOWN -> move(1)
            KeyAction.UP -> move(-1)
            KeyAction.HALF_PAGE_DOWN -> move(pageJump())
            KeyAction.HALF_PAGE_UP -> move(-pageJump())
            KeyAction.SELECT -> {
                val row = filtered.getOrNull(selected)
                if (row != null) activate(row) else onSubmit?.invoke()
            }
            KeyAction.BACK -> when {
                query.isNotEmpty() -> setQuery("")
                else -> filterFocused = false
            }
            KeyAction.SEARCH -> runCatching { focusRequester.requestFocus() }
            KeyAction.PREVIEW -> if (previewEnabled && preview != null) {
                showPreview = !showPreview
            }
            KeyAction.CLEAR -> setQuery("")
            KeyAction.NEXT_PAGE -> pager?.let { if (it.hasNext && !it.loading) it.onNext() }
            KeyAction.PREVIOUS_PAGE -> pager?.let { if (it.hasPrevious) it.onPrevious() }
        }
        return true
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    if (canGoBack) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = strings.text("TXT_MENU_BACK"),
                            )
                        }
                    }
                },
                actions = {
                    // fzf toggles its preview with `space`, which a phone keyboard does not
                    // have; the two-pane layout has no toggle because the panel is always up.
                    if (!twoPane && previewEnabled && preview != null) {
                        IconButton(onClick = { showPreview = !showPreview }) {
                            Icon(
                                imageVector = if (showPreview) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                                contentDescription = strings.text("TXT_ANDROID_PREVIEW_PANEL"),
                                tint = if (showPreview) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    actions?.invoke()
                    if (onRefresh != null) {
                        IconButton(onClick = onRefresh) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = strings.text("TXT_ANDROID_RETRY"),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
    ) { padding ->
        Row(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .imePadding()
                    .onPreviewKeyEvent(::handleKey),
            ) {
                if (breadcrumbs.isNotEmpty()) {
                    Breadcrumbs(crumbs = breadcrumbs, onCrumb = onBreadcrumb)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { value ->
                        setQuery(value)
                        selected = 0
                        filterFocused = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .focusRequester(focusRequester)
                        .onFocusChanged { filterFocused = it.isFocused },
                    singleLine = true,
                    placeholder = { Text(strings.text("TXT_ANDROID_FILTER_HINT")) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                        )
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { setQuery("") }) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = strings.text("TXT_ANDROID_CANCEL"),
                                )
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            if (filtered.isEmpty()) onSubmit?.invoke()
                            else activate(filtered.getOrNull(selected))
                        },
                    ),
                )

                header?.invoke()

                Box(modifier = Modifier.weight(1f)) {
                    when {
                        status is ListStatus.Loading && filtered.isEmpty() ->
                            CenteredStatus(strings.text("TXT_ANDROID_LOADING"), loading = true)

                        status is ListStatus.Failed && filtered.isEmpty() -> ErrorState(
                            summary = status.summary,
                            detail = status.detail,
                            retry = onRefresh?.takeIf { status.retryable },
                            strings = strings,
                        )

                        filtered.isEmpty() -> EmptyState(
                            text = emptyText ?: if (rows.isEmpty()) {
                                strings.text("TXT_ANDROID_EMPTY")
                            } else {
                                strings.text("TXT_ANDROID_NO_MATCHES")
                            },
                        )

                        else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(filtered, key = { _, row -> row.id }) { index, row ->
                                EntryRow(
                                    row = row,
                                    selected = index == selected,
                                    theme = theme,
                                    previewImages = previewImages,
                                    onClick = {
                                        selected = index
                                        activate(row)
                                    },
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                )
                            }
                        }
                    }
                }

                pager?.let { Pager(state = it, strings = strings) }
            }

            if (twoPane && previewEnabled && preview != null) {
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(
                    modifier = Modifier
                        .width(340.dp)
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    val row = filtered.getOrNull(selected)
                    if (row != null) preview.invoke(row) else PreviewHint(strings)
                }
            }
        }
    }

    if (!twoPane && showPreview && preview != null) {
        val row = filtered.getOrNull(selected)
        ModalBottomSheet(
            onDismissRequest = { showPreview = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            if (row != null) preview.invoke(row) else PreviewHint(strings)
        }
    }
}

@Composable
private fun Pager(state: ListPager, strings: Strings) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = state.onPrevious,
            enabled = state.hasPrevious && !state.loading,
        ) {
            Text(strings.text("TXT_MENU_PREVIOUS"))
        }
        Text(
            text = pageLabel(state, strings),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (state.loading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(18.dp)
                    .padding(end = 4.dp),
                strokeWidth = 2.dp,
            )
        }
        TextButton(
            onClick = state.onNext,
            enabled = state.hasNext && !state.loading,
        ) {
            Text(strings.text("TXT_MENU_NEXT"))
        }
    }
}

private fun pageLabel(state: ListPager, strings: Strings): String {
    val page = state.pageCount?.let { strings.format("TXT_ANDROID_PAGE_OF", state.page, it) }
        ?: strings.format("TXT_ANDROID_PAGE_FMT", state.page)
    val count = when {
        // A one-row list reads "1 item", not "1/1 items".
        state.totalCount == 1L -> strings.text("TXT_ANDROID_ITEM_COUNT_ONE")
        state.totalCount != null ->
            strings.format("TXT_ANDROID_ITEM_COUNT_FMT", "${state.itemCount}/${state.totalCount}")
        state.itemCount == 1 -> strings.text("TXT_ANDROID_ITEM_COUNT_ONE")
        else -> strings.format("TXT_ANDROID_ITEM_COUNT_FMT", state.itemCount.toString())
    }
    return "$page · $count"
}

@Composable
private fun Breadcrumbs(crumbs: List<Crumb>, onCrumb: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { index, crumb ->
            if (index > 0) {
                Text(
                    text = " / ",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            val isCurrent = index == crumbs.lastIndex
            Text(
                text = crumb.title,
                style = MaterialTheme.typography.labelMedium,
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(enabled = !isCurrent, role = Role.Button) { onCrumb(crumb.index) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun <T> EntryRow(
    row: ListRow<T>,
    selected: Boolean,
    theme: ThemeSpec,
    previewImages: Boolean,
    onClick: () -> Unit,
) {
    val accent = theme.iconColor(MaterialTheme.colorScheme.secondary)
    val destructive = theme.errorColor(MaterialTheme.colorScheme.error)
    val iconColor = when (row.tone) {
        MenuTone.DESTRUCTIVE -> destructive
        MenuTone.ACCENT -> MaterialTheme.colorScheme.primary
        MenuTone.MUTED -> MaterialTheme.colorScheme.onSurfaceVariant
        MenuTone.NORMAL -> accent
    }
    val labelColor = when {
        !row.enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        row.tone == MenuTone.DESTRUCTIVE -> destructive
        row.tone == MenuTone.MUTED -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    val background = if (selected) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.background
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(enabled = row.enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(32.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Spacer(modifier = Modifier.width(5.dp))
        }

        if (previewImages && row.thumbnailUrl != null) {
            AsyncImage(
                model = row.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 64.dp, height = 36.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(modifier = Modifier.width(12.dp))
        } else {
            Text(
                text = row.icon ?: " ",
                modifier = Modifier.width(28.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = iconColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.width(4.dp))
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                text = row.label,
                style = MaterialTheme.typography.bodyLarge,
                color = labelColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            row.detail?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        row.badge?.let { badge ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = RoundedCornerShape(4.dp),
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        row.checked?.let { checked ->
            Switch(
                checked = checked,
                onCheckedChange = { if (row.enabled) onClick() },
                enabled = row.enabled,
                modifier = Modifier.semantics { contentDescription = row.label },
            )
        }

        if (row.checked == null && row.submenu) {
            Text(
                text = "›",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        row.hint?.let { hint ->
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun CenteredStatus(text: String, loading: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.height(12.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ErrorState(
    summary: String,
    detail: String?,
    retry: (() -> Unit)?,
    strings: Strings,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (retry != null) {
                TextButton(onClick = retry, modifier = Modifier.padding(top = 8.dp)) {
                    Text(strings.text("TXT_ANDROID_RETRY"))
                }
            }
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "[ ]",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.outline,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun PreviewHint(strings: Strings) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = strings.text("TXT_ANDROID_EMPTY"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )
    }
}

/** Half a screen is the fzf `half-page` step; the exact number does not matter much. */
private fun pageJump(): Int = 6

/**
 * Compose key event -> the names `CONFIG_KEYBINDINGS` uses.
 *
 * Letters and digits go through `Key` rather than `utf16CodePoint` because a ctrl chord
 * reports a control character as its code point (`Ctrl-D` is `0x04`, not `d`).
 */
private fun keyName(event: KeyEvent): String? = when (event.key) {
    Key.DirectionDown -> "down"
    Key.DirectionUp -> "up"
    Key.DirectionLeft -> "left"
    Key.DirectionRight -> "right"
    Key.Enter, Key.NumPadEnter -> "enter"
    Key.Escape -> "esc"
    Key.Spacebar -> "space"
    Key.PageDown -> "page-down"
    Key.PageUp -> "page-up"
    Key.MoveHome -> "home"
    Key.MoveEnd -> "end"
    Key.Tab -> "tab"
    Key.Backspace -> "backspace"
    Key.Delete -> "delete"
    Key.Slash -> "/"
    Key.A -> "a"
    Key.B -> "b"
    Key.C -> "c"
    Key.D -> "d"
    Key.E -> "e"
    Key.F -> "f"
    Key.G -> "g"
    Key.H -> "h"
    Key.I -> "i"
    Key.J -> "j"
    Key.K -> "k"
    Key.L -> "l"
    Key.M -> "m"
    Key.N -> "n"
    Key.O -> "o"
    Key.P -> "p"
    Key.Q -> "q"
    Key.R -> "r"
    Key.S -> "s"
    Key.T -> "t"
    Key.U -> "u"
    Key.V -> "v"
    Key.W -> "w"
    Key.X -> "x"
    Key.Y -> "y"
    Key.Z -> "z"
    Key.Zero -> "0"
    Key.One -> "1"
    Key.Two -> "2"
    Key.Three -> "3"
    Key.Four -> "4"
    Key.Five -> "5"
    Key.Six -> "6"
    Key.Seven -> "7"
    Key.Eight -> "8"
    Key.Nine -> "9"
    else -> {
        val code = event.utf16CodePoint
        if (code in 32..126) code.toChar().lowercaseChar().toString() else null
    }
}
