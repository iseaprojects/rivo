package io.github.iseaprojects.rivo.core.menu

/**
 * Update-checker state as the Miscellaneous screen sees it.
 *
 * The checker talks to GitHub on IO and may be offline; the menu only reads this snapshot,
 * so the Update row can say "checking", "up to date" or name the newer release without
 * performing network I/O while a frame is being resolved.
 */
data class MenuUpdates(
    val state: MenuUpdateState = MenuUpdateState.IDLE,
    /** Version this build reports (`BuildConfig.RIVO_VERSION`). */
    val currentVersion: String = "",
    val latestVersion: String? = null,
    val releaseName: String? = null,
    val notes: String? = null,
    val url: String? = null,
    val publishedAt: String? = null,
    val message: String? = null,
    /** Wall clock of the last completed check, 0 when never checked. */
    val checkedAt: Long = 0,
) {
    val hasUpdate: Boolean get() = state == MenuUpdateState.AVAILABLE

    companion object {
        val EMPTY = MenuUpdates()
    }
}

enum class MenuUpdateState {
    IDLE,
    CHECKING,
    CURRENT,
    AVAILABLE,
    FAILED,
}
