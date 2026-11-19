package io.github.iseaprojects.rivo.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.iseaprojects.rivo.player.PlayerUiState

/**
 * Seek bar with the video's own metadata drawn into it: the "most replayed" heatmap, chapter
 * ticks and SponsorBlock segments. The Slider is only a thumb — the track itself is a Canvas,
 * because Material's slider cannot draw variable-width buckets or two independent overlays.
 */
@Composable
fun PlayerTimeline(
    state: PlayerUiState,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    val duration = state.durationMs.coerceAtLeast(1L)
    val shown = if (dragging) dragValue.toLong() else state.positionMs
    val progress = (shown.toFloat() / duration).coerceIn(0f, 1f)
    val buffered = (state.bufferedMs.toFloat() / duration).coerceIn(0f, 1f)

    val primary = MaterialTheme.colorScheme.primary
    val track = Color.White.copy(alpha = 0.22f)
    val bufferedColor = Color.White.copy(alpha = 0.38f)
    val chapterColor = Color.White.copy(alpha = 0.55f)
    val sponsorColor = MaterialTheme.colorScheme.tertiary

    Column(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
            ) {
                val center = size.height / 2f
                val height = 3.dp.toPx()

                state.heatmap.forEach { point ->
                    val x0 = (point.startMs.toFloat() / duration * size.width).coerceIn(0f, size.width)
                    val x1 = (point.endMs.toFloat() / duration * size.width).coerceIn(0f, size.width)
                    val width = (x1 - x0).coerceAtLeast(1f)
                    if (width <= 0f) return@forEach
                    drawRect(
                        color = primary.copy(alpha = 0.10f + 0.22f * point.intensity.coerceIn(0f, 1f)),
                        topLeft = Offset(x0, center - 7.dp.toPx()),
                        size = Size(width, 14.dp.toPx()),
                    )
                }

                drawRoundRect(
                    color = track,
                    topLeft = Offset(0f, center - height / 2f),
                    size = Size(size.width, height),
                    cornerRadius = CornerRadius(height / 2f),
                )
                if (buffered > 0f) {
                    drawRoundRect(
                        color = bufferedColor,
                        topLeft = Offset(0f, center - height / 2f),
                        size = Size(size.width * buffered, height),
                        cornerRadius = CornerRadius(height / 2f),
                    )
                }
                if (progress > 0f) {
                    drawRoundRect(
                        color = primary,
                        topLeft = Offset(0f, center - height / 2f),
                        size = Size(size.width * progress, height),
                        cornerRadius = CornerRadius(height / 2f),
                    )
                }

                state.chapters.forEach { chapter ->
                    val x = (chapter.startMs.toFloat() / duration * size.width)
                        .coerceIn(0f, size.width)
                    drawLine(
                        color = chapterColor,
                        start = Offset(x, center - 6.dp.toPx()),
                        end = Offset(x, center + 6.dp.toPx()),
                        strokeWidth = 1.dp.toPx(),
                    )
                }

                state.sponsors.forEach { sponsor ->
                    val end = sponsor.endMs ?: return@forEach
                    val x0 = (sponsor.startMs.toFloat() / duration * size.width)
                        .coerceIn(0f, size.width)
                    val x1 = (end.toFloat() / duration * size.width).coerceIn(0f, size.width)
                    val width = (x1 - x0).coerceAtLeast(1f)
                    drawRoundRect(
                        color = sponsorColor.copy(alpha = 0.65f),
                        topLeft = Offset(x0, center + 5.dp.toPx()),
                        size = Size(width, 2.dp.toPx()),
                        cornerRadius = CornerRadius(1.dp.toPx()),
                    )
                }
            }

            Slider(
                value = if (dragging) dragValue else state.positionMs.toFloat(),
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    dragging = false
                    onSeek(dragValue.toLong())
                },
                valueRange = 0f..duration.toFloat(),
                enabled = enabled,
                colors = SliderDefaults.colors(
                    thumbColor = primary,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    disabledThumbColor = primary.copy(alpha = 0.5f),
                    disabledActiveTrackColor = Color.Transparent,
                    disabledInactiveTrackColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatPosition(shown),
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = if (state.durationMs > 0) formatPosition(state.durationMs) else "--:--",
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** `m:ss` under an hour, `h:mm:ss` above it — the same shape yt-x's preview uses. */
internal fun formatPosition(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
