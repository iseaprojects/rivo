package io.github.iseaprojects.rivo.core.menu

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A point in the menu back stack. This is the Android equivalent of yt-x's
 * `STATE_CURRENT` + `state.env` snapshots: pusheable, poppable, and carrying the
 * payload that the next screen needs.
 *
 * [nodes] is non-null only for inline submenus (extension injections and `children`
 * in a MenuNode). Otherwise the frame is resolved from the catalog by [menuId].
 */
data class MenuFrame(
    val menuId: String,
    val title: String = "",
    val payload: MenuPayload = MenuPayload.None,
    val nodes: List<MenuNode>? = null,
)

interface MenuNavigator {
    fun open(
        menuId: String,
        payload: MenuPayload = MenuPayload.None,
        title: String = "",
        nodes: List<MenuNode>? = null,
    )

    /** Returns false when the stack is already at its root. */
    fun back(): Boolean

    fun exit()
}

class MenuNavController(rootMenuId: String = MenuIds.MAIN) : MenuNavigator {

    private val _stack = MutableStateFlow(listOf(MenuFrame(rootMenuId)))
    val stack: StateFlow<List<MenuFrame>> = _stack.asStateFlow()

    private val _exits = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val exits: SharedFlow<Unit> = _exits.asSharedFlow()

    val current: MenuFrame get() = _stack.value.last()

    override fun open(menuId: String, payload: MenuPayload, title: String, nodes: List<MenuNode>?) {
        if (menuId.isBlank()) return
        _stack.update { frames -> frames + MenuFrame(menuId, title, payload, nodes) }
    }

    override fun back(): Boolean {
        if (_stack.value.size <= 1) return false
        _stack.update { frames -> frames.dropLast(1) }
        return true
    }

    /** Breadcrumb navigation: jump back to [index] (0 = root). */
    fun popTo(index: Int): Boolean {
        val safe = index.coerceAtLeast(0)
        if (safe >= _stack.value.lastIndex) return false
        _stack.update { frames -> frames.take(safe + 1) }
        return true
    }

    fun replaceRoot(
        menuId: String,
        payload: MenuPayload = MenuPayload.None,
        title: String = "",
        nodes: List<MenuNode>? = null,
    ) {
        _stack.value = listOf(MenuFrame(menuId, title, payload, nodes))
    }

    /** Fills the placeholder title of the root frame once the catalog is resolved. */
    fun setRootTitle(title: String) {
        _stack.update { frames ->
            if (frames.isEmpty()) frames
            else listOf(frames.first().copy(title = title)) + frames.drop(1)
        }
    }

    override fun exit() {
        _exits.tryEmit(Unit)
    }
}
