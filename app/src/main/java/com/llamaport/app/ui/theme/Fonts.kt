package com.llamaport.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily

/**
 * Brand pixel font, loaded once from `assets/fonts` at runtime.
 *
 * The bundled Press Start 2P (SIL OFL 1.1, see `assets/fonts/OFL.txt`) covers
 * Cyrillic and the digits used by the numeric readouts. It is used only for
 * the wordmark and for numbers; body text stays on the system font.
 */
val LocalPixelFont = staticCompositionLocalOf<FontFamily> { FontFamily.Monospace }

@Composable
fun rememberPixelFontFamily(): FontFamily {
    val assets = LocalContext.current.assets
    return remember(assets) {
        runCatching {
            FontFamily(Font("fonts/PressStart2P-Regular.ttf", assets))
        }.getOrElse { FontFamily.Monospace }
    }
}
