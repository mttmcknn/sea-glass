package dev.mttmcknn.seaglass.internal

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** Exact regression oracle for the performance changes; no image-error tolerance. */
class RendererOptimizationParityTest {
    private val palette = RenderPalette(0xB5D0EB, 0xD6E9FF, 0x2F647C)
    private val extremes = listOf(
        RenderSettings(),
        RenderSettings(frost = 0.0, grain = 0.0, tumble = 0.0, thickness = 0.0, bulge = 0.0, elevation = 0.0, transmission = 0.0),
        RenderSettings(frost = 1.0, grain = 1.0, tumble = 0.065, thickness = 14.0, bulge = 1.0, elevation = 18.0, transmission = 1.0),
    )

    @Test
    fun groundMatchesFrozenAlgorithmForFractionalContoursAndAlphaExtremes() {
        val dimensions = listOf(
            Dimensions(23.25, 17.75, 1.25),
            Dimensions(19.1, 37.4, 1.75),
            Dimensions(1.0, 1.0, 0.625),
            Dimensions(120.0, 36.0, 1.5),
        )
        for (dimensionsCase in dimensions) for (shape in RenderShape.entries) {
            val mask = dimensionsCase.contourMask(shape)
            for (pattern in 0..3) for (settings in extremes) {
                assertGround(dimensionsCase, patternMask(mask, pattern), settings, "$dimensionsCase/$shape/pattern=$pattern/$settings")
            }
        }
        // Exercise the cropped blur on a wide material as well as compact shapes.
        val toolbar = Dimensions(500.25, 66.125, 2.0)
        val mask = toolbar.contourMask(RenderShape.Capsule)
        for (settings in extremes) assertGround(toolbar, mask, settings, "large toolbar/$settings")
    }

    @Test
    fun compositeMatchesFrozenAlgorithmWithFractionalMappingAndEveryAlphaPattern() {
        val dimensions = listOf(
            Dimensions(24.0, 13.0, 2.0), // Exact integral lookup fast path.
            Dimensions(23.25, 17.75, 1.25),
            Dimensions(19.1, 37.4, 1.75),
            Dimensions(1.0, 1.0, 0.625),
            Dimensions(120.0, 36.0, 1.5),
        )
        for (dimensionsCase in dimensions) for (pattern in 0..3) {
            val body = patternedPixels(dimensionsCase.bodyWidth, dimensionsCase.bodyHeight, pattern)
            val groundWidth = ceil((dimensionsCase.width + MATERIAL_PADDING_DP * 2) * dimensionsCase.scale).toInt()
            val groundHeight = ceil((dimensionsCase.height + MATERIAL_PADDING_DP * 2) * dimensionsCase.scale).toInt()
            val ground = patternedPixels(groundWidth, groundHeight, (pattern + 1) % 4)
            val originalGround = ground.pixels.copyOf()
            val originalBody = body.pixels.copyOf()
            val expected = FrozenGroundReference.compositeBody(ground, body, dimensionsCase.scale, dimensionsCase.width, dimensionsCase.height)
            val actual = compositeBody(ground, body, dimensionsCase.scale, dimensionsCase.width, dimensionsCase.height)
            assertPixels(expected, actual, "$dimensionsCase/pattern=$pattern")
            assertContentEquals(originalGround, ground.pixels, "Ground input must remain immutable")
            assertContentEquals(originalBody, body.pixels, "Body input must remain immutable")
        }
    }

    @Test
    fun warmCacheTransitionsExactlyMatchFreshRenderers() {
        val base = Case()
        val elevated = base.copy(settings = base.settings.copy(elevation = 18.0))
        val lit = elevated.copy(settings = elevated.settings.copy(light = 90.0))
        val optical = lit.copy(settings = lit.settings.copy(frost = 1.0, grain = 0.0, thickness = 14.0, bulge = 0.0, transmission = 0.0))
        val tinted = optical.copy(palette = RenderPalette(0xF1D4BB, 0xFFEFCC, 0x795334))
        val tumbled = tinted.copy(settings = tinted.settings.copy(tumble = 0.065))
        val reseeded = tumbled.copy(settings = tumbled.settings.copy(seed = 73))
        val resized = reseeded.copy(width = 71.3, height = 25.1, scale = 1.5, shape = RenderShape.Capsule)
        val renderer = SeaGlassRenderer(composeMaskRasterizer)
        for ((index, case) in listOf(base, elevated, lit, optical, tinted, tumbled, reseeded, resized, base).withIndex()) {
            val actual = case.render(renderer)
            val expected = case.render(SeaGlassRenderer(composeMaskRasterizer))
            assertPixels(expected, actual, "Cache transition $index")
            assertSame(actual, case.render(renderer), "Unchanged material should reuse its completed pixels")
            val inkOnly = case.copy(palette = case.palette.copy(ink = case.palette.ink xor 0x00FFFFFF))
            assertSame(actual, inkOnly.render(renderer), "Content ink must not invalidate material pixels")
            val alphaOnly = inkOnly.copy(palette = inkOnly.palette.copy(
                body = inkOnly.palette.body or 0x40000000,
                edge = inkOnly.palette.edge or 0x80000000.toInt(),
            ))
            assertSame(actual, alphaOnly.render(renderer), "Unused palette alpha must not invalidate material pixels")
        }
    }

    @Test
    fun cancellationCannotPublishPartialPixelsOrPoisonSubsequentRenders() {
        val base = Case()
        val target = base.copy(settings = RenderSettings(
            frost = 0.85, grain = 0.75, tumble = 0.06, thickness = 13.0,
            bulge = 0.9, elevation = 15.0, transmission = 0.8, light = 135.0, seed = 73,
        ))
        val expectedBase = base.render(SeaGlassRenderer(composeMaskRasterizer))
        val expectedTarget = target.render(SeaGlassRenderer(composeMaskRasterizer))
        val measuringRenderer = SeaGlassRenderer(composeMaskRasterizer)
        base.render(measuringRenderer)
        var probeCount = 0
        target.render(measuringRenderer) { probeCount++ }
        val interruptionPoints = listOf(1, 2, 3, probeCount / 4, probeCount / 2, probeCount - 1, probeCount).filter { it > 0 }.distinct()
        for (interruptionPoint in interruptionPoints) {
            val renderer = SeaGlassRenderer(composeMaskRasterizer)
            val published = base.render(renderer)
            var calls = 0
            assertFailsWith<InterruptedRender>("Probe $interruptionPoint/$probeCount") {
                target.render(renderer) {
                    if (++calls == interruptionPoint) throw InterruptedRender()
                }
            }
            assertPixels(expectedBase, published, "Previously published pixels after cancellation $interruptionPoint")
            assertPixels(expectedTarget, target.render(renderer), "Retry after cancellation $interruptionPoint")
            assertPixels(expectedBase, base.render(renderer), "Return to prior settings after cancellation $interruptionPoint")
        }
    }

    private fun assertGround(dimensions: Dimensions, alpha: ByteArray, settings: RenderSettings, label: String) {
        val originalAlpha = alpha.copyOf()
        val expected = FrozenGroundReference.renderGroundLayers(dimensions.width, dimensions.height, dimensions.scale, settings, palette, alpha, dimensions.bodyWidth, dimensions.bodyHeight)
        val actual = renderGroundLayers(dimensions.width, dimensions.height, dimensions.scale, settings, palette, alpha, dimensions.bodyWidth, dimensions.bodyHeight)
        assertPixels(expected, actual, label)
        assertContentEquals(originalAlpha, alpha, "Input alpha must remain immutable: $label")
    }

    private fun assertPixels(expected: PixelBuffer, actual: PixelBuffer, label: String) {
        assertEquals(expected.width, actual.width, "$label width")
        assertEquals(expected.height, actual.height, "$label height")
        assertContentEquals(expected.pixels, actual.pixels, label)
    }

    private fun patternMask(contour: ByteArray, pattern: Int): ByteArray = ByteArray(contour.size) { i ->
        val alpha = contour[i].toInt() and 255
        when (pattern) {
            0 -> 0
            1 -> alpha
            2 -> alpha * ((i * 37 + 17) and 255) / 255
            else -> if (i % 11 == 0 || i % 17 == 0) alpha else 0
        }.toByte()
    }

    private fun patternedPixels(width: Int, height: Int, pattern: Int): PixelBuffer = PixelBuffer(width, height, IntArray(width * height) { i ->
        val alpha = when (pattern) {
            0 -> 0
            1 -> 255
            2 -> (i * 37 + 17) and 255
            else -> if (i % 11 == 0) 255 else if (i % 17 == 0) 1 else 0
        }
        (alpha shl 24) or (((i * 101 + 251) and 255) shl 16) or (((i * 67 + 31) and 255) shl 8) or ((i * 23 + 11) and 255)
    })

    private data class Dimensions(val width: Double, val height: Double, val scale: Double) {
        val bodyWidth get() = ceil(width * scale).toInt()
        val bodyHeight get() = ceil(height * scale).toInt()
        fun contourMask(shape: RenderShape): ByteArray = composeMaskRasterizer(
            contourSegments(buildContour(width, height, shape, 0.065, 42)), bodyWidth, bodyHeight, scale, 0.0, 0.0,
        )
    }

    private data class Case(
        val width: Double = 48.5,
        val height: Double = 21.25,
        val scale: Double = 1.25,
        val shape: RenderShape = RenderShape.Rounded,
        val settings: RenderSettings = RenderSettings(),
        val palette: RenderPalette = RenderPalette(0xB5D0EB, 0xD6E9FF, 0x2F647C),
    ) {
        fun render(renderer: SeaGlassRenderer, cancellationProbe: () -> Unit = {}) = renderer.render(width, height, scale, shape, settings, palette, cancellationProbe)
    }

    private class InterruptedRender : RuntimeException()
}

/**
 * Frozen pre-optimization implementation, copied verbatim from GroundLayers.kt.
 * This oracle intentionally keeps the original full-image blur and bilinear
 * compositing. Do not refactor it alongside production code: it guards exact
 * rounding, transparent support, cropped convolution and fractional sampling.
 */
private object FrozenGroundReference {
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
        val short = min(widthDp, heightDp)
        val transmission = settings.transmission
        val pad = MATERIAL_PADDING_DP * scale

        fun layer(offsetDp: Double, blurDp: Double, color: Int, opacity: (Double) -> Double) {
            val mask = DoubleArray(size)
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
                for (x in 0 until width) {
                    val sourceX = x - pad
                    mask[y * width + x] = sampleAlpha(bodyAlpha, bodyWidth, bodyHeight, sourceX, sourceY) * opacityAtY
                }
            }
            val blurred = gaussianBlur(mask, width, height, blurDp * scale, cancellationProbe)
            val r = ((color ushr 16) and 255).toDouble()
            val g = ((color ushr 8) and 255).toDouble()
            val b = (color and 255).toDouble()
            val cancellationStride = width * 32
            for (index in 0 until size) {
                if (index % cancellationStride == 0) cancellationProbe()
                val sourceAlpha = blurred[index].coerceIn(0.0, 1.0)
                val destinationWeight = 1.0 - sourceAlpha
                red[index] = r * sourceAlpha + red[index] * destinationWeight
                green[index] = g * sourceAlpha + green[index] * destinationWeight
                blue[index] = b * sourceAlpha + blue[index] * destinationWeight
                alpha[index] = sourceAlpha + alpha[index] * destinationWeight
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
        for (y in top until bottom) {
            if (((y - top) and 31) == 0) cancellationProbe()
            val sourceY = (y + 0.5 - pad) / destinationHeight * body.height - 0.5
            val y0 = floor(sourceY).toInt()
            val fy = sourceY - y0
            for (x in left until right) {
                val sourceX = (x + 0.5 - pad) / destinationWidth * body.width - 0.5
                val x0 = floor(sourceX).toInt()
                val fx = sourceX - x0
                var alpha = 0.0
                var red = 0.0
                var green = 0.0
                var blue = 0.0
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

    private fun gaussianBlur(
        source: DoubleArray,
        width: Int,
        height: Int,
        sigma: Double,
        cancellationProbe: () -> Unit,
    ): DoubleArray {
        if (sigma <= 0.01) return source
        val radius = ceil(sigma * 3.0).toInt()
        val kernel = DoubleArray(radius * 2 + 1)
        var sum = 0.0
        for (i in kernel.indices) {
            val distance = (i - radius).toDouble()
            kernel[i] = exp(-distance * distance / (2.0 * sigma * sigma))
            sum += kernel[i]
        }
        for (i in kernel.indices) kernel[i] /= sum
        val horizontal = DoubleArray(source.size)
        val output = DoubleArray(source.size)
        // Transparent outside the image; do not renormalize the kernel at bounds.
        for (y in 0 until height) {
            if ((y and 31) == 0) cancellationProbe()
            val row = y * width
            for (x in 0 until width) {
                var value = 0.0
                for (dx in max(-radius, -x)..min(radius, width - x - 1)) {
                    value += source[row + x + dx] * kernel[dx + radius]
                }
                horizontal[row + x] = value
            }
        }
        for (y in 0 until height) {
            if ((y and 31) == 0) cancellationProbe()
            for (x in 0 until width) {
                var value = 0.0
                for (dy in max(-radius, -y)..min(radius, height - y - 1)) {
                    value += horizontal[(y + dy) * width + x] * kernel[dy + radius]
                }
                output[y * width + x] = value
            }
        }
        return output
    }
}
