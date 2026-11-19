package io.github.iseaprojects.rivo.ui.media

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.iseaprojects.rivo.data.extract.ChannelDetails
import io.github.iseaprojects.rivo.data.extract.ContinueToken
import io.github.iseaprojects.rivo.data.extract.ExtractorException
import io.github.iseaprojects.rivo.data.extract.ExtractorFailures
import io.github.iseaprojects.rivo.data.extract.PlaylistDetails
import io.github.iseaprojects.rivo.data.paging.PageWindow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Paging + loading state for one data screen.
 *
 * The backends page in their own sizes (NewPipe ~20, yt-dlp `CONFIG_PER_PAGE`), while
 * the UI shows windows of exactly `CONFIG_PER_PAGE`. Items land in a [PageWindow] that
 * re-chunks them, so Previous is instant and never refetches, and Next only touches the
 * network when the buffer cannot fill a window.
 */
@Stable
class MediaListState(
    private val loader: MediaLoader,
    private val scope: CoroutineScope,
    private val pageSize: () -> Int,
) {
    var rows by mutableStateOf<List<MediaEntry>>(emptyList())
        private set

    var loading by mutableStateOf(false)
        private set

    /** Set when the last request failed; cleared by the next successful one. */
    var failure by mutableStateOf<ExtractorException?>(null)
        private set

    var page by mutableIntStateOf(1)
        private set

    /** Known only once the backend stops returning tokens. */
    var pageCount by mutableStateOf<Int?>(null)
        private set

    var totalCount by mutableStateOf<Long?>(null)
        private set

    var hasPrevious by mutableStateOf(false)
        private set

    var hasNext by mutableStateOf(false)
        private set

    var channel by mutableStateOf<ChannelDetails?>(null)
        private set

    var playlist by mutableStateOf<PlaylistDetails?>(null)
        private set

    private var source: MediaSource? = null
    private var token: ContinueToken? = null
    private var window: PageWindow<MediaEntry>? = null
    private var job: Job? = null

    /** True once a request has completed, so "empty" can be told apart from "not yet". */
    var loadedOnce by mutableStateOf(false)
        private set

    fun open(next: MediaSource) {
        if (next == source) return
        source = next
        reset()
        load(advance = false)
    }

    fun retry() {
        if (source == null) return
        reset()
        load(advance = false)
    }

    fun nextPage() {
        val window = window ?: return
        if (loading) return
        when {
            window.bufferedCount() >= pageSize() -> {
                window.issue()
                publish()
            }
            token != null -> load(advance = true)
            window.bufferedCount() > 0 -> {
                window.issue()
                publish()
            }
        }
    }

    fun previousPage() {
        val window = window ?: return
        if (window.move(-1) != null) publish()
    }

    /** Cancels any in-flight request; called when the screen leaves composition. */
    fun close() {
        job?.cancel()
        job = null
    }

    private fun reset() {
        job?.cancel()
        job = null
        token = null
        window = PageWindow(pageSize()) { it.key }
        rows = emptyList()
        page = 1
        pageCount = null
        totalCount = null
        hasPrevious = false
        hasNext = false
        failure = null
        loadedOnce = false
        loading = true
    }

    private fun load(advance: Boolean) {
        val source = source ?: return
        val window = window ?: return
        job?.cancel()
        loading = true
        failure = null
        job = scope.launch {
            try {
                val result = loader.load(source, token)
                token = result.page.nextToken
                result.channel?.let { channel = it }
                result.playlist?.let { playlist = it }
                result.page.totalCount?.let { totalCount = it }
                window.append(result.page.entries())
                if (advance || window.pageCount == 0) window.issue()
                loading = false
                loadedOnce = true
                publish()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                loading = false
                loadedOnce = true
                failure = ExtractorFailures.fromThrowable(error)
                publish()
            }
        }
    }

    private fun publish() {
        val window = window ?: return
        rows = window.current()
        page = window.pageIndex + 1
        hasPrevious = window.hasPrevious
        hasNext = window.bufferedCount() > 0 || token != null
        pageCount = if (token == null) window.pageCount else null
    }
}
