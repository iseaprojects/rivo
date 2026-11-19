package io.github.iseaprojects.rivo.core.menu

/**
 * A menu registered by the app. `build` is invoked on every resolution, so a menu can
 * depend on config, account state or payload without caching stale nodes.
 */
class MenuDefinition(
    val id: String,
    val titleKey: String? = null,
    val title: String? = null,
    val build: (MenuContext) -> List<MenuNode>,
)

class MenuCatalog(definitions: List<MenuDefinition>) {

    private val byId: Map<String, MenuDefinition> = definitions.associateBy { it.id }

    operator fun get(menuId: String): MenuDefinition? = byId[menuId]

    fun contains(menuId: String): Boolean = byId.containsKey(menuId)

    val ids: Set<String> get() = byId.keys

    fun definitions(): Collection<MenuDefinition> = byId.values

    companion object {
        fun of(vararg definitions: MenuDefinition): MenuCatalog =
            MenuCatalog(definitions.toList())
    }
}
