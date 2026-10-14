package dev.mttmcknn.seaglass

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Material 3 container/on-container pair used to color a sea-glass surface. */
public enum class SeaGlassColorRole {
    Primary, Secondary, Tertiary;

    internal fun container(colorScheme: ColorScheme): Color = when (this) {
        Primary -> colorScheme.primaryContainer
        Secondary -> colorScheme.secondaryContainer
        Tertiary -> colorScheme.tertiaryContainer
    }

    internal fun onContainer(colorScheme: ColorScheme): Color = when (this) {
        Primary -> colorScheme.onPrimaryContainer
        Secondary -> colorScheme.onSecondaryContainer
        Tertiary -> colorScheme.onTertiaryContainer
    }
}

/**
 * Derive sea-glass pigment and ink from the current Material 3 theme.
 *
 * Omitted recipe and shape values inherit the enclosing [SeaGlassTheme]. Nested
 * calls can choose a different [role], and ordinary [SeaGlassTheme] or component
 * palette overrides still apply. This provider does not change Material colors,
 * typography, shapes, or spacing. Android wallpaper colors must be supplied to
 * MaterialTheme by the application, for example with rememberSeaGlassColorScheme.
 */
@Composable
public fun SeaGlassMaterialTheme(
    role: SeaGlassColorRole = SeaGlassColorRole.Primary,
    style: SeaGlassStyle = SeaGlassTheme.style,
    shape: SeaGlassShape = SeaGlassTheme.shape,
    content: @Composable () -> Unit,
) {
    SeaGlassTheme(style = style, palette = SeaGlassPalette.materialTheme(role), shape = shape, content = content)
}
