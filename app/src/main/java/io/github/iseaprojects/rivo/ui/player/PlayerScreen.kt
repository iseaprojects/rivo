package io.github.iseaprojects.rivo.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.ui.PlayerView
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import io.github.iseaprojects.rivo.R
import io.github.iseaprojects.rivo.core.config.ConfigKeys
import io.github.iseaprojects.rivo.core.i18n.Strings
import io.github.iseaprojects.rivo.core.model.MediaSummary
import io.github.iseaprojects.rivo.di.AppGraph
import io.github.iseaprojects.rivo.player.LaunchOutcome
import io.github.iseaprojects.rivo.player.PlayMode
import io.github.iseaprojects.rivo.player.PlayerController
import io.github.iseaprojects.rivo.player.PlayerUiState
import io.github.iseaprojects.rivo.player.PlaybackActions
import io.github.iseaprojects.rivo.player.describe
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class PlayerSheet { NONE, SPEED, QUALITY, AUDIO, SUBTITLES, CHAPTERS }

/**
 * Full-screen player UI.
 *
 * Controls stay out of the way: they fade four seconds after the last touch while the
 * video is playing, and the sheet-based pickers (speed, quality, audio, subtitles,
 * chapters) need no space on the surface itself. Everything reads from [PlayerUiState],
 * which mirrors the session; nothing here holds playback state of its own except the
 * temporary drag position of the timeline.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    graph: AppGraph,
    strings: Strings,
    controller: MediaController,
    inPip: Boolean,
    pendingError: String?,
    onErrorShown: () -> Unit,
    onClose: () -> Unit,
    onEnterPip: () -> Unit,
    onImmersive: (Boolean) -> Unit,
) {
    val holder = remember(controller) { PlayerController(controller) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val appContext = LocalContext.current.applicationContext

    DisposableEffect(controller) {
        holder.attach()
        onDispose { holder.detach() }
    }

    LaunchedEffect(controller) {
        while (true) {
            holder.tick()
            delay(250)
        }
    }

    val state = holder.state

    LaunchedEffect(pendingError) {
        if (pendingError != null) {
            holder.showError(pendingError)
            onErrorShown()
        }
    }

    // Audio-only sessions want the normal system chrome; a video wants the screen.
    LaunchedEffect(state.audioOnly) { onImmersive(!state.audioOnly) }

    var controlsVisible by remember { mutableStateOf(true) }
    var interactionAt by remember { mutableLongStateOf(0L) }
    var sheet by remember { mutableStateOf(PlayerSheet.NONE) }
    var loopStart by remember { mutableLongStateOf(-1L) }
    var loopEnd by remember { mutableLongStateOf(-1L) }
    var quality by remember { mutableStateOf<Int?>(null) }

    fun interact() {
        controlsVisible = true
        interactionAt = android.os.SystemClock.elapsedRealtime()
    }

    LaunchedEffect(state.isPlaying, interactionAt) {
        if (state.isPlaying) {
            delay(AUTO_HIDE_MS)
            controlsVisible = false
        } else if (!state.isBuffering) {
            // A paused picture with no chrome is a dead end: there is no video motion to
            // hint that a tap brings the controls back, so they stay put.
            controlsVisible = true
        }
    }

    // A–B loop is enforced here rather than with Player.setRepeatMode because Media3 has
    // no loop-region primitive; a 250 ms poll is well inside a frame of audibility.
    LaunchedEffect(state.positionMs, loopStart, loopEnd) {
        if (loopStart >= 0 && loopEnd > loopStart && state.positionMs >= loopEnd) {
            holder.seekTo(loopStart)
        }
    }

    LaunchedEffect(state.mediaId) {
        holder.clearError()
        quality = null
    }

    state.error?.let { message ->
        LaunchedEffect(message) {
            val text = strings.format("TXT_ANDROID_PLAYER_ERROR", message)
            snackbar.showSnackbar(text)
            holder.clearError()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (state.audioOnly) MaterialTheme.colorScheme.background else Color.Black),
    ) {
        if (state.audioOnly) {
            AudioBackdrop(state, strings = strings)
        } else {
            VideoSurface(controller = controller, modifier = Modifier.fillMaxSize())
        }

        if (!inPip) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (controlsVisible && state.isPlaying) {
                            controlsVisible = false
                        } else {
                            interact()
                        }
                    },
            )

            if (controlsVisible) {
                PlayerOverlay(
                    state = state,
                    strings = strings,
                    onTap = { controlsVisible = false },
                    onClose = onClose,
                    onPip = onEnterPip,
                    onTogglePlay = { holder.togglePlayPause(); interact() },
                    onPrevious = { holder.previous(); interact() },
                    onNext = { holder.next(); interact() },
                    onSeek = { position -> holder.seekTo(position); interact() },
                    onSpeed = { sheet = PlayerSheet.SPEED },
                    onQuality = { sheet = PlayerSheet.QUALITY },
                    onAudio = { sheet = PlayerSheet.AUDIO },
                    onSubtitles = { sheet = PlayerSheet.SUBTITLES },
                    onChapters = { sheet = PlayerSheet.CHAPTERS },
                    loopActive = loopEnd > loopStart && loopStart >= 0,
                    onLoop = {
                        when {
                            loopStart < 0 -> loopStart = state.positionMs
                            loopEnd <= loopStart -> loopEnd = state.positionMs
                            else -> {
                                loopStart = -1
                                loopEnd = -1
                            }
                        }
                        interact()
                    },
                    onExternal = {
                        interact()
                        val url = state.extras.webUrl
                            ?: state.mediaId?.let(io.github.iseaprojects.rivo.data.extract.UrlCanon::watchUrl)
                        if (url == null) {
                            scope.launch {
                                snackbar.showSnackbar(strings.text("TXT_PLAYER_NOT_FOUND"))
                            }
                        } else {
                            scope.launch {
                                val mode = if (state.audioOnly) PlayMode.AUDIO else PlayMode.VIDEO
                                val outcome = PlaybackActions(graph).sendToExternal(
                                    context = appContext,
                                    url = url,
                                    mode = mode,
                                )
                                report(outcome, strings, snackbar)
                            }
                        }
                    },
                )
            }

            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            )
        }
    }

    if (sheet != PlayerSheet.NONE) {
        ModalBottomSheet(
            onDismissRequest = { sheet = PlayerSheet.NONE },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            when (sheet) {
                PlayerSheet.SPEED -> SpeedSheet(state, strings) { speed ->
                    holder.setSpeed(speed)
                    sheet = PlayerSheet.NONE
                }

                PlayerSheet.QUALITY -> QualitySheet(state, strings, quality) { height ->
                    quality = height
                    holder.setQuality(height)
                    sheet = PlayerSheet.NONE
                }

                PlayerSheet.AUDIO -> AudioSheet(state, strings, state.audioTrack) { track ->
                    holder.setAudioTrack(track)
                    sheet = PlayerSheet.NONE
                }

                PlayerSheet.SUBTITLES -> SubtitleSheet(state, strings) { choice ->
                    holder.setSubtitles(choice)
                    sheet = PlayerSheet.NONE
                }

                PlayerSheet.CHAPTERS -> ChapterSheet(state, strings) { position ->
                    holder.seekTo(position)
                    sheet = PlayerSheet.NONE
                    interact()
                }

                PlayerSheet.NONE -> Unit
            }
        }
    }
}

@Composable
fun PlayerBootScreen(strings: Strings) {
    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = strings.text("TXT_ANDROID_PLAYER_CONNECTING"),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(controller: MediaController, modifier: Modifier) {
    AndroidView(
        factory = { context ->
            // A real parent keeps the `match_parent` layout params of the inflated view and
            // avoids lint's InflateParams warning; it is not attached anywhere.
            val parent = android.widget.FrameLayout(context)
            val view = android.view.LayoutInflater.from(context)
                .inflate(R.layout.view_player, parent, false) as PlayerView
            view.apply {
                useController = false
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                keepScreenOn = true
            }
        },
        update = { view -> view.player = controller },
        onRelease = { view -> view.player = null },
        modifier = modifier,
    )
}

@Composable
private fun AudioBackdrop(state: PlayerUiState, strings: Strings) {
    Box(Modifier.fillMaxSize()) {
        if (state.artworkUri != null) {
            AsyncImage(
                model = state.artworkUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.72f)),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = state.title.ifBlank { strings.text("TXT_ANDROID_PLAYER_NOTHING") },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.artist.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = state.artist,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PlayerOverlay(
    state: PlayerUiState,
    strings: Strings,
    onTap: () -> Unit,
    onClose: () -> Unit,
    onPip: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeed: () -> Unit,
    onQuality: () -> Unit,
    onAudio: () -> Unit,
    onSubtitles: () -> Unit,
    onChapters: () -> Unit,
    onExternal: () -> Unit,
    onLoop: () -> Unit,
    loopActive: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onTap() },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = strings.text("TXT_ANDROID_PLAYER_CLOSE"),
                    tint = Color.White,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.artist.isNotBlank()) {
                    Text(
                        text = state.artist,
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextButton(onClick = onPip) {
                Text(strings.text("TXT_ANDROID_PLAYER_PIP"), color = Color.White)
            }
        }

        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            IconButton(onClick = onPrevious, enabled = state.hasPrevious || state.positionMs > 0) {
                SkipIcon(next = false, tint = Color.White)
            }
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { onTogglePlay() },
                contentAlignment = Alignment.Center,
            ) {
                if (state.isPlaying) {
                    PauseGlyph(tint = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = strings.text("TXT_ANDROID_RESUME"),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            IconButton(onClick = onNext, enabled = state.hasNext) {
                SkipIcon(next = true, tint = Color.White)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            PlayerTimeline(
                state = state,
                enabled = state.durationMs > 0,
                onSeek = onSeek,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = strings.format(
                        "TXT_ANDROID_PLAYER_QUEUE",
                        state.queueIndex,
                        state.queueSize,
                    ),
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                if (state.isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OverlayChip(speedLabel(state.speed), onSpeed)
                OverlayChip(state.extras.qualityLabel ?: strings.text("TXT_ANDROID_PLAYER_AUTO"), onQuality)
                OverlayChip(
                    label = audioLabel(state, strings),
                    onClick = onAudio,
                    active = state.audioOnly,
                )
                OverlayChip(subtitleLabel(state, strings), onSubtitles)
                if (state.chapters.isNotEmpty()) {
                    OverlayChip(strings.text("TXT_ANDROID_PLAYER_CHAPTERS"), onChapters)
                }
                OverlayChip(
                    label = if (loopActive) "A–B" else "A-B",
                    onClick = onLoop,
                    active = loopActive,
                )
                OverlayChip(strings.text("TXT_ANDROID_PLAYER_EXTERNAL"), onExternal)
            }
        }
    }
}

@Composable
private fun OverlayChip(label: String, onClick: () -> Unit, active: Boolean = false) {
    TextButton(
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 4.dp,
            vertical = 2.dp,
        ),
    ) {
        Text(
            text = label,
            color = if (active) MaterialTheme.colorScheme.primary else Color.White,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@Composable
private fun PauseGlyph(tint: Color) {
    Canvas(Modifier.size(32.dp)) {
        val barWidth = size.width * 0.24f
        val gap = size.width * 0.18f
        drawRect(tint, Offset((size.width - barWidth * 2 - gap) / 2f, 0f), Size(barWidth, size.height))
        drawRect(
            tint,
            Offset((size.width - barWidth * 2 - gap) / 2f + barWidth + gap, 0f),
            Size(barWidth, size.height),
        )
    }
}

@Composable
private fun SkipIcon(next: Boolean, tint: Color) {
    Canvas(Modifier.size(28.dp)) {
        val width = size.width
        val height = size.height
        val triangle = Path().apply {
            if (next) {
                moveTo(width * 0.04f, 0f)
                lineTo(width * 0.66f, height / 2f)
                lineTo(width * 0.04f, height)
            } else {
                moveTo(width * 0.66f, 0f)
                lineTo(width * 0.04f, height / 2f)
                lineTo(width * 0.66f, height)
            }
            close()
        }
        drawPath(triangle, tint)
        if (next) {
            drawRect(tint, Offset(width * 0.74f, 0f), Size(width * 0.14f, height))
        } else {
            drawRect(tint, Offset(width * 0.12f, 0f), Size(width * 0.14f, height))
        }
    }
}

/** How long the chrome survives untouched while the picture is moving. */
private const val AUTO_HIDE_MS = 4_000L

private fun subtitleLabel(state: PlayerUiState, strings: Strings): String = when {
    state.subtitlesOff || state.subtitles.isEmpty() ->
        strings.text("TXT_ANDROID_PLAYER_SUBTITLES")

    state.subtitleLanguage.isNullOrBlank() -> strings.text("TXT_ANDROID_PLAYER_SUBTITLES")
    else -> "CC ${state.subtitleLanguage}"
}

/** The chip names the selected track, so a dub is visible without opening the sheet. */
private fun audioLabel(state: PlayerUiState, strings: Strings): String {
    val base = strings.text("TXT_ANDROID_PLAYER_AUDIO_TRACK")
    return state.audioTrackLabel?.takeIf { it.isNotBlank() }?.let { "$base $it" } ?: base
}

private fun speedLabel(speed: Float): String {
    val rounded = (speed * 100).toInt() / 100f
    val text = if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString()
    return "$text\u00d7"
}

private suspend fun report(
    outcome: LaunchOutcome,
    strings: Strings,
    snackbar: SnackbarHostState,
) {
    when (outcome) {
        LaunchOutcome.Started -> Unit
        is LaunchOutcome.Failed -> {
            snackbar.showSnackbar(outcome.describe(strings))
        }
    }
}
