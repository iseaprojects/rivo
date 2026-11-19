package io.github.iseaprojects.rivo.core.commands

import kotlinx.serialization.Serializable

/**
 * One entry of `my/cmds.json`, the Android counterpart of `~/.config/yt-x/my/cmds.json`.
 *
 * [id] is local (upstream keys commands by name); keeping it stable means renaming a
 * command does not break the navigation frame or the action menu that is open on it.
 */
@Serializable
data class StoredCommand(
    val id: String,
    val name: String,
    val url: String,
    val options: String = "",
)
