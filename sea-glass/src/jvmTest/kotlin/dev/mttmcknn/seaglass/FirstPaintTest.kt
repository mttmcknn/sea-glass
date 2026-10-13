package dev.mttmcknn.seaglass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap

/**
 * CI guard for complete first-draw materials on the live Skia backend.
 *
 * These tests deliberately inspect the very first render, before yielding to
 * another coroutine. Each material uses an otherwise-unused seed and verifies
 * both a varied body and tinted light outside its bounds. An unchanged flat
 * placeholder therefore cannot satisfy the first-frame stability assertion.
 */
@OptIn(ExperimentalComposeUiApi::class)
class FirstPaintTest {
    @Test
    fun aColdCapsuleButtonIsFullyShadedOnItsFirstDraw() = withScene(360, 240) {
        val bounds = MaterialBounds(64, 72, 232, 72)
        setContent {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                SeaGlassButton(
                    onClick = {},
                    modifier = Modifier.offset(bounds.x.dp, bounds.y.dp).size(bounds.width.dp, bounds.height.dp),
                    style = SeaGlassStyle.Reference.copy(seed = 10_000_177, grain = .68f),
                    palette = SeaGlassPalette.Lilac,
                    shape = SeaGlassShape.Capsule,
                ) {}
            }
        }

        val first = readFirstPaintFrame()
        first.assertCompleteMaterial(bounds, "cold capsule button")
        assertNoLaterReplacement(first, "A cold button must not replace a placeholder after its first draw")
    }

    @Test
    fun aMultiSurfaceThemeAndItsLightChangesAreCompleteOnTheFirstDraw() = withScene(704, 240) {
        val selected = mutableStateOf(SeaGlassStyle.Reference.copy(grain = .68f, lightDegrees = 315f))
        val palettes = listOf(SeaGlassPalette.Mint, SeaGlassPalette.Lilac, SeaGlassPalette.Amber)
        val bounds = List(3) { MaterialBounds(48 + it * 216, 64, 160, 72) }
        setContent {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                SeaGlassTheme(style = selected.value) {
                    val material = SeaGlassTheme.style
                    palettes.forEachIndexed { index, palette ->
                        val placement = bounds[index]
                        SeaGlassSurface(
                            modifier = Modifier.offset(placement.x.dp, placement.y.dp)
                                .size(placement.width.dp, placement.height.dp),
                            style = material.copy(seed = 20_000_047 + index * 97),
                            palette = palette,
                            shape = SeaGlassShape.Rounded,
                        ) {}
                    }
                }
            }
        }

        val first = readFirstPaintFrame()
        bounds.forEachIndexed { index, placement -> first.assertCompleteMaterial(placement, "surface $index") }
        assertNoLaterReplacement(first, "All three surfaces must have their final material on the first draw")

        selected.value = selected.value.copy(lightDegrees = 135f)
        // No yield, polling or warmup between the typed theme update and its first frame.
        val updated = readFirstPaintFrame()
        bounds.forEachIndexed { index, placement ->
            assertFalse(
                first.bodyPixels(placement).contentEquals(updated.bodyPixels(placement)),
                "Surface $index must show the new light in the first frame after the theme changes",
            )
            updated.assertCompleteMaterial(placement, "updated surface $index")
        }
        assertNoLaterReplacement(updated, "The first light-update frame must already contain the final material")
    }

    private fun withScene(width: Int, height: Int, block: suspend ImageComposeScene.() -> Unit) = runBlocking {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f), coroutineContext = coroutineContext)
        try {
            scene.block()
        } finally {
            scene.close()
        }
    }

    private fun ImageComposeScene.readFirstPaintFrame(): MaterialFrame = render(System.nanoTime()).use { image ->
        Bitmap().use { bitmap ->
            check(bitmap.allocN32Pixels(image.width, image.height))
            check(image.readPixels(bitmap))
            MaterialFrame(image.width, checkNotNull(bitmap.readPixels()))
        }
    }

    private suspend fun ImageComposeScene.assertNoLaterReplacement(first: MaterialFrame, message: String) {
        // Drain later frames so delayed bitmap publication or a progressive
        // replacement is observable. This is a correctness test, not a timer.
        repeat(6) {
            delay(40)
            assertContentEquals(first.bytes, readFirstPaintFrame().bytes, message)
        }
    }

    private data class MaterialBounds(val x: Int, val y: Int, val width: Int, val height: Int)

    private data class MaterialFrame(val width: Int, val bytes: ByteArray) {
        // N32's RGB channel order is platform-specific, but equality, variation
        // and tint do not depend on which of those channels comes first.
        private fun color(x: Int, y: Int): Int {
            val offset = (y * width + x) * 4
            return ((bytes[offset].toInt() and 255) shl 16) or
                ((bytes[offset + 1].toInt() and 255) shl 8) or (bytes[offset + 2].toInt() and 255)
        }

        fun bodyPixels(bounds: MaterialBounds): IntArray = IntArray(bounds.width * bounds.height) { index ->
            color(bounds.x + index % bounds.width, bounds.y + index / bounds.width)
        }

        fun assertCompleteMaterial(bounds: MaterialBounds, label: String) {
            val centerX = bounds.x + bounds.width / 2
            val centerY = bounds.y + bounds.height / 2
            val bodyColors = buildSet {
                for (y in centerY - 8 until centerY + 8) for (x in centerX - 8 until centerX + 8) add(color(x, y))
            }
            assertTrue(bodyColors.size >= 12, "$label must contain shading and texture, not a uniform body-color placeholder")
            assertTrue(bodyColors.any { it != 0xFFFFFF }, "$label must draw a visible body")

            var tintedGroundPixels = 0
            for (y in bounds.y + bounds.height + 2 until bounds.y + bounds.height + 24) {
                for (x in bounds.x + bounds.width / 4 until bounds.x + bounds.width * 3 / 4) {
                    val rgb = color(x, y)
                    val first = rgb ushr 16 and 255
                    val second = rgb ushr 8 and 255
                    val third = rgb and 255
                    if (first != second || second != third) tintedGroundPixels++
                }
            }
            assertTrue(tintedGroundPixels >= 20, "$label must include transmitted colored light below the shape on its first draw")
        }
    }
}
