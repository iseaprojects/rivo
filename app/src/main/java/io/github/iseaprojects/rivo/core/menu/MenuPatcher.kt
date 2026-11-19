package io.github.iseaprojects.rivo.core.menu

import io.github.iseaprojects.rivo.core.menu.extension.UiMenuPatch

/**
 * Declarative menu transforms. This is the port of yt-x's
 * `_util_menu_sort` + `grep --invert-match` + injected `extra_actions`:
 *
 *  - [UiMenuPatch.sort] is an ordered list of node ids or 1-based indexes.
 *  - [UiMenuPatch.filter] is a regular expression matched against id and label key.
 *  - [UiMenuPatch.inject] adds nodes at `start`, `end`, `before:<id>`,
 *    `after:<id>` or a numeric index.
 *
 * Unlisted nodes keep their relative order and follow the explicit ones unless
 * [UiMenuPatch.dropUnlisted] is set.
 */
object MenuPatcher {

    fun applyAll(nodes: List<MenuNode>, patches: List<UiMenuPatch>): List<MenuNode> =
        patches.fold(nodes) { current, patch -> apply(current, patch) }

    fun apply(nodes: List<MenuNode>, patch: UiMenuPatch): List<MenuNode> {
        var result = nodes
        patch.filter?.let { regex ->
            result = result.filterNot { node -> matches(regex, node) }
        }
        patch.sort?.let { order ->
            result = sort(result, order, patch.dropUnlisted)
        }
        if (patch.inject.isNotEmpty()) {
            result = inject(result, patch.inject)
        }
        return result
    }

    fun sort(
        nodes: List<MenuNode>,
        order: List<String>,
        dropUnlisted: Boolean,
    ): List<MenuNode> {
        if (order.isEmpty()) return nodes
        val remaining = nodes.toMutableList()
        val ordered = ArrayList<MenuNode>(nodes.size)
        order.forEach { token ->
            val match = remaining.firstOrNull { it.id == token }
                ?: token.toIntOrNull()?.let { index -> remaining.getOrNull(index - 1) }
            if (match != null) {
                ordered += match
                remaining.remove(match)
            }
        }
        if (!dropUnlisted) ordered += remaining
        return ordered
    }

    private fun inject(nodes: List<MenuNode>, injected: List<MenuNode>): List<MenuNode> {
        val result = nodes.toMutableList()
        injected.forEach { node ->
            val index = positionIndex(node.position, result)
            result.add(index, node)
        }
        return result
    }

    private fun positionIndex(position: String?, nodes: List<MenuNode>): Int {
        if (position.isNullOrBlank() || position == "end") return nodes.size
        if (position == "start") return 0
        if (position.startsWith("before:")) {
            val id = position.removePrefix("before:")
            val index = nodes.indexOfFirst { it.id == id }
            return if (index >= 0) index else nodes.size
        }
        if (position.startsWith("after:")) {
            val id = position.removePrefix("after:")
            val index = nodes.indexOfFirst { it.id == id }
            return if (index >= 0) index + 1 else nodes.size
        }
        return position.toIntOrNull()?.coerceIn(0, nodes.size) ?: nodes.size
    }

    private fun matches(regex: Regex, node: MenuNode): Boolean {
        if (regex.containsMatchIn(node.id)) return true
        val key = node.labelKey ?: return false
        return regex.containsMatchIn(key)
    }
}
