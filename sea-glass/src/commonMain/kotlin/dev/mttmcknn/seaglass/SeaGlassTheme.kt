package dev.mttmcknn.seaglass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember

/**
 * A complete, reusable component appearance: material recipe, pigment, and outline.
 *
 * This is a resolved value, not a partial override. Passing it to a component or
 * theme replaces all three material defaults. Use [copy] for a local variation.
 * Layout, typography, interaction behavior, and enabled state remain independent.
 */
@Immutable
public data class SeaGlassComponentStyle(
    val material: SeaGlassStyle = SeaGlassStyle.Reference,
    val palette: SeaGlassPalette = SeaGlassPalette.Lagoon,
    val shape: SeaGlassShape = SeaGlassShape.Rounded,
) {
    /** Give this piece a stable identity while keeping the rest of the style. */
    public fun withSeed(seed: Int): SeaGlassComponentStyle = copy(material = material.copy(seed = seed))
}

private val LocalSeaGlassComponentStyle = compositionLocalOf { SeaGlassComponentStyle() }

/**
 * Material defaults for the current Compose subtree.
 *
 * Works alongside MaterialTheme without changing typography, ordinary component
 * colors, or spacing. Outside a provider, the original Reference/Lagoon/Rounded
 * defaults apply. All accessors update when the nearest provider changes.
 */
public object SeaGlassTheme {
    public val componentStyle: SeaGlassComponentStyle
        @Composable @ReadOnlyComposable get() = LocalSeaGlassComponentStyle.current

    public val style: SeaGlassStyle
        @Composable @ReadOnlyComposable get() = componentStyle.material

    public val palette: SeaGlassPalette
        @Composable @ReadOnlyComposable get() = componentStyle.palette

    public val shape: SeaGlassShape
        @Composable @ReadOnlyComposable get() = componentStyle.shape
}

/**
 * Override selected material defaults. Omitted values inherit the enclosing
 * [SeaGlassTheme], so a nested palette override preserves the parent's recipe
 * and shape. This provider adds no drawing, layout, or foreground color itself.
 */
@Composable
public fun SeaGlassTheme(
    style: SeaGlassStyle = SeaGlassTheme.style,
    palette: SeaGlassPalette = SeaGlassTheme.palette,
    shape: SeaGlassShape = SeaGlassTheme.shape,
    content: @Composable () -> Unit,
) {
    val componentStyle = remember(style, palette, shape) { SeaGlassComponentStyle(style, palette, shape) }
    CompositionLocalProvider(LocalSeaGlassComponentStyle provides componentStyle, content = content)
}

/** Provide a complete reusable appearance. All three defaults come from [style]. */
@Composable
public fun SeaGlassTheme(
    style: SeaGlassComponentStyle,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalSeaGlassComponentStyle provides style, content = content)
}
