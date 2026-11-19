package io.github.iseaprojects.rivo.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.iseaprojects.rivo.core.theme.ThemeSpec

/**
 * Material 3 with a theme-extension palette. Dynamic color (Material You) is
 * deliberately disabled: the app's identity is the imported Tokyo Night /
 * Catppuccin scheme, and wallpaper colors would make previews unpredictable.
 */
@Composable
fun RivoTheme(
    theme: ThemeSpec,
    fontFamily: FontFamily = FontFamily.Monospace,
    content: @Composable () -> Unit,
) {
    val colorScheme = remember(theme) { theme.toColorScheme() }
    val typography = remember(fontFamily) { rivoTypography(fontFamily) }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = content,
    )
}

fun ThemeSpec.toColorScheme(): ColorScheme {
    val background = color("background", Color(0xFF16161E))
    val surface = color("surface", background)
    val onBackground = color("onBackground", Color(0xFFC0CAF5))
    val primary = color("primary", Color(0xFF7AA2F7))
    val secondary = color("secondary", Color(0xFF2AC3DE))
    val tertiary = color("tertiary", Color(0xFFBB9AF7))
    val error = color("error", Color(0xFFF7768E))
    val outline = color("outline", Color(0xFF545C7E))
    val outlineVariant = color("outlineVariant", outline.copy(alpha = 0.5f))
    val onSurfaceVariant = color("onSurfaceVariant", Color(0xFFA9B1D6))

    val tokens = mapOf(
        "primary" to primary,
        "onPrimary" to color("onPrimary", background),
        "primaryContainer" to color("primaryContainer", surface),
        "onPrimaryContainer" to color("onPrimaryContainer", onBackground),
        "inversePrimary" to color("inversePrimary", primary),
        "secondary" to secondary,
        "onSecondary" to color("onSecondary", background),
        "secondaryContainer" to color("secondaryContainer", surface),
        "onSecondaryContainer" to color("onSecondaryContainer", onBackground),
        "tertiary" to tertiary,
        "onTertiary" to color("onTertiary", background),
        "tertiaryContainer" to color("tertiaryContainer", surface),
        "onTertiaryContainer" to color("onTertiaryContainer", onBackground),
        "background" to background,
        "onBackground" to onBackground,
        "surface" to surface,
        "onSurface" to color("onSurface", onBackground),
        "surfaceVariant" to color("surfaceVariant", surface),
        "onSurfaceVariant" to onSurfaceVariant,
        "surfaceTint" to color("surfaceTint", primary),
        "inverseSurface" to color("inverseSurface", onBackground),
        "inverseOnSurface" to color("inverseOnSurface", background),
        "error" to error,
        "onError" to color("onError", background),
        "errorContainer" to color("errorContainer", surface),
        "onErrorContainer" to color("onErrorContainer", error),
        "outline" to outline,
        "outlineVariant" to outlineVariant,
        "scrim" to color("scrim", Color.Black),
        "surfaceDim" to color("surfaceDim", background),
        "surfaceBright" to color("surfaceBright", surface),
        "surfaceContainerLowest" to color("surfaceContainerLowest", background),
        "surfaceContainerLow" to color("surfaceContainerLow", background),
        "surfaceContainer" to color("surfaceContainer", surface),
        "surfaceContainerHigh" to color("surfaceContainerHigh", surface),
        "surfaceContainerHighest" to color("surfaceContainerHighest", surface),
    )

    return if (dark) {
        darkColorScheme(
            primary = tokens.getValue("primary"),
            onPrimary = tokens.getValue("onPrimary"),
            primaryContainer = tokens.getValue("primaryContainer"),
            onPrimaryContainer = tokens.getValue("onPrimaryContainer"),
            inversePrimary = tokens.getValue("inversePrimary"),
            secondary = tokens.getValue("secondary"),
            onSecondary = tokens.getValue("onSecondary"),
            secondaryContainer = tokens.getValue("secondaryContainer"),
            onSecondaryContainer = tokens.getValue("onSecondaryContainer"),
            tertiary = tokens.getValue("tertiary"),
            onTertiary = tokens.getValue("onTertiary"),
            tertiaryContainer = tokens.getValue("tertiaryContainer"),
            onTertiaryContainer = tokens.getValue("onTertiaryContainer"),
            background = tokens.getValue("background"),
            onBackground = tokens.getValue("onBackground"),
            surface = tokens.getValue("surface"),
            onSurface = tokens.getValue("onSurface"),
            surfaceVariant = tokens.getValue("surfaceVariant"),
            onSurfaceVariant = tokens.getValue("onSurfaceVariant"),
            surfaceTint = tokens.getValue("surfaceTint"),
            inverseSurface = tokens.getValue("inverseSurface"),
            inverseOnSurface = tokens.getValue("inverseOnSurface"),
            error = tokens.getValue("error"),
            onError = tokens.getValue("onError"),
            errorContainer = tokens.getValue("errorContainer"),
            onErrorContainer = tokens.getValue("onErrorContainer"),
            outline = tokens.getValue("outline"),
            outlineVariant = tokens.getValue("outlineVariant"),
            scrim = tokens.getValue("scrim"),
            surfaceDim = tokens.getValue("surfaceDim"),
            surfaceBright = tokens.getValue("surfaceBright"),
            surfaceContainerLowest = tokens.getValue("surfaceContainerLowest"),
            surfaceContainerLow = tokens.getValue("surfaceContainerLow"),
            surfaceContainer = tokens.getValue("surfaceContainer"),
            surfaceContainerHigh = tokens.getValue("surfaceContainerHigh"),
            surfaceContainerHighest = tokens.getValue("surfaceContainerHighest"),
        )
    } else {
        lightColorScheme(
            primary = tokens.getValue("primary"),
            onPrimary = tokens.getValue("onPrimary"),
            primaryContainer = tokens.getValue("primaryContainer"),
            onPrimaryContainer = tokens.getValue("onPrimaryContainer"),
            inversePrimary = tokens.getValue("inversePrimary"),
            secondary = tokens.getValue("secondary"),
            onSecondary = tokens.getValue("onSecondary"),
            secondaryContainer = tokens.getValue("secondaryContainer"),
            onSecondaryContainer = tokens.getValue("onSecondaryContainer"),
            tertiary = tokens.getValue("tertiary"),
            onTertiary = tokens.getValue("onTertiary"),
            tertiaryContainer = tokens.getValue("tertiaryContainer"),
            onTertiaryContainer = tokens.getValue("onTertiaryContainer"),
            background = tokens.getValue("background"),
            onBackground = tokens.getValue("onBackground"),
            surface = tokens.getValue("surface"),
            onSurface = tokens.getValue("onSurface"),
            surfaceVariant = tokens.getValue("surfaceVariant"),
            onSurfaceVariant = tokens.getValue("onSurfaceVariant"),
            surfaceTint = tokens.getValue("surfaceTint"),
            inverseSurface = tokens.getValue("inverseSurface"),
            inverseOnSurface = tokens.getValue("inverseOnSurface"),
            error = tokens.getValue("error"),
            onError = tokens.getValue("onError"),
            errorContainer = tokens.getValue("errorContainer"),
            onErrorContainer = tokens.getValue("onErrorContainer"),
            outline = tokens.getValue("outline"),
            outlineVariant = tokens.getValue("outlineVariant"),
            scrim = tokens.getValue("scrim"),
            surfaceDim = tokens.getValue("surfaceDim"),
            surfaceBright = tokens.getValue("surfaceBright"),
            surfaceContainerLowest = tokens.getValue("surfaceContainerLowest"),
            surfaceContainerLow = tokens.getValue("surfaceContainerLow"),
            surfaceContainer = tokens.getValue("surfaceContainer"),
            surfaceContainerHigh = tokens.getValue("surfaceContainerHigh"),
            surfaceContainerHighest = tokens.getValue("surfaceContainerHighest"),
        )
    }
}

/** Terminal accent colors preserved from yt-x's THEME_FZF_* variables. */
fun ThemeSpec.terminalColor(token: String, fallback: Color): Color =
    terminalArgb(token, fallbackToken = "iconPrimary")?.let { Color(it) } ?: fallback

fun ThemeSpec.iconColor(fallback: Color): Color =
    terminalArgb("iconPrimary", fallbackToken = "previewKey")?.let { Color(it) } ?: fallback

fun ThemeSpec.errorColor(fallback: Color): Color =
    terminalArgb("iconError")?.let { Color(it) } ?: fallback

fun rivoTypography(fontFamily: FontFamily): Typography = Typography(
    titleLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.2.sp,
    ),
)

private fun ThemeSpec.color(token: String, fallback: Color): Color =
    colorArgb(token)?.let { Color(it) } ?: fallback
