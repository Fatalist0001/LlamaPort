package com.llamaport.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/*
 * Obsidian palette. Every colour in the app comes from here or from the mapped
 * Material color scheme, so screens never hard-code brand greys.
 */

/* ---- Obsidian (dark, primary identity) -------------------------------- */
internal val ObsidianBlack = Color(0xFF08090B)
internal val ObsidianSurface = Color(0xFF111317)
internal val ObsidianCard = Color(0xFF171A1F)
internal val ObsidianNested = Color(0xFF20242A)
internal val ObsidianElevated = Color(0xFF292D34)

internal val ObsidianText = Color(0xFFE8E9ED)
internal val ObsidianTextSecondary = Color(0xFF999DA7)
internal val ObsidianTextMuted = Color(0xFF686D77)

internal val ObsidianSilver = Color(0xFFD7D9E0)
internal val ObsidianSilverBright = Color(0xFFF1F2F5)
internal val ObsidianSilverDim = Color(0xFF8B909B)

internal val ObsidianSuccess = Color(0xFF9BC9AE)
internal val ObsidianWarning = Color(0xFFD9B77E)
internal val ObsidianError = Color(0xFFD98C91)

internal val ObsidianHairline = Color(0xFF2A2E36)
internal val ObsidianHairlineStrong = Color(0xFF3C414A)
internal val ObsidianCodeBackground = Color(0xFF0B0C0F)
internal val ObsidianCodeBorder = Color(0xFF2A2E36)

/* ---- Obsidian Daylight (system light mode) --------------------------- */
internal val DaylightBackground = Color(0xFFF1F1F4)
internal val DaylightSurface = Color(0xFFF8F8FA)
internal val DaylightCard = Color(0xFFFFFFFF)
internal val DaylightNested = Color(0xFFEAEBEF)
internal val DaylightElevated = Color(0xFFDFE1E6)

internal val DaylightText = Color(0xFF131418)
internal val DaylightTextSecondary = Color(0xFF5D6068)
internal val DaylightTextMuted = Color(0xFF8B8E96)

internal val DaylightSilver = Color(0xFF3F444D)
internal val DaylightSilverBright = Color(0xFF141519)
internal val DaylightSilverDim = Color(0xFF6B707A)

internal val DaylightSuccess = Color(0xFF3E7D5B)
internal val DaylightWarning = Color(0xFF916A2A)
internal val DaylightError = Color(0xFFA24B53)

internal val DaylightHairline = Color(0xFFD9DBE0)
internal val DaylightHairlineStrong = Color(0xFFC2C5CB)

/**
 * Brand colours that do not map cleanly onto Material 3 slots: hairlines,
 * success/warning, code surfaces, the polished Core and the silver gradients.
 */
@Immutable
data class ObsidianPalette(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val card: Color,
    val nested: Color,
    val elevated: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val silver: Color,
    val silverBright: Color,
    val silverDim: Color,
    val hairline: Color,
    val hairlineStrong: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val codeBackground: Color,
    val codeBorder: Color,
    val codeText: Color,
    val codeMuted: Color,
    val codeString: Color,
    val codeNumber: Color,
    val codeKeyword: Color,
    val codeFunction: Color,
    val codeInlineBackground: Color,
    val coreTop: Color,
    val coreFace: Color,
    val coreSide: Color,
    val coreEdge: Color,
    val coreGlow: Color,
)

internal val ObsidianDarkPalette = ObsidianPalette(
    isDark = true,
    background = ObsidianBlack,
    surface = ObsidianSurface,
    card = ObsidianCard,
    nested = ObsidianNested,
    elevated = ObsidianElevated,
    textPrimary = ObsidianText,
    textSecondary = ObsidianTextSecondary,
    textMuted = ObsidianTextMuted,
    silver = ObsidianSilver,
    silverBright = ObsidianSilverBright,
    silverDim = ObsidianSilverDim,
    hairline = ObsidianHairline,
    hairlineStrong = ObsidianHairlineStrong,
    success = ObsidianSuccess,
    warning = ObsidianWarning,
    error = ObsidianError,
    codeBackground = ObsidianCodeBackground,
    codeBorder = ObsidianCodeBorder,
    codeText = ObsidianText,
    codeMuted = Color(0xFF6F747E),
    codeString = Color(0xFFA9C7B4),
    codeNumber = Color(0xFFC9B38C),
    codeKeyword = Color(0xFFD7D9E0),
    codeFunction = Color(0xFFB7BBC4),
    codeInlineBackground = Color(0xFF292D34),
    coreTop = Color(0xFF25292F),
    coreFace = Color(0xFF121418),
    coreSide = Color(0xFF07080A),
    coreEdge = Color(0xFFC9CCD4),
    coreGlow = Color(0xFF9EA4AF),
)

internal val ObsidianLightPalette = ObsidianPalette(
    isDark = false,
    background = DaylightBackground,
    surface = DaylightSurface,
    card = DaylightCard,
    nested = DaylightNested,
    elevated = DaylightElevated,
    textPrimary = DaylightText,
    textSecondary = DaylightTextSecondary,
    textMuted = DaylightTextMuted,
    silver = DaylightSilver,
    silverBright = DaylightSilverBright,
    silverDim = DaylightSilverDim,
    hairline = DaylightHairline,
    hairlineStrong = DaylightHairlineStrong,
    success = DaylightSuccess,
    warning = DaylightWarning,
    error = DaylightError,
    // Code and the Core remain polished black in the daylight theme.
    codeBackground = ObsidianCodeBackground,
    codeBorder = ObsidianCodeBorder,
    codeText = ObsidianText,
    codeMuted = Color(0xFF6F747E),
    codeString = Color(0xFFA9C7B4),
    codeNumber = Color(0xFFC9B38C),
    codeKeyword = Color(0xFFD7D9E0),
    codeFunction = Color(0xFFB7BBC4),
    codeInlineBackground = Color(0xFF292D34),
    coreTop = Color(0xFF25292F),
    coreFace = Color(0xFF101216),
    coreSide = Color(0xFF060708),
    coreEdge = Color(0xFFB9BDC6),
    coreGlow = Color(0xFF7F8590),
)