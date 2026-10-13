package dev.mttmcknn.seaglass.internal

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.awt.image.BufferedImage
import java.io.File
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertTrue

/** Native shader compilation and before/after body images, independent of Compose scheduling. */
class LiveMaterialBodyTest {
    @Test
    fun reportNativeBodyQualityAgainstReferenceRenderer() {
        val report = File("build/reports/sea-glass-live").apply { mkdirs() }
        val program = NativeMaterialProgram(SeaGlassBodyShader)
        try {
            for (id in listOf("lagoon-rounded", "mint-disc", "lilac-capsule", "amber-portrait")) {
                val properties = Properties().apply {
                    LiveMaterialBodyTest::class.java.getResourceAsStream("/reference/$id/case.properties")!!.use(::load)
                }
                fun number(key: String) = properties.getProperty(key).toDouble()
                val width = number("width")
                val height = number("height")
                val scale = 2.0
                val shape = when (properties.getProperty("shape")) {
                    "disc" -> RenderShape.Disc
                    "capsule" -> RenderShape.Capsule
                    else -> RenderShape.Rounded
                }
                val settings = RenderSettings(
                    frost = number("frost"), grain = number("grain"), tumble = number("tumble"),
                    thickness = number("thickness"), bulge = number("bulge"), elevation = number("elevation"),
                    light = number("light"), transmission = number("transmission"), seed = number("seed").toInt(),
                )
                val palette = RenderPalette(
                    properties.getProperty("body").toInt(16), properties.getProperty("edge").toInt(16),
                    properties.getProperty("ink").toInt(16),
                )
                val referenceTexture = makeTexture(width, height, scale, settings.seed)
                val referenceField = makeGeometryField(width, height, shape, settings.tumble, settings.seed, referenceTexture, composeMaskRasterizer)
                val reference = shadeBody(referenceField, settings, palette)
                lateinit var geometry: LiveMaterialGeometry
                val geometryNanos = measureNanoTime {
                    geometry = makeLiveMaterialGeometry(width, height, shape, settings.tumble, settings.seed, composeMaskRasterizer)
                }
                val shader = checkNotNull(program.createInstance())
                try {
                    val uploadNanos = measureNanoTime {
                        shader.setInputTexture("geometryA", geometry.geometryA)
                        shader.setInputTexture("geometryB", geometry.geometryB)
                    }
                    fun uniform(name: String, vararg values: Float) = shader.setFloatUniform(name, values)
                    shader.setMaterialLight(settings.light)
                    uniform("materialSize", width.toFloat(), height.toFloat())
                    uniform("fieldScale", geometry.fieldScale)
                    uniform("density", scale.toFloat())
                    shader.setFloatUniform("gradientScales", geometry.gradientScales)
                    uniform("recipeA", settings.frost.toFloat(), settings.grain.toFloat(), settings.thickness.toFloat(), settings.bulge.toFloat())
                    uniform("recipeB", settings.light.toFloat(), settings.transmission.toFloat(), SeededRandom(settings.seed).nextDouble().toFloat(), 0f)
                    shader.setFloatUniform("bodyColor", rgbComponents(palette.body).map { it / 255f }.toFloatArray())
                    shader.setFloatUniform("edgeColor", rgbComponents(palette.edge).map { it / 255f }.toFloatArray())
                    val pixelWidth = ceil(width * scale).toInt()
                    val pixelHeight = ceil(height * scale).toInt()
                    val image = ImageBitmap(pixelWidth, pixelHeight)
                    val path = contourToPath(geometry.segments, scale)
                    val drawNanos = measureNanoTime {
                        CanvasDrawScope().draw(Density(scale.toFloat()), LayoutDirection.Ltr, Canvas(image), Size(pixelWidth.toFloat(), pixelHeight.toFloat())) {
                            shader.draw(this, path)
                        }
                    }
                    val actual = IntArray(pixelWidth * pixelHeight)
                    image.readPixels(actual)
                    val expected = reference.pixels
                    val errors = IntArray(actual.size * 3)
                    var totalError = 0L
                    var totalSquaredError = 0L
                    val before = BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_RGB)
                    val after = BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_RGB)
                    for (pixel in actual.indices) {
                        var beforePixel = 0
                        var afterPixel = 0
                        for (channel in 0..2) {
                            val shift = 16 - channel * 8
                            fun overWhite(argb: Int): Int {
                                val alpha = (argb ushr 24) / 255.0
                                return (((argb ushr shift and 255) * alpha) + 255 * (1 - alpha)).roundToInt()
                            }
                            val a = overWhite(actual[pixel])
                            val b = overWhite(expected[pixel])
                            val error = abs(a - b)
                            errors[pixel * 3 + channel] = error
                            totalError += error
                            totalSquaredError += error * error
                            beforePixel = beforePixel or (b shl shift)
                            afterPixel = afterPixel or (a shl shift)
                        }
                        before.setRGB(pixel % pixelWidth, pixel / pixelWidth, beforePixel)
                        after.setRGB(pixel % pixelWidth, pixel / pixelWidth, afterPixel)
                    }
                    errors.sort()
                    val mae = totalError.toDouble() / errors.size
                    val rmse = sqrt(totalSquaredError.toDouble() / errors.size)
                    val p95 = errors[(errors.size * .95).toInt().coerceAtMost(errors.lastIndex)]
                    val lowFrequencyMae = blurredDifference(before, after)
                    ImageIO.write(before, "png", File(report, "$id-reference.png"))
                    ImageIO.write(after, "png", File(report, "$id-live.png"))
                    val line = "$id: MAE=$mae RMSE=$rmse p95=$p95 max=${errors.last()} blur3_MAE=$lowFrequencyMae geometryMs=${geometryNanos / 1e6} uploadMs=${uploadNanos / 1e6} softwareDrawMs=${drawNanos / 1e6}"
                    println(line)
                    File(report, "$id-metrics.txt").writeText(line)
                    // Grain positions may change, but color, form, and highlights
                    // must remain close to the inspected reference material.
                    assertTrue(mae < 3.0, line)
                    assertTrue(lowFrequencyMae < 1.5, line)
                    assertTrue(p95 <= 8, line)
                } finally {
                    shader.close()
                }
            }
        } finally {
            program.close()
        }
    }

    private fun blurredDifference(before: BufferedImage, after: BufferedImage): Double {
        var difference = 0.0
        var count = 0
        for (y in 0 until before.height) for (x in 0 until before.width) for (channel in 0..2) {
            var a = 0.0
            var b = 0.0
            var samples = 0
            val shift = 16 - channel * 8
            for (dy in -3..3) for (dx in -3..3) {
                val sx = (x + dx).coerceIn(0, before.width - 1)
                val sy = (y + dy).coerceIn(0, before.height - 1)
                a += before.getRGB(sx, sy) ushr shift and 255
                b += after.getRGB(sx, sy) ushr shift and 255
                samples++
            }
            difference += abs(a - b) / samples
            count++
        }
        return difference / max(1, count)
    }
}
