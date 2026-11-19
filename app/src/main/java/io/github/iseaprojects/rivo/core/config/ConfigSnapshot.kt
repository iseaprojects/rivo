package io.github.iseaprojects.rivo.core.config

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Immutable view of the config with typed accessors.
 *
 * Missing values never throw: the config is user-editable text and a typo must degrade
 * to the default, not crash the menu that happened to read it.
 */
class ConfigSnapshot(private val values: Map<String, String>) {

    fun raw(key: String): String? = values[key]

    fun string(key: String, default: String = ""): String =
        values[key]?.takeIf { it.isNotBlank() } ?: default

    fun int(key: String, default: Int): Int =
        values[key]?.trim()?.toIntOrNull() ?: default

    fun long(key: String, default: Long): Long =
        values[key]?.trim()?.toLongOrNull() ?: default

    fun bool(key: String, default: Boolean): Boolean {
        val value = values[key]?.trim()?.lowercase() ?: return default
        return when (value) {
            "1", "true", "yes", "on", "y" -> true
            "0", "false", "no", "off", "n" -> false
            else -> default
        }
    }

    fun list(key: String): List<String> =
        values[key].orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun withOverrides(overrides: Map<String, String>): ConfigSnapshot {
        if (overrides.isEmpty()) return this
        return ConfigSnapshot(values + overrides)
    }

    fun asMap(): Map<String, String> = values

    override fun equals(other: Any?): Boolean = other is ConfigSnapshot && other.values == values

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = "ConfigSnapshot(${values.size} keys)"
}

/**
 * Runtime-only overrides (intent extras, deep-link parameters, Tasker payloads).
 *
 * They never touch the config file, exactly like `YT_X_*` environment variables in
 * upstream yt-x, and are cleared when the process dies.
 */
class RuntimeConfig(
    private val base: ConfigStore,
    scope: CoroutineScope,
) {
    private val _overrides = MutableStateFlow<Map<String, String>>(emptyMap())
    val overrides: StateFlow<Map<String, String>> = _overrides.asStateFlow()

    val snapshot: StateFlow<ConfigSnapshot> =
        combine(base.state, _overrides) { snapshot, overrides ->
            snapshot.withOverrides(overrides)
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = base.snapshot().withOverrides(_overrides.value),
        )

    fun apply(values: Map<String, String>) {
        if (values.isEmpty()) return
        _overrides.value = _overrides.value + values
    }

    fun clear() {
        _overrides.value = emptyMap()
    }
}
