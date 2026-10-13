package dev.mttmcknn.seaglass.internal

import java.util.Properties
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Bound the visual error of the optional four-box ground blur independently. */
class FastGroundQualityTest {
    @Test
    fun fourBoxGroundStaysWithinOneHalfRmsAndThreeMaximumChannelsOverWhite() {
        var comparedCases = 0
        for (fixture in fixtures()) {
            val styles = listOf(
                "fixture" to fixture.settings,
                "minimum" to fixture.settings.copy(
                    frost = 0.0, grain = 0.0, tumble = 0.0, thickness = 0.0,
                    bulge = 0.0, elevation = 0.0, transmission = 0.0,
                ),
                "maximum" to fixture.settings.copy(
                    frost = 1.0, grain = 1.0, tumble = 0.065, thickness = 14.0,
                    bulge = 1.0, elevation = 18.0, transmission = 1.0,
                ),
            )
            for ((styleName, settings) in styles) {
                // Generate the actual Kotlin material coverage at the fixture's
                // scale; this also exercises native contour rasterization.
                val texture = makeTexture(fixture.width, fixture.height, fixture.scale, settings.seed)
                val field = makeGeometryField(
                    fixture.width, fixture.height, fixture.shape, settings.tumble,
                    settings.seed, texture, composeMaskRasterizer,
                )
                val exact = fixture.ground(settings, field, fast = false)
                val fast = fixture.ground(settings, field, fast = true)
                assertEquals(exact.width, fast.width)
                assertEquals(exact.height, fast.height)
                var squaredError = 0.0
                var maximumError = 0.0
                for (index in exact.pixels.indices) for (channel in 0..2) {
                    val error = abs(overWhite(exact.pixels[index], channel) - overWhite(fast.pixels[index], channel))
                    squaredError += error * error
                    maximumError = max(maximumError, error)
                }
                val rms = sqrt(squaredError / (exact.pixels.size * 3))
                val label = "${fixture.id}/$styleName RMS=$rms maximum=$maximumError"
                println("Optional fast ground: $label")
                assertTrue(rms <= 0.5, label)
                assertTrue(maximumError <= 3.0, label)
                comparedCases++
            }
        }
        assertEquals(12, comparedCases)
    }

    private fun overWhite(argb: Int, channel: Int): Double {
        val alpha = (argb ushr 24) / 255.0
        val value = argb ushr (16 - channel * 8) and 255
        return 255.0 + (value - 255.0) * alpha
    }

    private fun fixtures() = properties("manifest.properties").getProperty("cases").split(',').map { id ->
        val values = properties("$id/case.properties")
        Fixture(
            id = id,
            width = values.number("width"),
            height = values.number("height"),
            scale = values.number("ratio").coerceAtMost(2.0),
            shape = when (values.getProperty("shape")) {
                "disc" -> RenderShape.Disc
                "capsule" -> RenderShape.Capsule
                else -> RenderShape.Rounded
            },
            settings = RenderSettings(
                frost = values.number("frost"), grain = values.number("grain"),
                tumble = values.number("tumble"), thickness = values.number("thickness"),
                bulge = values.number("bulge"), elevation = values.number("elevation"),
                light = values.number("light"), transmission = values.number("transmission"),
                seed = values.getProperty("seed").toLong().toInt(),
            ),
            palette = RenderPalette(
                values.getProperty("body").toInt(16), values.getProperty("edge").toInt(16),
                values.getProperty("ink").toInt(16),
            ),
        )
    }

    private fun properties(path: String) = Properties().apply {
        checkNotNull(this@FastGroundQualityTest.javaClass.getResourceAsStream("/reference/$path")) { "Missing fixture $path" }.use { load(it) }
    }

    private fun Properties.number(key: String) = getProperty(key).toDouble()

    private data class Fixture(
        val id: String,
        val width: Double,
        val height: Double,
        val scale: Double,
        val shape: RenderShape,
        val settings: RenderSettings,
        val palette: RenderPalette,
    ) {
        fun ground(settings: RenderSettings, field: GeometryField, fast: Boolean) = renderGroundLayers(
            width, height, scale, settings, palette, field.alpha,
            field.texture.width, field.texture.height, approximateBlur = fast,
        )
    }
}
