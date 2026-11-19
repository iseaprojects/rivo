package io.github.iseaprojects.rivo.ui.theme

import android.content.Context
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * Nerd Font loading.
 *
 * The patched font (JetBrainsMono NF) is ~2 MB of glyphs and is not vendored in this
 * repository; drop `JetBrainsMonoNerdFont-Regular.ttf` (or any other Nerd Font
 * variant) into `app/src/main/assets/fonts/` and it is picked up automatically.
 * Without it the app stays on the system monospace face and the ASCII icon fallback
 * (`IconCatalog`) keeps every menu legible.
 *
 * Loading from assets instead of `res/font` means a missing file is a supported
 * state rather than a build break.
 */
class FontRepository(context: Context) {

    private val assets = context.assets

    val nerdFontFamily: FontFamily? by lazy {
        val files = runCatching { assets.list(DIR)?.toList().orEmpty() }
            .getOrDefault(emptyList())
        val regular = files
            .filter { it.endsWith(".ttf", ignoreCase = true) && !it.contains("bold", ignoreCase = true) }
            .sortedBy { it.length }
            .firstOrNull()
            ?: files.firstOrNull { it.endsWith(".ttf", ignoreCase = true) }
            ?: return@lazy null
        val bold = files.firstOrNull { it.contains("bold", ignoreCase = true) && it.endsWith(".ttf", true) }
        runCatching {
            if (bold != null) {
                FontFamily(
                    Font("$DIR/$regular", assets, FontWeight.Normal),
                    Font("$DIR/$bold", assets, FontWeight.Bold),
                )
            } else {
                FontFamily(Font("$DIR/$regular", assets))
            }
        }.getOrNull()
    }

    /** True when a patched Nerd Font is actually packaged in this APK. */
    val hasNerdFont: Boolean
        get() = nerdFontFamily != null

    private companion object {
        const val DIR = "fonts"
    }
}
