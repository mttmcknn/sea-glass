package dev.mttmcknn.seaglass

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

private val DefaultLightColorScheme = lightColorScheme()
private val DefaultDarkColorScheme = darkColorScheme()

/**
 * Resolve an Android Material 3 color scheme for use with MaterialTheme.
 *
 * Wallpaper colors are available on Android 12 (API 31) and later. Older versions,
 * or [dynamicColor] = false, return the supplied light/dark fallback unchanged.
 * The dynamic scheme is cached across unrelated recompositions and refreshed
 * when the Android context, resource configuration, or dark-mode selection changes.
 */
@Composable
public fun rememberSeaGlassColorScheme(
    dynamicColor: Boolean = true,
    darkTheme: Boolean = isSystemInDarkTheme(),
    lightFallback: ColorScheme = DefaultLightColorScheme,
    darkFallback: ColorScheme = DefaultDarkColorScheme,
): ColorScheme {
    if (!dynamicColor || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return if (darkTheme) darkFallback else lightFallback
    }

    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration, darkTheme) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
}
