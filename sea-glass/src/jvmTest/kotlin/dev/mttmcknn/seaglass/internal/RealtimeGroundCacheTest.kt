package dev.mttmcknn.seaglass.internal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.fail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class RealtimeGroundCacheTest {
    @Test
    fun paletteChangesRetainNeutralShadowRecordsAndRefreshTransmittedLight() = runTest {
        GroundScene(this).use { scene ->
            val first = scene.render()
            assertEquals(listOf(1, 1, 1), scene.recordCounts)
            scene.palette.value = scene.palette.value.copy(body = 0xFFC098C6.toInt())
            val recolored = scene.render()
            assertEquals(listOf(1, 1, 2), scene.recordCounts, "Body color must record only the transmitted light")
            assertFalse(first.contentEquals(recolored), "The new pigment must reach the visible ground light")
            scene.assertMatchesFresh(this)

            val recoloredRecords = scene.recordCounts
            scene.palette.value = scene.palette.value.let { it.copy(body = it.body and 0xFFFFFF, ink = 0) }
            assertExactPixelsEqual(recolored, scene.render(), "Unused alpha and ink must not affect the ground")
            assertEquals(recoloredRecords, scene.recordCounts, "Unused colors must not record any layer")
        }
    }

    @Test
    fun cachedGroundMatchesFreshRecordsAcrossIndependentLayerUpdates() = runTest {
        GroundScene(this).use { scene ->
            scene.render()
            val initialRecords = scene.recordCounts
            scene.settings.value = scene.settings.value.copy(frost = 0.9)
            scene.render()
            assertEquals(initialRecords[1], scene.recordCounts[1], "Frost must retain the unaffected contact shadow")
            scene.assertMatchesFresh(this)

            for (next in listOf(
                scene.settings.value.copy(thickness = 12.0),
                scene.settings.value.copy(elevation = 17.0),
                scene.settings.value.copy(transmission = 0.0),
                scene.settings.value.copy(transmission = 0.8),
            )) {
                scene.settings.value = next
                scene.assertMatchesFresh(this)
            }
            scene.settings.value = scene.settings.value.copy(transmission = 0.0)
            scene.render()
            scene.palette.value = scene.palette.value.copy(body = 0xFFE0B68A.toInt())
            scene.render()
            scene.settings.value = scene.settings.value.copy(transmission = 0.8)
            scene.assertMatchesFresh(this)
        }
    }

    private class GroundScene(
        private val scope: TestScope,
        initialSettings: RenderSettings = RenderSettings(),
        initialPalette: RenderPalette = RenderPalette(0xFF9ABBA9.toInt(), 0xFF659580.toInt(), 0),
    ) : AutoCloseable {
        val settings = mutableStateOf(initialSettings)
        val palette = mutableStateOf(initialPalette)
        private lateinit var cast: GraphicsLayer
        private lateinit var contact: GraphicsLayer
        private lateinit var transmission: GraphicsLayer
        private lateinit var ground: RealtimeGround
        val recordCounts: List<Int> get() = listOf(ground.castRecordCount, ground.contactRecordCount, ground.transmissionRecordCount)
        private val scene = ImageComposeScene(width = 256, height = 176, density = Density(1f), coroutineContext = scope.coroutineContext)
        private var frameTime = 0L

        init {
            scene.setContent {
                cast = rememberGraphicsLayer()
                contact = rememberGraphicsLayer()
                transmission = rememberGraphicsLayer()
                ground = remember { RealtimeGround(cast, contact, transmission) }
                val path = remember {
                    contourToPath(contourSegments(buildContour(144.0, 64.0, RenderShape.Rounded, 0.025, 42)))
                }
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    Box(Modifier.offset(56.dp, 48.dp).size(144.dp, 64.dp).drawBehind {
                        ground.draw(this, path, 144.0, 64.0, density, settings.value, palette.value)
                    })
                }
            }
        }

        fun render(): ByteArray {
            // The offscreen scene has no platform frame host. Deliver pending
            // snapshot invalidations before capturing the requested state.
            scope.runCurrent()
            Snapshot.sendApplyNotifications()
            scope.runCurrent()
            return scene.render(frameTime.also { frameTime += 16_666_667L }).use { image ->
                Bitmap().use { bitmap ->
                    check(bitmap.allocN32Pixels(image.width, image.height))
                    check(image.readPixels(bitmap))
                    checkNotNull(bitmap.readPixels())
                }
            }
        }

        fun assertMatchesFresh(scope: TestScope) {
            val actual = render()
            GroundScene(scope, settings.value, palette.value).use { fresh ->
                assertExactPixelsEqual(fresh.render(), actual, "Updated cached ground must match a fresh renderer for ${settings.value}")
            }
        }

        override fun close() = scene.close()
    }

    companion object {
        private fun assertExactPixelsEqual(expected: ByteArray, actual: ByteArray, message: String) {
            assertEquals(expected.size, actual.size, "$message: pixel buffer size")
            val firstMismatch = expected.indices.firstOrNull { expected[it] != actual[it] } ?: return
            val differences = expected.indices.count { expected[it] != actual[it] }
            fail("$message. $differences differing bytes; first at $firstMismatch: expected ${expected[firstMismatch].toUByte()}, actual ${actual[firstMismatch].toUByte()}.")
        }
    }
}
