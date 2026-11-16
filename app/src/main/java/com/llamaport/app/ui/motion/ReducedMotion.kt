package com.llamaport.app.ui.motion

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * True when the user removed animations at the OS level
 * (Settings > Accessibility > Remove animations, i.e. animator scale 0).
 *
 * Decorative motion is disabled in that case; short fades remain
 * so that state changes stay readable.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

private fun readAnimatorScale(context: android.content.Context): Float = runCatching {
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
}.getOrDefault(1f)

/**
 * Reads the system animator scale and keeps following it: toggling
 * "Remove animations" in system settings updates the UI immediately,
 * without restarting the app.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(readAnimatorScale(context)) }

    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scale = readAnimatorScale(context)
            }
        }
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        context.contentResolver.registerContentObserver(uri, false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }

    return scale == 0f
}

/** Provides [LocalReducedMotion] for the subtree. */
@Composable
fun ProvideReducedMotion(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalReducedMotion provides rememberReducedMotion()) {
        content()
    }
}
