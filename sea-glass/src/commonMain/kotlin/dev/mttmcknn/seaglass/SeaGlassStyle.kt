package dev.mttmcknn.seaglass

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mttmcknn.seaglass.internal.RenderPalette
import dev.mttmcknn.seaglass.internal.RenderSettings
import dev.mttmcknn.seaglass.internal.RenderShape

/**
 * The same material recipe used by the web playground. Lengths are dp: one web
 * CSS pixel maps to one dp, so the frost does not grow with display density.
 * Values outside the documented ranges are clamped by the renderer.
 */
@Immutable
public data class SeaGlassStyle(
    /** Cloudiness, 0..1. */
    val frost: Float = 0.55f,
    /** Microscopic abrasion, 0..1. */
    val grain: Float = 0.48f,
    /** Contour variation as a fraction of the shorter side, 0..0.065. */
    val tumble: Float = 0.025f,
    /** Optical thickness, 0..14.dp. */
    val thickness: Dp = 6.dp,
    /** Curvature through the body, 0..1. */
    val bulge: Float = 0.65f,
    /** Height of the fixed downward shadow, 0..18.dp. */
    val elevation: Dp = 8.dp,
    /** Surface reflection azimuth in degrees. Zero is above; 90 is right. */
    val lightDegrees: Float = 315f,
    /** Lower light exit and colored ground pool, 0..1. */
    val transmission: Float = 0.58f,
    /** Stable piece identity. Any Int, including negative seeds, is supported. */
    val seed: Int = 42,
) {
    internal fun renderSettings(): RenderSettings = RenderSettings(
        frost.toDouble(), grain.toDouble(), tumble.toDouble(), thickness.value.toDouble(),
        bulge.toDouble(), elevation.value.toDouble(), lightDegrees.toDouble(),
        transmission.toDouble(), seed,
    ).normalized()

    public companion object {
        public val Reference: SeaGlassStyle = SeaGlassStyle()
        public val Tumbled: SeaGlassStyle = SeaGlassStyle(
            frost = .72f, grain = .68f, tumble = .055f, thickness = 9.dp,
            bulge = .80f, elevation = 7.dp, transmission = .48f,
        )
        public val Quiet: SeaGlassStyle = SeaGlassStyle(
            frost = .38f, grain = .23f, tumble = .012f, thickness = 3.dp,
            bulge = .38f, elevation = 4.dp, transmission = .66f,
        )
    }
}

/** Body pigment, transmitted edge light, and a legible foreground color.
 * Body and edge use opaque sRGB pigment values; their Color alpha is ignored.
 * Transparency is produced by the material model. Ink retains its alpha.
 */
@Immutable
public data class SeaGlassPalette(val body: Color, val edge: Color, val ink: Color) {
    internal fun renderPalette(): RenderPalette = RenderPalette(body.toArgb(), edge.toArgb(), ink.toArgb())

    public companion object {
        public val Lagoon: SeaGlassPalette = SeaGlassPalette(Color(0xFFB5D0EB), Color(0xFFD6E9FF), Color(0xFF2F647C))
        public val Lilac: SeaGlassPalette = SeaGlassPalette(Color(0xFFE1CDE8), Color(0xFFFBE8F7), Color(0xFF71388B))
        public val Rose: SeaGlassPalette = SeaGlassPalette(Color(0xFFEECED6), Color(0xFFFFF0F1), Color(0xFF963F59))
        public val Amber: SeaGlassPalette = SeaGlassPalette(Color(0xFFF0D9BE), Color(0xFFFFFCE0), Color(0xFF865733))
        public val Mint: SeaGlassPalette = SeaGlassPalette(Color(0xFFC0DED0), Color(0xFFE6FFF0), Color(0xFF396C5A))
    }
}

public enum class SeaGlassShape {
    Rounded, Capsule, Disc;

    internal fun renderShape(): RenderShape = RenderShape.valueOf(name)
}
