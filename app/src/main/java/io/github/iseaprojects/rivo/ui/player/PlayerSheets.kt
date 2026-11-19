package io.github.iseaprojects.rivo.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.iseaprojects.rivo.core.i18n.Strings
import io.github.iseaprojects.rivo.player.PlayerUiState
import io.github.iseaprojects.rivo.player.SubtitleChoice
import kotlin.math.abs

private data class SheetEntry(
    val title: String,
    val detail: String? = null,
    val selected: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Pickers for the four things a video player always needs: speed, quality, audio
 * rendition and subtitles. They are plain lists because the app is a list-first interface;
 * a check mark marks the active value, and the sheet closes on choice.
 */
@Composable
fun SpeedSheet(state: PlayerUiState, strings: Strings, onPick: (Float) -> Unit) {
    SheetBody(strings.text("TXT_ANDROID_PLAYER_SPEED")) {
        SPEEDS.map { speed ->
            SheetEntry(
                title = speedLabel(speed),
                selected = abs(state.speed - speed) < 0.01f,
                onClick = { onPick(speed) },
            )
        }
    }
}

@Composable
fun QualitySheet(
    state: PlayerUiState,
    strings: Strings,
    selected: Int?,
    onPick: (Int?) -> Unit,
) {
    val heights = state.extras.heights
    SheetBody(strings.text("TXT_ANDROID_PLAYER_QUALITY")) {
        buildList {
            add(
                SheetEntry(
                    title = strings.text("TXT_ANDROID_PLAYER_AUTO"),
                    detail = state.extras.qualityLabel,
                    selected = selected == null,
                    onClick = { onPick(null) },
                ),
            )
            heights.forEach { height ->
                add(
                    SheetEntry(
                        title = "${height}p",
                        selected = selected == height,
                        onClick = { onPick(height) },
                    ),
                )
            }
        }
    }
}

@Composable
fun AudioSheet(
    state: PlayerUiState,
    strings: Strings,
    selected: String?,
    onPick: (String?) -> Unit,
) {
    val tracks = state.extras.audioTracks
    SheetBody(strings.text("TXT_ANDROID_PLAYER_AUDIO_TRACK")) {
        buildList {
            add(
                SheetEntry(
                    title = strings.text("TXT_ANDROID_PLAYER_ORIGINAL"),
                    selected = selected == null,
                    onClick = { onPick(null) },
                ),
            )
            tracks.forEach { track ->
                add(
                    SheetEntry(
                        title = track.label,
                        detail = track.detail,
                        selected = selected != null && selected == track.key,
                        onClick = { onPick(track.key) },
                    ),
                )
            }
        }
    }
}

@Composable
fun SubtitleSheet(
    state: PlayerUiState,
    strings: Strings,
    onPick: (SubtitleChoice?) -> Unit,
) {
    SheetBody(strings.text("TXT_ANDROID_PLAYER_SUBTITLES")) {
        buildList {
            add(
                SheetEntry(
                    title = strings.text("TXT_ANDROID_OFF"),
                    selected = state.subtitlesOff || state.subtitles.isEmpty(),
                    onClick = { onPick(null) },
                ),
            )
            state.subtitles.forEach { choice ->
                add(
                    SheetEntry(
                        title = choice.label,
                        detail = choice.language,
                        selected = !state.subtitlesOff &&
                            state.subtitleLanguage == choice.language,
                        onClick = { onPick(choice) },
                    ),
                )
            }
        }
    }
}

@Composable
fun ChapterSheet(state: PlayerUiState, strings: Strings, onSeek: (Long) -> Unit) {
    SheetBody(strings.text("TXT_ANDROID_PLAYER_CHAPTERS")) {
        state.chapters.map { chapter ->
            val end = chapter.endMs ?: Long.MAX_VALUE
            SheetEntry(
                title = chapter.title,
                detail = formatPosition(chapter.startMs),
                selected = state.positionMs in chapter.startMs until end,
                onClick = { onSeek(chapter.startMs) },
            )
        }
    }
}

@Composable
private fun SheetBody(title: String, entries: () -> List<SheetEntry>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )
        entries().forEach { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { entry.onClick() }
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                entry.detail?.let { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (entry.selected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)

private fun speedLabel(speed: Float): String {
    val text = if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()
    return "$text\u00d7"
}
