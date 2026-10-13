package dev.mttmcknn.seaglass.internal

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Small, recipe-independent inputs for the draw-time material shader. */
internal data class LiveMaterialGeometry(
    val segments: List<ContourSegment>,
    val geometryA: NativeFloatTexture,
    val geometryB: NativeFloatTexture,
    val fieldScale: Float,
    val gradientScales: FloatArray,
)

/**
 * Keeps the original tumbled vector silhouette and rounded height construction.
 * The interpolated field stores only smooth shape information: grain is evaluated
 * at its original physical frequency in the shader, independently of field size.
 */
internal fun makeLiveMaterialGeometry(
    widthDp: Double,
    heightDp: Double,
    shape: RenderShape,
    tumble: Double,
    seed: Int,
    rasterizeMask: MaskRasterizer,
    fieldScale: Double = 1.0,
): LiveMaterialGeometry {
    require(widthDp.isFinite() && widthDp >= 1.0 && heightDp.isFinite() && heightDp >= 1.0)
    require(fieldScale.isFinite() && fieldScale > 0.0)
    val scale = min(fieldScale, 1.0)
    val width = max(1, ceil(widthDp * scale).toInt())
    val height = max(1, ceil(heightDp * scale).toInt())
    val random = SeededRandom(seed xor 0x59BF1D7A)
    // Consume exactly the original fine/micro streams without constructing them.
    fun skipGrid(spacing: Double) {
        val columns = ceil((widthDp + 3.0) / spacing).toLong() + 2L
        val rows = ceil((heightDp + 3.0) / spacing).toLong() + 2L
        random.advance(columns * rows)
    }
    skipGrid(0.68)
    skipGrid(1.7)
    val worn = NoiseGrid(widthDp + 3.0, heightDp + 3.0, 7.5, random)
    val cloud = NoiseGrid(widthDp + 3.0, heightDp + 3.0, min(widthDp, heightDp) * 0.44, random)
    val clouds = FloatArray(width * height)
    for (y in 0 until height) {
        val cy = (y + 0.5) / scale
        for (x in 0 until width) {
            val cx = (x + 0.5) / scale
            clouds[y * width + x] = (cloud(cx, cy) * 0.75 + worn(cx, cy) * 0.25).toFloat()
        }
    }
    val unused = FloatArray(0)
    val texture = TextureField(scale, width, height, unused, clouds, unused, unused)
    val field = makeGeometryField(widthDp, heightDp, shape, tumble, seed, texture, rasterizeMask)
    var maxGradientA = 0.005f
    var maxGradientB = 0.005f
    for (i in field.alpha.indices) {
        if ((field.alpha[i].toInt() and 255) == 0) continue
        maxGradientA = max(maxGradientA, max(abs(field.gradX[i]), abs(field.gradY[i])))
        maxGradientB = max(maxGradientB, max(abs(field.roundGradX[i]), abs(field.roundGradY[i])))
    }
    // Encoding into opaque RGB keeps mathematical inputs out of alpha/color
    // conversions, while using the precision available in Android RGBA_F16.
    val gradientScaleA = 0.49f / maxGradientA
    val gradientScaleB = 0.49f / maxGradientB
    val a = FloatArray(width * height * 4)
    val b = FloatArray(width * height * 4)
    for (i in field.alpha.indices) {
        val channel = i * 4
        a[channel] = field.profile[i].coerceIn(0f, 1f)
        a[channel + 1] = (0.5f + field.gradX[i] * gradientScaleA).coerceIn(0f, 1f)
        a[channel + 2] = (0.5f + field.gradY[i] * gradientScaleA).coerceIn(0f, 1f)
        a[channel + 3] = 1f
        b[channel] = field.roundProfile[i].coerceIn(0f, 1f)
        b[channel + 1] = (0.5f + field.roundGradX[i] * gradientScaleB).coerceIn(0f, 1f)
        b[channel + 2] = (0.5f + field.roundGradY[i] * gradientScaleB).coerceIn(0f, 1f)
        b[channel + 3] = 1f
    }
    return LiveMaterialGeometry(
        segments = field.segments,
        geometryA = NativeFloatTexture(width, height, a),
        geometryB = NativeFloatTexture(width, height, b),
        fieldScale = scale.toFloat(),
        gradientScales = floatArrayOf(gradientScaleA, gradientScaleB),
    )
}
