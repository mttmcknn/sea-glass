package dev.mttmcknn.seaglass.internal

import kotlin.math.max
import kotlin.math.min

/** Internal renderer contracts deliberately do not depend on Compose or a platform. */
internal data class RenderSettings(
    val frost: Double = 0.55,
    val grain: Double = 0.48,
    val tumble: Double = 0.025,
    val thickness: Double = 6.0,
    val bulge: Double = 0.65,
    val elevation: Double = 8.0,
    val light: Double = 315.0,
    val transmission: Double = 0.58,
    val seed: Int = 42,
) {
    fun normalized() = copy(
        frost = frost.finiteOr(0.55).coerceIn(0.0, 1.0),
        grain = grain.finiteOr(0.48).coerceIn(0.0, 1.0),
        tumble = tumble.finiteOr(0.025).coerceIn(0.0, 0.065),
        thickness = thickness.finiteOr(6.0).coerceIn(0.0, 14.0),
        bulge = bulge.finiteOr(0.65).coerceIn(0.0, 1.0),
        elevation = elevation.finiteOr(8.0).coerceIn(0.0, 18.0),
        light = light.finiteOr(315.0),
        transmission = transmission.finiteOr(0.58).coerceIn(0.0, 1.0),
    )
}

internal data class RenderPalette(val body: Int, val edge: Int, val ink: Int)

internal enum class RenderShape { Rounded, Capsule, Disc }

/** Straight-alpha ARGB pixels, suitable for conversion by each platform adapter. */
internal data class PixelBuffer(val width: Int, val height: Int, val pixels: IntArray) {
    init { require(width > 0 && height > 0 && pixels.size == width * height) }
}

internal data class ContourPoint(val x: Double, val y: Double)

internal data class ContourSegment(
    val start: ContourPoint,
    val control1: ContourPoint,
    val control2: ContourPoint,
    val end: ContourPoint,
)

/**
 * Rasterize the closed cubic path with source-over white on transparent pixels.
 * Scale is applied to logical path coordinates before the pixel-space offset.
 * Return one unsigned alpha byte per pixel in row-major order.
 */
internal typealias MaskRasterizer = (
    segments: List<ContourSegment>,
    widthPx: Int,
    heightPx: Int,
    scale: Double,
    offsetX: Double,
    offsetY: Double,
) -> ByteArray

internal const val MATERIAL_PADDING_DP = 48.0
internal fun clamp(value: Double, low: Double, high: Double) = max(low, min(high, value))
internal fun Double.finiteOr(fallback: Double) = if (isFinite()) this else fallback
internal fun rgbComponents(rgb: Int) = intArrayOf(rgb ushr 16 and 255, rgb ushr 8 and 255, rgb and 255)

/** Mulberry32 matches JavaScript's imul/unsigned shifts, including seed wraparound. */
internal class SeededRandom(seed: Int) {
    private var value = seed
    fun nextDouble(): Double {
        value += 0x6D2B79F5
        return mix(value)
    }

    /** Read an offset in this stream without consuming the intervening values. */
    fun sample(index: Long): Double {
        require(index >= 0)
        return mix(value + (index + 1).toInt() * 0x6D2B79F5)
    }

    fun advance(count: Long) {
        require(count >= 0)
        value += count.toInt() * 0x6D2B79F5
    }

    fun copy(): SeededRandom = SeededRandom(value)

    private fun mix(state: Int): Double {
        var n = state
        n = (n xor (n ushr 15)) * (n or 1)
        n = n xor (n + (n xor (n ushr 7)) * (n or 61))
        return (n xor (n ushr 14)).toUInt().toDouble() / 4294967296.0
    }
}
