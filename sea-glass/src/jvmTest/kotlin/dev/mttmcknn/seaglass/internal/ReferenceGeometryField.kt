package dev.mttmcknn.seaglass.internal

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** Frozen scalar reference for detecting rounding changes when optimizing field kernels. */
/** Exact squared Euclidean distance transform, linear in image area. */
private fun referenceDistanceLine(input: DoubleArray, length: Int, output: DoubleArray, sites: IntArray, separators: DoubleArray) {
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

private fun referenceSmoothHeight(values: FloatArray, width: Int, height: Int, scale: Double, cancellationProbe: () -> Unit): FloatArray {
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

internal fun referenceGeometryField(
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
        referenceDistanceLine(input, columns, output, sites, separators)
        for (x in 0 until columns) distances[y * columns + x] = output[x].toFloat()
    }
    for (x in 0 until columns) {
        if (x % 32 == 0) cancellationProbe()
        for (y in 0 until rows) input[y] = distances[y * columns + x].toDouble()
        referenceDistanceLine(input, rows, output, sites, separators)
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
    val softened = referenceSmoothHeight(roundProfile, width, height, scale, cancellationProbe)
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
