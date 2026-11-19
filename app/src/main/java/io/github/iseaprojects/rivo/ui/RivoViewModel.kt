package io.github.iseaprojects.rivo.ui

import androidx.lifecycle.ViewModel
import io.github.iseaprojects.rivo.core.launch.LaunchInbox
import io.github.iseaprojects.rivo.core.menu.MenuIds
import io.github.iseaprojects.rivo.core.menu.MenuNavController

/**
 * Survives configuration changes (rotation, theme change, DeX docking) so the menu
 * back stack and the intent inbox are not rebuilt from scratch. Process death still
 * resets both, which is the intended behaviour: the last deep link is re-applied by
 * `MainActivity`.
 */
class RivoViewModel : ViewModel() {
    val nav = MenuNavController(MenuIds.MAIN)
    val inbox = LaunchInbox()
}
