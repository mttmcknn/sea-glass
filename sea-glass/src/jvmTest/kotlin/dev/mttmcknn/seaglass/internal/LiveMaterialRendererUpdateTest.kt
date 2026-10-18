package dev.mttmcknn.seaglass.internal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.mttmcknn.seaglass.SeaGlassPalette
import dev.mttmcknn.seaglass.SeaGlassShape
import dev.mttmcknn.seaglass.SeaGlassStyle
import dev.mttmcknn.seaglass.SeaGlassSurface
import dev.mttmcknn.seaglass.seaGlass
import java.awt.EventQueue
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap

/** Real native pixels, with only the geometry worker's completion controlled. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class LiveMaterialRendererUpdateTest {
    @Test
    fun surfaceContentEdgesMatchTheOriginalGraphicsLayerClip() = runTest {
        for (shape in SeaGlassShape.entries) for (density in listOf(1f, 1.5f)) {
            val style = SeaGlassStyle.Tumbled.copy(seed = 3_600_011 + shape.ordinal * 97)
            fun capture(originalLayerClip: Boolean): ByteArray {
                val scene = ImageComposeScene(width = 420, height = 300, density = Density(density), coroutineContext = coroutineContext)
                try {
                    scene.setContent {
                        Box(Modifier.fillMaxSize().background(Color.White)) {
                            val modifier = Modifier.offset(48.dp, 48.dp).size(144.dp, 64.dp)
                            if (originalLayerClip) {
                                val outline = remember(style, shape) { OriginalOutline(style, shape) }
                                Box(modifier.seaGlass(style, SeaGlassPalette.Mint, shape).clip(outline), contentAlignment = Alignment.Center) {
                                    Box(Modifier.fillMaxSize().background(Color(0xFFED2070)))
                                }
                            } else {
                                SeaGlassSurface(modifier, style, SeaGlassPalette.Mint, shape) {
                                    Box(Modifier.fillMaxSize().background(Color(0xFFED2070)))
                                }
                            }
                        }
                    }
                    return scene.render(0).use { image ->
                        Bitmap().use { bitmap ->
                            check(bitmap.allocN32Pixels(image.width, image.height))
                            check(image.readPixels(bitmap))
                            checkNotNull(bitmap.readPixels())
                        }
                    }
                } finally {
                    scene.close()
                }
            }
            assertExactPixelsEqual(capture(true), capture(false), "Content-edge antialiasing must match the original layer clip for $shape at density $density")
        }
    }

    @Test
    fun firstPaintIsCompleteAndPendingSeedShapeAndTumbleKeepBodyAndContentTogether() = runTest {
        val builder = ControlledBuilder()
        val updates = LiveMaterialGeometryUpdates(StandardTestDispatcher(testScheduler), builder::build)
        val initial = RenderSettings(seed = 3_100_011, tumble = 0.012)
        MaterialScene(coroutineContext, initial, updates = updates).use { scene ->
            val first = scene.render()
            assertTrue(first.distinctRgbCount() > 32, "First draw must contain shaded material, not a flat placeholder")
            val initialContour = scene.contour
            deliverPendingChanges()
            assertTrue(builder.started.isEmpty(), "First draw must not wait for the background worker")
            assertExactPixelsEqual(first, scene.render())

            val final = initial.copy(seed = -3_100_013, tumble = 0.061)
            scene.settings.value = final
            scene.shape.value = RenderShape.Disc
            val pending = scene.render()
            assertExactPixelsEqual(first, pending, "Pending geometry must keep old body, grain seed and clipping contour together")
            assertSame(initialContour, scene.contour)
            deliverPendingChanges()
            assertEquals(RenderShape.Disc, builder.started.single().key.shape)

            builder.started.single().release.complete(Unit)
            deliverPendingChanges()
            assertEquals(final.seed, updates.completed.value?.key?.seed, "The worker must publish the final request before the next frame")
            val finalPixels = scene.render()
            assertFalse(first.contentEquals(finalPixels), "The settled shape and seeded grain must visibly change")
            assertNotSame(initialContour, scene.contour)
            scene.assertMatchesFresh(coroutineContext, finalPixels)
        }
    }

    @Test
    fun intermediateCompletionsRemainCoherentAndTheNewestPendingEditEventuallyWins() = runTest {
        val builder = ControlledBuilder()
        val updates = LiveMaterialGeometryUpdates(StandardTestDispatcher(testScheduler), builder::build)
        val initial = RenderSettings(seed = 3_200_011, tumble = 0.012)
        MaterialScene(coroutineContext, initial, updates = updates).use { scene ->
            scene.render()
            deliverPendingChanges()
            val intermediate = initial.copy(seed = 3_200_013, tumble = 0.03)
            scene.settings.value = intermediate
            scene.shape.value = RenderShape.Capsule
            scene.render()
            deliverPendingChanges()
            scene.settings.value = initial.copy(seed = 3_200_017, tumble = 0.04)
            scene.render()
            val final = initial.copy(seed = 3_200_019, tumble = 0.065)
            scene.settings.value = final
            scene.shape.value = RenderShape.Disc
            scene.render()
            deliverPendingChanges()
            assertEquals(1, builder.started.size)

            builder.started[0].release.complete(Unit)
            deliverPendingChanges()
            assertEquals(listOf(intermediate.seed, final.seed), builder.started.map { it.key.seed })
            val intermediatePixels = scene.render()
            MaterialScene(coroutineContext, intermediate, RenderShape.Capsule).use { reference ->
                assertExactPixelsEqual(reference.render(), intermediatePixels, "Intermediate body, path and seed must describe the same completed geometry")
            }

            builder.started[1].release.complete(Unit)
            deliverPendingChanges()
            assertEquals(final.seed, updates.completed.value?.key?.seed, "The newest queued request must reach completion")
            scene.assertMatchesFresh(coroutineContext, scene.render())
        }
    }

    @Test
    fun returningToTheDisplayedRecipeRejectsTheOlderActiveResult() = runTest {
        val builder = ControlledBuilder()
        val updates = LiveMaterialGeometryUpdates(StandardTestDispatcher(testScheduler), builder::build)
        val initial = RenderSettings(seed = 3_300_011)
        MaterialScene(coroutineContext, initial, updates = updates).use { scene ->
            val first = scene.render()
            val firstContour = scene.contour
            deliverPendingChanges()
            scene.settings.value = initial.copy(seed = 3_300_013, tumble = 0.06)
            scene.shape.value = RenderShape.Disc
            scene.render()
            deliverPendingChanges()
            assertEquals(1, builder.started.size)

            scene.settings.value = initial
            scene.shape.value = RenderShape.Rounded
            assertExactPixelsEqual(first, scene.render())
            builder.started.single().release.complete(Unit)
            deliverPendingChanges()
            assertExactPixelsEqual(first, scene.render(), "Late work must not replace a synchronously retained recipe")
            assertSame(firstContour, scene.contour)
        }
    }

    @Test
    fun resizeDuringAnActiveEditPaintsCompleteNewBoundsAndRejectsTheOldSize() = runTest {
        val builder = ControlledBuilder()
        val updates = LiveMaterialGeometryUpdates(StandardTestDispatcher(testScheduler), builder::build)
        val initial = RenderSettings(seed = 3_400_011)
        MaterialScene(coroutineContext, initial, updates = updates).use { scene ->
            scene.render()
            deliverPendingChanges()
            scene.settings.value = initial.copy(seed = 3_400_013, tumble = 0.06)
            scene.shape.value = RenderShape.Capsule
            scene.render()
            deliverPendingChanges()
            val oldBuild = builder.started.single()
            scene.dimensions.value = Dimensions(176, 88)
            val resized = scene.render()
            scene.assertMatchesFresh(coroutineContext, resized)
            assertEquals(1, builder.started.size, "The first resized draw must be synchronous and complete")

            oldBuild.release.complete(Unit)
            deliverPendingChanges()
            assertExactPixelsEqual(resized, scene.render(), "A late old-size field must not stretch or replace the resized material")
        }
    }

    @Test
    fun densityChangesKeepLogicalGeometryAndMatchAFreshNativePath() = runTest {
        val builder = ControlledBuilder()
        val updates = LiveMaterialGeometryUpdates(StandardTestDispatcher(testScheduler), builder::build)
        MaterialScene(coroutineContext, RenderSettings(seed = 3_500_011),
            initialDimensions = Dimensions(88, 48), updates = updates).use { scene ->
            scene.render()
            val firstContour = scene.contour
            deliverPendingChanges()
            scene.displayDensity.value = 1.5f
            val resized = scene.render()
            deliverPendingChanges()
            assertTrue(builder.started.isEmpty(), "Density alone must not rebuild logical geometry")
            assertNotSame(firstContour, scene.contour)
            scene.assertMatchesFresh(coroutineContext, resized)
        }
    }

    private class ControlledBuilder {
        val started = mutableListOf<Build>()

        suspend fun build(key: LiveMaterialGeometryKey, clouds: LiveMaterialCloudCache): LiveMaterialGeometry {
            val build = Build(key)
            started += build
            build.release.await()
            // Do not seed the shared cache: this deliberately exercises delivery
            // of completed geometry independently from an incidental cache hit.
            return makeLiveMaterialGeometry(
                key.widthDp, key.heightDp, key.shape, key.tumble, key.seed,
                composeMaskRasterizer, key.fieldScale, clouds,
            )
        }
    }

    private fun TestScope.deliverPendingChanges() {
        deliverPendingChanges(testScheduler)
    }

    private class Build(val key: LiveMaterialGeometryKey) {
        val release = CompletableDeferred<Unit>()
    }

    private data class Dimensions(val width: Int = 144, val height: Int = 64)

    /** The pre-worker surface's exact Shape implementation, kept as a test oracle. */
    private class OriginalOutline(private val style: SeaGlassStyle, private val shape: SeaGlassShape) : Shape {
        override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
            val points = buildContour(
                size.width / density.density.toDouble(), size.height / density.density.toDouble(),
                shape.renderShape(), style.renderSettings().tumble, style.seed,
            )
            return Outline.Generic(contourToPath(contourSegments(points), density.density.toDouble()))
        }
    }

    private class MaterialScene(
        context: CoroutineContext,
        initialSettings: RenderSettings,
        initialShape: RenderShape = RenderShape.Rounded,
        initialDimensions: Dimensions = Dimensions(),
        initialDensity: Float = 1f,
        updates: LiveMaterialGeometryUpdates = LiveMaterialGeometryUpdates(),
    ) : AutoCloseable {
        val settings = mutableStateOf(initialSettings)
        val shape = mutableStateOf(initialShape)
        val dimensions = mutableStateOf(initialDimensions)
        val displayDensity = mutableStateOf(initialDensity)
        var contour: Path? = null
            private set
        private val renderer = checkNotNull(LiveMaterialRenderer.create(updates))
        private val scheduler = checkNotNull(context[TestCoroutineScheduler])
        private val scene = ImageComposeScene(width = 320, height = 224, density = Density(1f), coroutineContext = context)
        private var frameTime = 0L

        init {
            scene.setContent {
                LaunchedEffect(renderer) { renderer.prepareUpdates() }
                CompositionLocalProvider(LocalDensity provides Density(displayDensity.value)) {
                    val cast = rememberGraphicsLayer()
                    val contact = rememberGraphicsLayer()
                    val transmission = rememberGraphicsLayer()
                    val ground = remember(cast, contact, transmission) { RealtimeGround(cast, contact, transmission) }
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        val size = dimensions.value
                        Box(Modifier.offset(48.dp, 48.dp).size(size.width.dp, size.height.dp).drawWithContent {
                            val path = renderer.draw(this, ground, settings.value, Palette, shape.value)
                            contour = path
                            if (path != null) clipPath(path) { this@drawWithContent.drawContent() }
                        }) {
                            // This translucent fill reaches every outer edge. Any
                            // mismatched content clip changes exact pixel output,
                            // while the material body and grain remain visible.
                            Box(Modifier.fillMaxSize().background(Color(0x40ED2070)))
                        }
                    }
                }
            }
        }

        fun render(): ByteArray {
            // Deliver caller state writes as well as worker completions before
            // this one requested frame; rendering alone is not an AWT barrier.
            deliverPendingChanges(scheduler)
            return scene.render(frameTime.also { frameTime += 16_666_667L }).use { image ->
                Bitmap().use { bitmap ->
                    check(bitmap.allocN32Pixels(image.width, image.height))
                    check(image.readPixels(bitmap))
                    checkNotNull(bitmap.readPixels())
                }
            }
        }

        fun assertMatchesFresh(context: CoroutineContext, actual: ByteArray) {
            MaterialScene(context, settings.value, shape.value, dimensions.value, displayDensity.value).use { fresh ->
                assertExactPixelsEqual(fresh.render(), actual, "A settled material and its content clip must exactly match a fresh renderer")
            }
        }

        override fun close() {
            scene.close()
            renderer.close()
        }
    }

    private fun ByteArray.distinctRgbCount(): Int = (indices step 4).mapTo(mutableSetOf()) { index ->
        ((this[index].toInt() and 255) shl 16) or ((this[index + 1].toInt() and 255) shl 8) or (this[index + 2].toInt() and 255)
    }.size

    companion object {
        private val Palette = RenderPalette(0xFFC0DED0.toInt(), 0xFFE6FFF0.toInt(), 0xFF396C5A.toInt())

        private fun deliverPendingChanges(scheduler: TestCoroutineScheduler) {
            scheduler.runCurrent()
            // Desktop Compose's GlobalSnapshotManager uses the AWT event thread
            // even when scene coroutines use our test scheduler. Wait behind any
            // in-flight global apply notification, then flush remaining writes on
            // that same dispatcher before capturing the scene. A notification on
            // the test thread alone can race an observer already running on AWT.
            if (EventQueue.isDispatchThread()) {
                Snapshot.sendApplyNotifications()
            } else {
                EventQueue.invokeAndWait { Snapshot.sendApplyNotifications() }
            }
            scheduler.runCurrent()
            // No forced draw or extra frame: production snapshot observation
            // must still invalidate the material and content clip by itself.
        }

        private fun assertExactPixelsEqual(expected: ByteArray, actual: ByteArray, message: String = "Pixel buffers must be identical") {
            if (expected.contentEquals(actual)) return
            val sharedSize = minOf(expected.size, actual.size)
            var firstMismatch = -1
            var differences = kotlin.math.abs(expected.size - actual.size)
            for (index in 0 until sharedSize) {
                if (expected[index] != actual[index]) {
                    if (firstMismatch < 0) firstMismatch = index
                    differences++
                }
            }
            if (firstMismatch < 0) firstMismatch = sharedSize
            val expectedValue = expected.getOrNull(firstMismatch)?.toUByte()?.toString() ?: "end"
            val actualValue = actual.getOrNull(firstMismatch)?.toUByte()?.toString() ?: "end"
            fail("$message. Expected ${expected.size} bytes, actual ${actual.size}; $differences differing bytes; first mismatch at $firstMismatch: expected $expectedValue, actual $actualValue.")
        }
    }
}
