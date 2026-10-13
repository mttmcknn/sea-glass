package dev.mttmcknn.seaglass.internal

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** Float arrays intentionally retain the web renderer's Float32 quantization. */
internal data class TextureField(
    val scale: Double,
    val width: Int,
    val height: Int,
    val noise: FloatArray,
    val clouds: FloatArray,
    val roughX: FloatArray,
    val roughY: FloatArray,
)

internal data class GeometryField(
    val segments: List<ContourSegment>,
    val profile: FloatArray,
    val gradX: FloatArray,
    val gradY: FloatArray,
    val roundProfile: FloatArray,
    val roundGradX: FloatArray,
    val roundGradY: FloatArray,
    val alpha: ByteArray,
    val texture: TextureField,
)

/** Large downsampled surfaces sample the same seeded grid without storing it. */
internal class NoiseGrid(
    width: Double,
    height: Double,
    private val spacing: Double,
    random: SeededRandom,
    maxCachedCells: Int = 262_144,
) {
    private val columns = ceil(width / spacing).toInt() + 2
    private val rows = ceil(height / spacing).toInt() + 2
    private val count = columns.toLong() * rows
    private val sampleRandom = random.copy()
    private val values = if (count <= maxCachedCells.toLong()) {
        FloatArray(count.toInt()) { (random.nextDouble() * 2.0 - 1.0).toFloat() }
    } else null

    init {
        if (values == null) random.advance(count)
    }

    private fun value(x: Int, y: Int): Double {
        val index = y.toLong() * columns + x
        return (values?.get(index.toInt()) ?: (sampleRandom.sample(index) * 2.0 - 1.0).toFloat()).toDouble()
    }

    operator fun invoke(x: Double, y: Double): Double {
        // When a very low output scale rounds up to one pixel, its center can
        // lie beyond the logical size. Extend the final grid value safely.
        val gx = clamp(x / spacing, 0.0, (columns - 1).toDouble())
        val gy = clamp(y / spacing, 0.0, (rows - 1).toDouble())
        val ix = min(floor(gx).toInt(), columns - 2)
        val iy = min(floor(gy).toInt(), rows - 2)
        val tx = gx - ix
        val ty = gy - iy
        val sx = tx * tx * (3.0 - 2.0 * tx)
        val sy = ty * ty * (3.0 - 2.0 * ty)
        val a = value(ix, iy)
        val b = value(ix + 1, iy)
        val c = value(ix, iy + 1)
        val d = value(ix + 1, iy + 1)
        return (a + (b - a) * sx) * (1.0 - sy) + (c + (d - c) * sx) * sy
    }
}

internal fun makeTexture(w: Double, h: Double, ratio: Double, seed: Int, cancellationProbe: () -> Unit = {}): TextureField {
    val scale = min(ratio, 2.0)
    val width = max(1, ceil(w * scale).toInt())
    val height = max(1, ceil(h * scale).toInt())
    val random = SeededRandom(seed xor 0x59BF1D7A)
    val fine = NoiseGrid(w + 3.0, h + 3.0, 0.68, random)
    val micro = NoiseGrid(w + 3.0, h + 3.0, 1.7, random)
    val worn = NoiseGrid(w + 3.0, h + 3.0, 7.5, random)
    val cloud = NoiseGrid(w + 3.0, h + 3.0, min(w, h) * 0.44, random)
    val count = width * height
    val noise = FloatArray(count)
    val clouds = FloatArray(count)
    val roughX = FloatArray(count)
    val roughY = FloatArray(count)
    // Axis coordinates stay Double, matching the original sampling arithmetic.
    // One column-sized array avoids repeating division without an area-sized cache.
    val sampleX = DoubleArray(width) { x -> (x + 0.5) / scale }
    for (y in 0 until height) {
        if (y % 32 == 0) cancellationProbe()
        val row = y * width
        val cy = (y + 0.5) / scale
        val cyPlus = cy + 0.32
        val cyMinus = max(0.0, cy - 0.32)
        for (x in 0 until width) {
            val i = row + x
            val cx = sampleX[x]
            val wornSample = worn(cx, cy)
            noise[i] = (fine(cx, cy) * 0.54 + micro(cx, cy) * 0.32 + wornSample * 0.14).toFloat()
            clouds[i] = (cloud(cx, cy) * 0.75 + wornSample * 0.25).toFloat()
            roughX[i] = (micro(cx + 0.32, cy) - micro(max(0.0, cx - 0.32), cy)).toFloat()
            roughY[i] = (micro(cx, cyPlus) - micro(cx, cyMinus)).toFloat()
        }
    }
    return TextureField(scale, width, height, noise, clouds, roughX, roughY)
}

/** Exact squared Euclidean distance transform, linear in image area. */
private fun distanceLine(input: DoubleArray, length: Int, output: DoubleArray, sites: IntArray, separators: DoubleArray) {
    var k = 0
    sites[0] = 0
    separators[0] = Double.NEGATIVE_INFINITY
    separators[1] = Double.POSITIVE_INFINITY
    for (q in 1 until length) {
        var cut: Double
        do {
            val v = sites[k]
            cut = ((input[q] + q.toDouble() * q) - (input[v] + v.toDouble() * v)) / (2.0 * q - 2.0 * v)
            if (cut <= separators[k]) k-- else break
        } while (k >= 0)
        k++
        sites[k] = q
        separators[k] = cut
        separators[k + 1] = Double.POSITIVE_INFINITY
    }
    k = 0
    for (q in 0 until length) {
        while (separators[k + 1] < q) k++
        val delta = q - sites[k]
        output[q] = delta.toDouble() * delta + input[sites[k]]
    }
}

private fun smoothHeight(values: FloatArray, width: Int, height: Int, scale: Double, cancellationProbe: () -> Unit): FloatArray {
    val sigma = 0.85 * scale
    val reach = ceil(sigma * 3.0).toInt()
    val weights = DoubleArray(reach * 2 + 1) { i ->
        val q = (i - reach) / sigma
        exp(-(q * q) / 2.0)
    }
    val sum = weights.sum()
    val horizontal = FloatArray(values.size)
    val result = FloatArray(values.size)
    for (y in 0 until height) for (x in 0 until width) {
        if (x == 0 && y % 32 == 0) cancellationProbe()
        var value = 0.0
        for (k in -reach..reach) value += values[y * width + (x + k).coerceIn(0, width - 1)] * weights[k + reach]
        horizontal[y * width + x] = (value / sum).toFloat()
    }
    for (y in 0 until height) for (x in 0 until width) {
        if (x == 0 && y % 32 == 0) cancellationProbe()
        var value = 0.0
        for (k in -reach..reach) value += horizontal[(y + k).coerceIn(0, height - 1) * width + x] * weights[k + reach]
        result[y * width + x] = (value / sum).toFloat()
    }
    return result
}

internal fun makeGeometryField(
    w: Double,
    h: Double,
    shape: RenderShape,
    tumble: Double,
    seed: Int,
    texture: TextureField,
    rasterizeMask: MaskRasterizer,
    cancellationProbe: () -> Unit = {},
): GeometryField {
    val segments = contourSegments(buildContour(w, h, shape, tumble, seed))
    val width = texture.width
    val height = texture.height
    val scale = texture.scale
    val columns = width + 2
    val rows = height + 2
    val coverage = rasterizeMask(segments, columns, rows, scale, 1.0, 1.0)
    require(coverage.size == columns * rows) { "Mask rasterizer returned an incorrect pixel count" }
    val distances = FloatArray(columns * rows) { if ((coverage[it].toInt() and 255) > 127) 1e12f else 0f }
    val longest = max(columns, rows)
    val input = DoubleArray(longest)
    val output = DoubleArray(longest)
    val sites = IntArray(longest)
    val separators = DoubleArray(longest + 1)
    for (y in 0 until rows) {
        if (y % 32 == 0) cancellationProbe()
        for (x in 0 until columns) input[x] = distances[y * columns + x].toDouble()
        distanceLine(input, columns, output, sites, separators)
        for (x in 0 until columns) distances[y * columns + x] = output[x].toFloat()
    }
    for (x in 0 until columns) {
        if (x % 32 == 0) cancellationProbe()
        for (y in 0 until rows) input[y] = distances[y * columns + x].toDouble()
        distanceLine(input, rows, output, sites, separators)
        for (y in 0 until rows) distances[y * columns + x] = (max(0.0, sqrt(output[y]) - 0.5) / scale).toFloat()
    }
    val count = width * height
    val profile = FloatArray(count)
    val gradX = FloatArray(count)
    val gradY = FloatArray(count)
    val roundProfile = FloatArray(count)
    val roundGradX = FloatArray(count)
    val roundGradY = FloatArray(count)
    val alpha = ByteArray(count)
    val short = min(w, h)
    val maxDistance = max(1.0, distances.maxOrNull()?.toDouble() ?: 0.0)
    fun at(x: Int, y: Int) = distances[y.coerceIn(0, rows - 1) * columns + x.coerceIn(0, columns - 1)].toDouble()
    // These longitudinal terms depend only on the column. Preserve Double
    // intermediates and operation order; quantize only at the existing stores.
    val columnLengthArch = DoubleArray(width)
    val columnLengthSlope = DoubleArray(width)
    for (x in 0 until width) {
        val u = (x + 0.5) / width
        val sine = sin(u * PI)
        columnLengthArch[x] = 0.86 + 0.14 * sine * sine
        columnLengthSlope[x] = 0.14 * PI / w * sin(u * PI * 2.0)
    }
    for (y in 0 until height) for (x in 0 until width) {
        if (x == 0 && y % 32 == 0) cancellationProbe()
        val i = y * width + x
        val mx = x + 1
        val my = y + 1
        alpha[i] = coverage[my * columns + mx]
        val roll = short * (0.205 + texture.clouds[i].toDouble() * 0.023)
        val dome = tanh(at(mx, my) / roll)
        val slope = (1.0 - dome * dome) / roll
        profile[i] = dome.toFloat()
        val dx = at(mx + 2, my) - at(mx - 2, my)
        val dy = at(mx, my + 2) - at(mx, my - 2)
        val direction = hypot(dx, dy)
        gradX[i] = (if (direction > 0.001) dx / direction * slope else 0.0).toFloat()
        gradY[i] = (if (direction > 0.001) dy / direction * slope else 0.0).toFloat()
        val q = clamp(at(mx, my) / maxDistance, 0.0, 1.0)
        val arch = sqrt(q * (2.0 - q))
        val roundSlope = (1.0 - q) / (maxDistance * max(arch, 0.035))
        val lengthArch = columnLengthArch[x]
        val lengthSlope = columnLengthSlope[x]
        roundProfile[i] = (arch * lengthArch).toFloat()
        roundGradX[i] = ((if (direction > 0.001) dx / direction * roundSlope * lengthArch else 0.0) + arch * lengthSlope).toFloat()
        roundGradY[i] = (if (direction > 0.001) dy / direction * roundSlope * lengthArch else 0.0).toFloat()
    }
    val softened = smoothHeight(roundProfile, width, height, scale, cancellationProbe)
    fun heightAt(x: Int, y: Int) = softened[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)].toDouble()
    for (y in 0 until height) for (x in 0 until width) {
        if (x == 0 && y % 32 == 0) cancellationProbe()
        val i = y * width + x
        val blend = clamp((at(x + 1, y + 1) - 2.0) / 3.0, 0.0, 1.0)
        val weight = blend * blend * (3.0 - 2.0 * blend)
        val rp = roundProfile[i].toDouble()
        val gx = roundGradX[i].toDouble()
        val gy = roundGradY[i].toDouble()
        roundProfile[i] = (rp + (softened[i].toDouble() - rp) * weight).toFloat()
        roundGradX[i] = (gx + ((heightAt(x + 2, y) - heightAt(x - 2, y)) * scale / 4.0 - gx) * weight).toFloat()
        roundGradY[i] = (gy + ((heightAt(x, y + 2) - heightAt(x, y - 2)) * scale / 4.0 - gy) * weight).toFloat()
    }
    return GeometryField(segments, profile, gradX, gradY, roundProfile, roundGradX, roundGradY, alpha, texture)
}
