package io.github.iseaprojects.rivo.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.iseaprojects.rivo.core.i18n.Strings
import io.github.iseaprojects.rivo.core.menu.MenuTone
import io.github.iseaprojects.rivo.core.model.ChannelSummary
import io.github.iseaprojects.rivo.core.model.MediaSummary
import io.github.iseaprojects.rivo.core.model.PlaylistSummary
import io.github.iseaprojects.rivo.core.search.SearchFilter
import io.github.iseaprojects.rivo.core.search.SearchFilterGroup
import io.github.iseaprojects.rivo.core.search.SearchFilters
import io.github.iseaprojects.rivo.core.ui.CountFormat
import io.github.iseaprojects.rivo.data.extract.ChannelTab
import io.github.iseaprojects.rivo.data.extract.ChannelTabKind
import io.github.iseaprojects.rivo.ui.screens.ListRow

/** Row labels shared by every list screen; the detail line mirrors yt-x's fzf line. */
fun MediaEntry.toListRow(strings: Strings): ListRow<MediaEntry> = when (this) {
    is MediaEntry.Video -> ListRow(
        id = key,
        item = this,
        label = media.title.ifBlank { media.url },
        detail = videoDetail(media, strings),
        icon = "\uF144",
        badge = if (media.isLive) strings.text("TXT_ANDROID_LIVE") else null,
        tone = MenuTone.NORMAL,
        thumbnailUrl = media.thumbnailUrl,
        searchText = searchText,
    )

    is MediaEntry.Playlist -> ListRow(
        id = key,
        item = this,
        label = playlist.title.ifBlank { playlist.url },
        detail = playlistDetail(playlist, strings),
        icon = "\uF0CA",
        submenu = true,
        thumbnailUrl = playlist.thumbnailUrl,
        searchText = searchText,
    )

    is MediaEntry.Channel -> ListRow(
        id = key,
        item = this,
        label = channel.name.ifBlank { channel.url },
        detail = channelDetail(channel, strings),
        icon = "\uF007",
        submenu = true,
        thumbnailUrl = channel.avatarUrl,
        searchText = searchText,
    )
}

private fun videoDetail(media: MediaSummary, strings: Strings): String = listOfNotNull(
    media.channelName?.takeIf { it.isNotBlank() },
    media.durationText,
    CountFormat.compact(media.viewCount).takeIf { it.isNotEmpty() }
        ?.let { "$it ${strings.text("TXT_FZF_PREVIEW_VIEW_COUNT")}" },
).joinToString(" · ")

private fun playlistDetail(playlist: PlaylistSummary, strings: Strings): String = listOfNotNull(
    playlist.channelName?.takeIf { it.isNotBlank() },
    playlist.itemCount?.let { strings.format("TXT_ANDROID_ITEM_COUNT_FMT", it) },
).joinToString(" · ")

private fun channelDetail(channel: ChannelSummary, strings: Strings): String =
    CountFormat.compact(channel.subscriberCount).takeIf { it.isNotEmpty() }
        ?.let { "$it ${strings.text("TXT_FZF_PREVIEW_CHANNEL_FOLLOWERS")}" }
        .orEmpty()

/** The `sp` chips. One active filter at a time: a single protobuf cannot hold two. */
@Composable
fun SearchFilterRow(
    activeToken: String?,
    strings: Strings,
    modifier: Modifier = Modifier,
    onSelect: (SearchFilter) -> Unit,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        SearchFilters.all.forEachIndexed { index, filter ->
            if (index == 0 || SearchFilters.all[index - 1].group != filter.group) {
                item(key = "group:" + filter.group.name) {
                    Text(
                        text = strings.text(filter.group.labelKey()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 10.dp, end = 2.dp),
                    )
                }
            }
            item(key = filter.token) {
                FilterChip(
                    selected = filter.token == activeToken,
                    onClick = { onSelect(filter) },
                    label = { Text(strings.text(filter.labelKey)) },
                )
            }
        }
    }
}

private fun SearchFilterGroup.labelKey(): String = when (this) {
    SearchFilterGroup.DATE -> "TXT_ANDROID_FILTER_GROUP_DATE"
    SearchFilterGroup.TYPE -> "TXT_ANDROID_FILTER_GROUP_TYPE"
    SearchFilterGroup.FEATURE -> "TXT_ANDROID_FILTER_GROUP_FEATURE"
    SearchFilterGroup.SORT -> "TXT_ANDROID_FILTER_GROUP_SORT"
}

/** Channel tabs, labelled with the upstream channel-action strings. */
@Composable
fun ChannelTabRow(
    tabs: List<ChannelTab>,
    active: ChannelTabKind?,
    strings: Strings,
    modifier: Modifier = Modifier,
    onSelect: (ChannelTab) -> Unit,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        items(tabs, key = { it.id }) { tab ->
            FilterChip(
                selected = tab.kind == active,
                onClick = { onSelect(tab) },
                label = { Text(strings.text(tab.kind.labelKey())) },
            )
        }
    }
}

fun ChannelTabKind.labelKey(): String = when (this) {
    ChannelTabKind.VIDEOS -> "TXT_MENU_CHANNEL_ACTIONS_VIDEOS"
    ChannelTabKind.SHORTS -> "TXT_MENU_CHANNEL_ACTIONS_SHORTS"
    ChannelTabKind.LIVE -> "TXT_MENU_CHANNEL_ACTIONS_STREAMS"
    ChannelTabKind.PLAYLISTS -> "TXT_MENU_CHANNEL_ACTIONS_PLAYLISTS"
    ChannelTabKind.FEATURED -> "TXT_MENU_CHANNEL_ACTIONS_FEATURED"
    ChannelTabKind.PODCASTS -> "TXT_MENU_CHANNEL_ACTIONS_PODCASTS"
    ChannelTabKind.RELEASES -> "TXT_ANDROID_TAB_RELEASES"
    ChannelTabKind.OTHER -> "TXT_ANDROID_TAB_MORE"
}

/** Preview panel body: thumbnail first, then the fields yt-x prints under it. */
@Composable
fun MediaPreview(
    row: ListRow<MediaEntry>,
    strings: Strings,
    showImage: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(16.dp)) {
        val entry = row.item
        val image = when (entry) {
            is MediaEntry.Video -> entry.media.thumbnailUrl
            is MediaEntry.Playlist -> entry.playlist.thumbnailUrl
            is MediaEntry.Channel -> entry.channel.avatarUrl
        }
        if (showImage && image != null) {
            AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(6.dp)),
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        Text(
            text = row.label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )

        when (entry) {
            is MediaEntry.Video -> VideoPreview(entry.media, strings)
            is MediaEntry.Playlist -> {
                Spacer(modifier = Modifier.height(8.dp))
                InfoLine(strings.text("TXT_FZF_PREVIEW_CHANNEL"), entry.playlist.channelName)
                InfoLine(
                    strings.text("TXT_ANDROID_ITEMS"),
                    entry.playlist.itemCount?.toString(),
                )
                entry.playlist.description?.let { Description(it) }
            }
            is MediaEntry.Channel -> {
                Spacer(modifier = Modifier.height(8.dp))
                InfoLine(
                    strings.text("TXT_FZF_PREVIEW_CHANNEL_FOLLOWERS"),
                    CountFormat.compact(entry.channel.subscriberCount).takeIf { it.isNotEmpty() },
                )
                entry.channel.description?.let { Description(it) }
            }
        }
    }
}

@Composable
private fun VideoPreview(media: MediaSummary, strings: Strings) {
    Spacer(modifier = Modifier.height(8.dp))
    InfoLine(strings.text("TXT_FZF_PREVIEW_CHANNEL"), media.channelName)
    InfoLine(strings.text("TXT_FZF_PREVIEW_DURATION"), media.durationText)
    InfoLine(
        strings.text("TXT_FZF_PREVIEW_VIEW_COUNT"),
        CountFormat.compact(media.viewCount).takeIf { it.isNotEmpty() },
    )
    InfoLine(strings.text("TXT_FZF_PREVIEW_TIMESTAMP"), media.publishedText)
    if (media.isLive) {
        InfoLine(strings.text("TXT_FZF_PREVIEW_LIVE_STATUS"), strings.text("TXT_ANDROID_LIVE"))
    }
    media.description?.takeIf { it.isNotBlank() }?.let { Description(it) }
}

@Composable
private fun InfoLine(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(modifier = Modifier.padding(top = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Description(text: String) {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 12,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Extractor suggestions for the typed prefix. Upstream recalls `!1`/`!2` from its JSON
 * file; the search endpoint is the same data with the long tail included, and the local
 * history store (F4) can be merged in front of it without changing this row.
 */
@Composable
fun SuggestionRow(
    suggestions: List<String>,
    modifier: Modifier = Modifier,
    onPick: (String) -> Unit,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
    ) {
        items(suggestions, key = { it }) { suggestion ->
            AssistChip(
                onClick = { onPick(suggestion) },
                label = {
                    Text(
                        text = suggestion,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}
