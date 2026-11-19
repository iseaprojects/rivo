package io.github.iseaprojects.rivo.core.ui

/** Actions a hardware key can trigger on a list screen. */
enum class KeyAction {
    DOWN,
    UP,
    HALF_PAGE_DOWN,
    HALF_PAGE_UP,
    SELECT,
    BACK,
    SEARCH,
    PREVIEW,
    CLEAR,
    NEXT_PAGE,
    PREVIOUS_PAGE,
}

data class KeyBinding(
    val key: String,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val action: KeyAction,
)

/**
 * Parser for `CONFIG_KEYBINDINGS` (upstream format: `j:down,k:up,ctrl-d:half-page-down`).
 *
 * The filter field always has focus, so plain letters cannot be captured without
 * stealing typing. Unmodified single-character bindings therefore fire only while the
 * filter is empty, which keeps both fzf muscle memory (`j`/`k`) and text entry working.
 * Modified bindings, arrows, Enter, Escape and PageUp/PageDown work at any time.
 */
class KeyBindings private constructor(private val bindings: List<KeyBinding>) {

    fun actionFor(key: String, ctrl: Boolean, alt: Boolean, shift: Boolean): KeyAction? {
        val normalized = key.lowercase()
        return bindings.firstOrNull {
            it.key == normalized &&
                it.ctrl == ctrl &&
                it.alt == alt &&
                it.shift == shift
        }?.action
    }

    /** True for bindings made of one printable character, which must not block typing. */
    fun isPlainCharacter(action: KeyAction): Boolean = bindings.any { binding ->
        binding.action == action &&
            !binding.ctrl &&
            !binding.alt &&
            !binding.shift &&
            (binding.key.length == 1 && binding.key[0].isLetterOrDigit() || binding.key == "space")
    }

    fun bindings(): List<KeyBinding> = bindings

    companion object {

        const val DEFAULT_SPEC =
            "j:down,k:up,ctrl-d:half-page-down,ctrl-u:half-page-up," +
                "enter:select,esc:back,/:search,space:preview"

        val DEFAULT: KeyBindings = parse(DEFAULT_SPEC)

        fun parse(spec: String): KeyBindings {
            val parsed = spec.split(',')
                .mapNotNull(::parseBinding)
                .distinctBy { Triple(it.key, modifierBits(it), it.action) }
            return KeyBindings(parsed)
        }

        private fun modifierBits(binding: KeyBinding): Int =
            (if (binding.ctrl) 1 else 0) or (if (binding.alt) 2 else 0) or (if (binding.shift) 4 else 0)

        private fun parseBinding(entry: String): KeyBinding? {
            // The separator is the last colon: a binding for "/" (`/:search`) starts with
            // one, and action names never contain a colon.
            val split = entry.lastIndexOf(':')
            if (split <= 0 || split == entry.lastIndex) return null
            val combo = entry.substring(0, split).trim().lowercase()
            val action = actionOf(entry.substring(split + 1).trim().lowercase()) ?: return null
            if (combo.isEmpty()) return null

            var rest = combo
            var ctrl = false
            var alt = false
            var shift = false
            while (true) {
                when {
                    rest.startsWith("ctrl-") -> { ctrl = true; rest = rest.removePrefix("ctrl-") }
                    rest.startsWith("alt-") -> { alt = true; rest = rest.removePrefix("alt-") }
                    rest.startsWith("shift-") -> { shift = true; rest = rest.removePrefix("shift-") }
                    else -> break
                }
            }
            val key = canonicalKey(rest) ?: return null
            return KeyBinding(key, ctrl, alt, shift, action)
        }

        private fun canonicalKey(value: String): String? {
            if (value.isEmpty()) return null
            if (value == "/") return "/"
            val named = when (value) {
                "arrow-down", "down" -> "down"
                "arrow-up", "up" -> "up"
                "arrow-left", "left" -> "left"
                "arrow-right", "right" -> "right"
                "enter", "return" -> "enter"
                "esc", "escape" -> "esc"
                "space", "spacebar" -> "space"
                "page-down", "pagedown", "pgdn" -> "page-down"
                "page-up", "pageup", "pgup" -> "page-up"
                "tab" -> "tab"
                "home" -> "home"
                "end" -> "end"
                "backspace" -> "backspace"
                "delete" -> "delete"
                else -> null
            }
            named?.let { return it }
            val single = value.removePrefix("/")
            return when {
                single.length == 1 -> single
                else -> null
            }
        }

        private fun actionOf(value: String): KeyAction? = when (value) {
            "down", "next" -> KeyAction.DOWN
            "up", "prev", "previous" -> KeyAction.UP
            "half-page-down", "half-page" -> KeyAction.HALF_PAGE_DOWN
            "half-page-up" -> KeyAction.HALF_PAGE_UP
            "select", "enter", "open" -> KeyAction.SELECT
            "back" -> KeyAction.BACK
            "search", "filter" -> KeyAction.SEARCH
            "preview", "peek" -> KeyAction.PREVIEW
            "clear" -> KeyAction.CLEAR
            "next-page" -> KeyAction.NEXT_PAGE
            "previous-page", "prev-page" -> KeyAction.PREVIOUS_PAGE
            else -> null
        }
    }
}
