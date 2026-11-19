package com.llamaport.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * Obsidian is a fixed brand identity, not a wallpaper-derived Material You
 * theme. On Android 12+ the old build followed the system accent, which could
 * turn every silver surface purple or blue; that is intentionally gone.
 */

private val ObsidianDarkScheme = darkColorScheme(
    primary = ObsidianSilver,
    onPrimary = Color(0xFF111214),
    primaryContainer = ObsidianNested,
    onPrimaryContainer = ObsidianText,
    inversePrimary = Color(0xFF5B606A),
    secondary = Color(0xFFB7BAC3),
    onSecondary = Color(0xFF111214),
    secondaryContainer = ObsidianNested,
    onSecondaryContainer = ObsidianText,
    tertiary = ObsidianSuccess,
    onTertiary = Color(0xFF0D1712),
    tertiaryContainer = Color(0xFF21352A),
    onTertiaryContainer = Color(0xFFCBE6D6),
    error = ObsidianError,
    onError = Color(0xFF221012),
    errorContainer = Color(0xFF3A2024),
    onErrorContainer = Color(0xFFF2CED1),
    background = ObsidianBlack,
    onBackground = ObsidianText,
    surface = ObsidianBlack,
    onSurface = ObsidianText,
    surfaceVariant = ObsidianNested,
    onSurfaceVariant = ObsidianTextSecondary,
    surfaceTint = ObsidianSilver,
    inverseSurface = ObsidianText,
    inverseOnSurface = ObsidianBlack,
    outline = ObsidianHairlineStrong,
    outlineVariant = ObsidianHairline,
    scrim = Color(0xFF000000),
    surfaceBright = ObsidianElevated,
    surfaceDim = ObsidianBlack,
    surfaceContainerLowest = ObsidianBlack,
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = ObsidianCard,
    surfaceContainerHigh = ObsidianNested,
    surfaceContainerHighest = ObsidianElevated,
)

private val ObsidianLightScheme = lightColorScheme(
    primary = DaylightSilver,
    onPrimary = Color(0xFFF7F7F9),
    primaryContainer = DaylightNested,
    onPrimaryContainer = DaylightText,
    inversePrimary = Color(0xFFBFC3CB),
    secondary = Color(0xFF6B6F78),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = DaylightNested,
    onSecondaryContainer = DaylightText,
    tertiary = DaylightSuccess,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD8EADF),
    onTertiaryContainer = Color(0xFF153425),
    error = DaylightError,
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF6DDE0),
    onErrorContainer = Color(0xFF401115),
    background = DaylightBackground,
    onBackground = DaylightText,
    surface = DaylightBackground,
    onSurface = DaylightText,
    surfaceVariant = DaylightNested,
    onSurfaceVariant = DaylightTextSecondary,
    surfaceTint = DaylightSilver,
    inverseSurface = DaylightText,
    inverseOnSurface = DaylightBackground,
    outline = DaylightHairlineStrong,
    outlineVariant = DaylightHairline,
    scrim = Color(0xFF000000),
    surfaceBright = DaylightCard,
    surfaceDim = DaylightBackground,
    surfaceContainerLowest = DaylightCard,
    surfaceContainerLow = DaylightSurface,
    surfaceContainer = DaylightCard,
    surfaceContainerHigh = DaylightNested,
    surfaceContainerHighest = DaylightElevated,
)

/** Brand colours outside of the Material 3 slots. */
val LocalObsidianPalette = staticCompositionLocalOf { ObsidianDarkPalette }

/** Short accessor: `ObsidianTheme.palette.silver`. */
object ObsidianTheme {
    val palette: ObsidianPalette
        @Composable
        @ReadOnlyComposable
        get() = LocalObsidianPalette.current
}

/**
 * The app always uses the Obsidian scale; the system light setting selects the
 * daylight ink variant while code surfaces and the Core stay polished black.
 */
@Composable
fun LlamaPortTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) ObsidianDarkPalette else ObsidianLightPalette
    CompositionLocalProvider(LocalObsidianPalette provides palette) {
        MaterialTheme(
            colorScheme = if (darkTheme) ObsidianDarkScheme else ObsidianLightScheme,
            typography = LlamaTypography,
            shapes = LlamaShapes,
            content = content,
        )
    }
}