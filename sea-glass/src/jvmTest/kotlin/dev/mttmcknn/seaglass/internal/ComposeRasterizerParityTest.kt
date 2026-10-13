package dev.mttmcknn.seaglass.internal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the real Compose/Skia adapters in addition to pure shader parity. */
class ComposeRasterizerParityTest {
    @Test
    fun completeComposePipelineMatchesTheLargeWebReference() {
        val reference = resource("web-reference.png").use { ImageIO.read(it) }
        val bitmap = ImageBitmap(reference.width, reference.height)
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, reference.width.toFloat(), reference.height.toFloat(), Paint().apply { color = Color.White })
        val palettes = listOf(
            RenderPalette(0xC0DED0, 0xE6FFF0, 0x396C5A),
            RenderPalette(0xB5D0EB, 0xD6E9FF, 0x2F647C),
        )
        for ((row, palette) in palettes.withIndex()) {
            for ((width, x, shape) in listOf(Triple(108.0, 90.0, RenderShape.Disc), Triple(320.0, 275.0, RenderShape.Rounded))) {
                val renderer = SeaGlassRenderer(composeMaskRasterizer)
                val material = renderer.render(width, 108.0, 2.0, shape, RenderSettings(), palette).toImageBitmap()
                canvas.save()
                canvas.translate((x - MATERIAL_PADDING_DP).toFloat(), (75 + row * 230 - MATERIAL_PADDING_DP).toFloat())
                canvas.scale(0.5f, 0.5f)
                canvas.drawImage(material, Offset.Zero, Paint().apply { filterQuality = FilterQuality.Low })
                canvas.restore()
            }
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.readPixels(pixels)
        val native = BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_ARGB)
        native.setRGB(0, 0, bitmap.width, bitmap.height, pixels, 0, bitmap.width)
        var squared = 0.0
        var maximum = 0
        var channels = 0
        for (y in 0 until reference.height) for (x in 0 until reference.width) {
            val a = reference.getRGB(x, y)
            val b = native.getRGB(x, y)
            if (a == -1 && b == -1) continue
            for (shift in listOf(16, 8, 0)) {
                val error = abs((a ushr shift and 255) - (b ushr shift and 255))
                squared += error * error
                maximum = max(maximum, error)
                channels++
            }
        }
        val rms = sqrt(squared / channels)
        val comparison = BufferedImage(reference.width * 2, reference.height + 52, BufferedImage.TYPE_INT_RGB)
        val graphics = comparison.createGraphics()
        graphics.color = java.awt.Color.WHITE
        graphics.fillRect(0, 0, comparison.width, comparison.height)
        graphics.color = java.awt.Color(35, 55, 59)
        graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 22)
        graphics.drawString("Web", 34, 35)
        graphics.drawString("Compose", reference.width + 34, 35)
        graphics.drawImage(reference, 0, 52, null)
        graphics.drawImage(native, reference.width, 52, null)
        graphics.dispose()
        val output = File("build/reports/sea-glass/compose-comparison.png")
        output.parentFile.mkdirs()
        ImageIO.write(comparison, "png", output)
        println("Compose visual comparison: RMS=$rms, maximum channel error=$maximum; ${output.absolutePath}")
        assertTrue(rms < 1.25 && maximum < 8, "Compose gallery RMS=$rms maximum=$maximum")
    }

    @Test
    fun composeContourCoverageMatchesTheWebOracle() {
        val manifest = properties("manifest.properties")
        for (id in manifest.getProperty("cases").split(',')) {
            val p = properties("$id/case.properties")
            val shape = when (p.getProperty("shape")) {
                "disc" -> RenderShape.Disc
                "capsule" -> RenderShape.Capsule
                else -> RenderShape.Rounded
            }
            val points = buildContour(p.number("width"), p.number("height"), shape, p.number("tumble"), p.getProperty("seed").toLong().toInt())
            val mask = composeMaskRasterizer(
                contourSegments(points), p.number("pixelWidth").toInt() + 2, p.number("pixelHeight").toInt() + 2,
                p.number("ratio").coerceAtMost(2.0), 1.0, 1.0,
            )
            val expected = resource("$id/mask.u8").use { it.readBytes() }
            assertEquals(expected.size, mask.size, "$id coverage size")
            var squared = 0.0
            var maximum = 0
            var thresholdChanges = 0
            for (i in mask.indices) {
                val actual = mask[i].toInt() and 255
                val reference = expected[i].toInt() and 255
                val error = abs(actual - reference)
                squared += error * error
                maximum = max(maximum, error)
                if ((actual > 127) != (reference > 127)) thresholdChanges++
            }
            val rms = sqrt(squared / mask.size)
            // Different Skia versions can change edge antialiasing slightly.
            // Coverage must remain close enough to retain the distance field.
            assertTrue(rms < 2.0 && maximum < 32 && thresholdChanges <= 2,
                "$id coverage RMS=$rms max=$maximum threshold changes=$thresholdChanges")
        }
    }

    @Test
    fun nativePixelUploadRetainsColorAndAlpha() {
        val source = intArrayOf(0xFF19324B.toInt(), 0xFFB5D0EB.toInt(), 0x8064C832.toInt(), 0xC8DCF0B4.toInt(), 0)
        val bitmap = PixelBuffer(source.size, 1, source).toImageBitmap()
        val actual = IntArray(source.size)
        bitmap.readPixels(actual)
        for (i in source.indices) {
            assertEquals(source[i] ushr 24, actual[i] ushr 24, "alpha at $i")
            for (shift in listOf(16, 8, 0)) {
                val error = abs((source[i] ushr shift and 255) - (actual[i] ushr shift and 255))
                assertTrue(error <= 1, "color at $i, shift=$shift, error=$error")
            }
        }
    }

    private fun resource(path: String) = checkNotNull(javaClass.getResourceAsStream("/reference/$path")) { "Missing reference fixture $path" }
    private fun properties(path: String) = Properties().apply { resource(path).use { load(it) } }
    private fun Properties.number(key: String) = getProperty(key).toDouble()
}
