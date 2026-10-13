package dev.mttmcknn.seaglass.internal

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The approved playground's three ground layers, in their original paint order.
 *
 * Cast and contact shadows always fall down; the movable surface light never
 * enters this calculation. The last layer is colored light transmitted through
 * the glass. Its color remains constant while opacity varies over its footprint.
 * That makes blurring the alpha mask identical to blurring premultiplied RGBA.
 *
 * A normalized, separable Gaussian with a three-sigma support approximates the
 * browser's CSS blur. Browser blur implementations can use a different discrete
 * kernel, so subpixel antialiasing and the last few alpha levels may differ.
 * The source redraws the vector contour after each fractional translation. This
 * implementation translates its rasterized mask with bilinear sampling instead;
 * edge coverage can differ slightly before the ground-layer blur is applied.
 */
internal fun renderGroundLayers(
    widthDp: Double,
    heightDp: Double,
    scale: Double,
    settings: RenderSettings,
    palette: RenderPalette,
    bodyAlpha: ByteArray,
    bodyWidth: Int,
    bodyHeight: Int,
    cancellationProbe: () -> Unit = {},
    approximateBlur: Boolean = false,
): PixelBuffer {
    require(widthDp > 0.0 && heightDp > 0.0 && scale > 0.0)
    require(bodyWidth > 0 && bodyHeight > 0 && bodyAlpha.size == bodyWidth * bodyHeight)
    val width = ceil((widthDp + MATERIAL_PADDING_DP * 2.0) * scale).toInt()
    val height = ceil((heightDp + MATERIAL_PADDING_DP * 2.0) * scale).toInt()
    val size = width * height
    val alpha = DoubleArray(size)
    val red = DoubleArray(size)
    val green = DoubleArray(size)
    val blue = DoubleArray(size)
    // These scratch buffers serve all three layers. Once the horizontal pass
    // finishes, its source mask is no longer needed and becomes the output.
    val mask = DoubleArray(size)
    val horizontal = DoubleArray(size)
    val short = min(widthDp, heightDp)
    val transmission = settings.transmission
    val pad = MATERIAL_PADDING_DP * scale

    fun layer(offsetDp: Double, blurDp: Double, color: Int, opacity: (Double) -> Double) {
        mask.fill(0.0)
        var maskLeft = width
        var maskTop = height
        var maskRight = 0
        var maskBottom = 0
        val firstX = max(0, floor(pad).toInt())
        val lastX = min(width, ceil(pad + bodyWidth).toInt())
        // The mask already uses this physical scale. Unlike drawImage for the
        // body, the reference's ground path is not shrunk from a ceil-sized
        // texture to layout bounds. Retain the original scale when translating.
        for (y in 0 until height) {
            if ((y and 31) == 0) cancellationProbe()
            val relativeY = (y + 0.5 - pad - offsetDp * scale) / (heightDp * scale)
            val sourceY = y - pad - offsetDp * scale
            if (sourceY <= -1.0 || sourceY >= bodyHeight) continue
            val opacityAtY = opacity(relativeY.coerceIn(0.0, 1.0))
            if (opacityAtY <= 0.0) continue
            for (x in firstX until lastX) {
                val sourceX = x - pad
                val coverage = sampleAlpha(bodyAlpha, bodyWidth, bodyHeight, sourceX, sourceY) * opacityAtY
                if (coverage <= 0.0) continue
                mask[y * width + x] = coverage
                maskLeft = min(maskLeft, x)
                maskTop = min(maskTop, y)
                maskRight = max(maskRight, x + 1)
                maskBottom = max(maskBottom, y + 1)
            }
        }
        if (maskLeft >= maskRight) return
        val maskBounds = PixelBounds(maskLeft, maskTop, maskRight, maskBottom)
        val bounds = if (approximateBlur) {
            fastGaussianBlur(mask, horizontal, width, height, blurDp * scale, maskBounds, cancellationProbe)
        } else {
            gaussianBlur(mask, horizontal, width, height, blurDp * scale, maskBounds, cancellationProbe)
        }
        val r = ((color ushr 16) and 255).toDouble()
        val g = ((color ushr 8) and 255).toDouble()
        val b = (color and 255).toDouble()
        for (y in bounds.top until bounds.bottom) {
            if (((y - bounds.top) and 31) == 0) cancellationProbe()
            val row = y * width
            for (x in bounds.left until bounds.right) {
                val index = row + x
                val sourceAlpha = mask[index].coerceIn(0.0, 1.0)
                val destinationWeight = 1.0 - sourceAlpha
                red[index] = r * sourceAlpha + red[index] * destinationWeight
                green[index] = g * sourceAlpha + green[index] * destinationWeight
                blue[index] = b * sourceAlpha + blue[index] * destinationWeight
                alpha[index] = sourceAlpha + alpha[index] * destinationWeight
            }
        }
    }

    layer(
        offsetDp = min(16.0, settings.thickness * 0.40 + settings.elevation * 0.72 + settings.bulge * short * 0.03),
        blurDp = min(6.0, 2.5 + settings.elevation * 0.18 + settings.frost * 0.7 + settings.bulge * short * 0.018),
        color = 0x3F4342,
    ) { (0.18 + settings.elevation * 0.001) * (1.0 - transmission * 0.82) }
    layer(
        offsetDp = min(8.0, settings.thickness * 0.32 + 0.8),
        blurDp = 1.4 + settings.elevation * 0.065,
        color = 0x3D413F,
    ) { 0.10 * (1.0 - transmission * 0.70) }

    if (transmission > 0.001) {
        fun tint(channel: Int): Int = (255.0 - (255 - channel) * 1.35).roundToInt().coerceIn(0, 255)
        val tintColor = (tint((palette.body ushr 16) and 255) shl 16) or
            (tint((palette.body ushr 8) and 255) shl 8) or tint(palette.body and 255)
        val travel = 4.0 + settings.elevation * 0.56 + short * 0.055
        layer(
            offsetDp = min(22.0, settings.thickness * 0.42 + travel),
            blurDp = min(8.0, 2.8 + short * 0.033 + settings.frost * 1.6),
            color = tintColor,
        ) { y ->
            transmission * when {
                y < 0.45 -> 0.06 * y / 0.45
                y < 0.75 -> 0.06 + (0.48 - 0.06) * (y - 0.45) / 0.30
                else -> 0.48 + (0.94 - 0.48) * (y - 0.75) / 0.25
            }
        }
    }

    val cancellationStride = width * 32
    val pixels = IntArray(size) { i ->
        if (i % cancellationStride == 0) cancellationProbe()
        straightArgb(alpha[i], red[i], green[i], blue[i])
    }
    return PixelBuffer(width, height, pixels)
}

/**
 * Places a straight-ARGB body over its ground, with premultiplied interpolation.
 * Logical dimensions reproduce drawImage's shrink from ceil-sized raster bounds
 * to exact layout bounds when density or dimensions are fractional.
 */
internal fun compositeBody(
    ground: PixelBuffer,
    body: PixelBuffer,
    scale: Double,
    widthDp: Double = body.width / scale,
    heightDp: Double = body.height / scale,
    cancellationProbe: () -> Unit = {},
): PixelBuffer {
    require(scale.isFinite() && scale > 0.0)
    require(widthDp.isFinite() && widthDp > 0.0 && heightDp.isFinite() && heightDp > 0.0)
    val pixels = ground.pixels.copyOf()
    val pad = MATERIAL_PADDING_DP * scale
    val destinationWidth = widthDp * scale
    val destinationHeight = heightDp * scale
    val left = max(0, floor(pad - 1.0).toInt())
    val top = max(0, floor(pad - 1.0).toInt())
    val right = min(ground.width, ceil(pad + destinationWidth + 1.0).toInt())
    val bottom = min(ground.height, ceil(pad + destinationHeight + 1.0).toInt())
    if (left >= right || top >= bottom) return PixelBuffer(ground.width, ground.height, pixels)
    // X sampling coordinates are identical on every row. Keep the original
    // expression (including fractional-size shrink) and calculate it once.
    val columns = IntArray(right - left)
    val fractions = DoubleArray(right - left)
    for (x in left until right) {
        val sourceX = (x + 0.5 - pad) / destinationWidth * body.width - 0.5
        val x0 = floor(sourceX).toInt()
        columns[x - left] = x0
        fractions[x - left] = sourceX - x0
    }
    for (y in top until bottom) {
        if (((y - top) and 31) == 0) cancellationProbe()
        val sourceY = (y + 0.5 - pad) / destinationHeight * body.height - 0.5
        val y0 = floor(sourceY).toInt()
        val fy = sourceY - y0
        for (x in left until right) {
            val x0 = columns[x - left]
            val fx = fractions[x - left]
            var alpha = 0.0
            var red = 0.0
            var green = 0.0
            var blue = 0.0
            if (fx == 0.0 && fy == 0.0 && x0 in 0 until body.width && y0 in 0 until body.height) {
                // Do not infer alignment from integer dimensions: the computed
                // coordinates can still have a floating-point fractional part.
                val color = body.pixels[y0 * body.width + x0]
                alpha = ((color ushr 24) and 255) / 255.0
                red = ((color ushr 16) and 255) * alpha
                green = ((color ushr 8) and 255) * alpha
                blue = (color and 255) * alpha
            } else {
                for (dy in 0..1) {
                    val sy = y0 + dy
                    if (sy !in 0 until body.height) continue
                    val wy = if (dy == 0) 1.0 - fy else fy
                    for (dx in 0..1) {
                        val sx = x0 + dx
                        if (sx !in 0 until body.width) continue
                        val wx = if (dx == 0) 1.0 - fx else fx
                        val color = body.pixels[sy * body.width + sx]
                        val weightedAlpha = ((color ushr 24) and 255) / 255.0 * wx * wy
                        alpha += weightedAlpha
                        red += ((color ushr 16) and 255) * weightedAlpha
                        green += ((color ushr 8) and 255) * weightedAlpha
                        blue += (color and 255) * weightedAlpha
                    }
                }
            }
            if (alpha <= 0.0) continue
            val index = y * ground.width + x
            val destination = pixels[index]
            val destinationAlpha = ((destination ushr 24) and 255) / 255.0
            val destinationWeight = destinationAlpha * (1.0 - alpha)
            pixels[index] = straightArgb(
                alpha + destinationWeight,
                red + ((destination ushr 16) and 255) * destinationWeight,
                green + ((destination ushr 8) and 255) * destinationWeight,
                blue + (destination and 255) * destinationWeight,
            )
        }
    }
    return PixelBuffer(ground.width, ground.height, pixels)
}

private fun straightArgb(alpha: Double, red: Double, green: Double, blue: Double): Int {
    val a = (alpha * 255.0).roundToInt().coerceIn(0, 255)
    if (a == 0 || alpha <= 0.0) return 0
    val r = (red / alpha).roundToInt().coerceIn(0, 255)
    val g = (green / alpha).roundToInt().coerceIn(0, 255)
    val b = (blue / alpha).roundToInt().coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

private fun sampleAlpha(alpha: ByteArray, width: Int, height: Int, x: Double, y: Double): Double {
    if (x <= -1.0 || y <= -1.0 || x >= width || y >= height) return 0.0
    val x0 = floor(x).toInt()
    val y0 = floor(y).toInt()
    val fx = x - x0
    val fy = y - y0
    var result = 0.0
    for (dy in 0..1) {
        val sy = y0 + dy
        if (sy !in 0 until height) continue
        val wy = if (dy == 0) 1.0 - fy else fy
        for (dx in 0..1) {
            val sx = x0 + dx
            if (sx !in 0 until width) continue
            val wx = if (dx == 0) 1.0 - fx else fx
            result += (alpha[sy * width + sx].toInt() and 255) / 255.0 * wx * wy
        }
    }
    return result
}

private data class PixelBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * Exact separable convolution restricted to nonzero support. Skipping zero
 * padding retains the original per-pixel tap order and Double precision.
 */
private fun gaussianBlur(
    source: DoubleArray,
    horizontal: DoubleArray,
    width: Int,
    height: Int,
    sigma: Double,
    bounds: PixelBounds,
    cancellationProbe: () -> Unit,
): PixelBounds {
    if (sigma <= 0.01) return bounds
    val radius = ceil(sigma * 3.0).toInt()
    val kernel = DoubleArray(radius * 2 + 1)
    var sum = 0.0
    for (i in kernel.indices) {
        val distance = (i - radius).toDouble()
        kernel[i] = exp(-distance * distance / (2.0 * sigma * sigma))
        sum += kernel[i]
    }
    for (i in kernel.indices) kernel[i] /= sum
    val blurred = PixelBounds(
        max(0, bounds.left - radius), max(0, bounds.top - radius),
        min(width, bounds.right + radius), min(height, bounds.bottom + radius),
    )
    // Transparent outside the image; do not renormalize the kernel at bounds.
    for (y in bounds.top until bounds.bottom) {
        if (((y - bounds.top) and 31) == 0) cancellationProbe()
        val row = y * width
        horizontal.fill(0.0, row + blurred.left, row + blurred.right)
        for (dx in -radius..radius) {
            val firstX = max(blurred.left, bounds.left - dx)
            val lastX = min(blurred.right, bounds.right - dx)
            val sampleRow = row + dx
            val weight = kernel[dx + radius]
            for (x in firstX until lastX) {
                horizontal[row + x] += source[sampleRow + x] * weight
            }
        }
    }
    for (y in blurred.top until blurred.bottom) {
        if (((y - blurred.top) and 31) == 0) cancellationProbe()
        val row = y * width
        source.fill(0.0, row + blurred.left, row + blurred.right)
        // Contiguous inner loops avoid strided reads, and allow vectorization.
        // Every output pixel still adds its vertical taps in ascending order.
        for (dy in max(-radius, bounds.top - y)..min(radius, bounds.bottom - y - 1)) {
            val sampleRow = (y + dy) * width
            val weight = kernel[dy + radius]
            for (x in blurred.left until blurred.right) {
                source[row + x] += horizontal[sampleRow + x] * weight
            }
        }
    }
    return blurred
}

/** Four running-sum box filters approximate a Gaussian at constant work per pixel. */
private fun fastGaussianBlur(
    source: DoubleArray,
    horizontal: DoubleArray,
    width: Int,
    height: Int,
    sigma: Double,
    bounds: PixelBounds,
    cancellationProbe: () -> Unit,
): PixelBounds {
    if (sigma <= 0.01) return bounds
    val passes = 4
    val idealWidth = kotlin.math.sqrt(12.0 * sigma * sigma / passes + 1.0)
    var lowerWidth = floor(idealWidth).toInt()
    if (lowerWidth % 2 == 0) lowerWidth--
    lowerWidth = max(1, lowerWidth)
    val upperWidth = lowerWidth + 2
    val lowerCount = ((12.0 * sigma * sigma - passes * lowerWidth * lowerWidth -
        4.0 * passes * lowerWidth - 3.0 * passes) / (-4.0 * lowerWidth - 4.0))
        .roundToInt().coerceIn(0, passes)
    var resultBounds = bounds
    val columnSums = DoubleArray(width)
    for (pass in 0 until passes) {
        val radius = ((if (pass < lowerCount) lowerWidth else upperWidth) - 1) / 2
        if (radius == 0) continue
        resultBounds = boxBlur(source, horizontal, columnSums, width, height, radius, resultBounds, cancellationProbe)
    }
    return resultBounds
}

private fun boxBlur(
    source: DoubleArray,
    horizontal: DoubleArray,
    columnSums: DoubleArray,
    width: Int,
    height: Int,
    radius: Int,
    bounds: PixelBounds,
    cancellationProbe: () -> Unit,
): PixelBounds {
    val outputBounds = PixelBounds(
        max(0, bounds.left - radius), max(0, bounds.top - radius),
        min(width, bounds.right + radius), min(height, bounds.bottom + radius),
    )
    val inverseWidth = 1.0 / (radius * 2 + 1)
    for (y in bounds.top until bounds.bottom) {
        if (((y - bounds.top) and 31) == 0) cancellationProbe()
        val row = y * width
        var sum = 0.0
        for (x in max(bounds.left, outputBounds.left - radius)..min(bounds.right - 1, outputBounds.left + radius)) {
            sum += source[row + x]
        }
        for (x in outputBounds.left until outputBounds.right) {
            horizontal[row + x] = sum * inverseWidth
            val outgoing = x - radius
            val incoming = x + radius + 1
            if (outgoing >= bounds.left && outgoing < bounds.right) sum -= source[row + outgoing]
            if (incoming >= bounds.left && incoming < bounds.right) sum += source[row + incoming]
        }
    }
    columnSums.fill(0.0, outputBounds.left, outputBounds.right)
    for (y in max(bounds.top, outputBounds.top - radius)..min(bounds.bottom - 1, outputBounds.top + radius)) {
        val row = y * width
        for (x in outputBounds.left until outputBounds.right) columnSums[x] += horizontal[row + x]
    }
    for (y in outputBounds.top until outputBounds.bottom) {
        if (((y - outputBounds.top) and 31) == 0) cancellationProbe()
        val row = y * width
        val outgoing = y - radius
        val incoming = y + radius + 1
        val outgoingRow = if (outgoing >= bounds.top && outgoing < bounds.bottom) outgoing * width else -1
        val incomingRow = if (incoming >= bounds.top && incoming < bounds.bottom) incoming * width else -1
        for (x in outputBounds.left until outputBounds.right) {
            source[row + x] = columnSums[x] * inverseWidth
            if (outgoingRow >= 0) columnSums[x] -= horizontal[outgoingRow + x]
            if (incomingRow >= 0) columnSums[x] += horizontal[incomingRow + x]
        }
    }
    return outputBounds
}
