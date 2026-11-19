package io.github.iseaprojects.rivo.core.launch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

data class LaunchEvent(
    val id: Long,
    val spec: LaunchSpec,
)

/**
 * Ordered inbox for intents.
 *
 * A plain SharedFlow with `replay = 1` would re-deliver the last intent after a
 * configuration change and push a duplicate frame, so events carry a monotonic id
 * that the composable remembers with `rememberSaveable` and ignores once handled.
 */
class LaunchInbox {

    /**
     * Seeded from the clock so that ids stay distinct across process death, where the
     * inbox is recreated but the last handled id is restored from saved state. Without
     * that, a single deep link could be considered already handled after the process is
     * killed and the frame would never be pushed.
     */
    private val counter = AtomicLong(System.nanoTime())

    private val _events = MutableStateFlow<LaunchEvent?>(null)
    val events: StateFlow<LaunchEvent?> = _events.asStateFlow()

    val lastEvent: LaunchEvent? get() = _events.value

    fun push(spec: LaunchSpec): Long {
        val id = counter.incrementAndGet()
        _events.value = LaunchEvent(id, spec)
        return id
    }
}
