package dev.mttmcknn.seaglass.internal

import kotlin.math.ceil
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class LiveMaterialCloudCacheTest {
    @Test
    fun tumbleAndShapeEditsReuseCloudsWithExactlyTheSameGeometryAsUncachedRendering() {
        for (scale in listOf(0.65, 1.0, 2.0)) for (seed in listOf(42, -17, Int.MIN_VALUE)) {
            val cache = LiveMaterialCloudCache()
            for (shape in RenderShape.entries) for (tumble in listOf(0.0, 0.025, 0.065)) {
                val expected = makeLiveMaterialGeometry(48.5, 31.25, shape, tumble, seed, composeMaskRasterizer, scale)
                val actual = makeLiveMaterialGeometry(48.5, 31.25, shape, tumble, seed, composeMaskRasterizer, scale, cache)
                assertGeometryEquals(expected, actual)
            }
            assertEquals(1, cache.buildCount, "Shape and tumble must reuse all existing cloud samples")
            val effectiveScale = min(scale, 1.0)
            assertEquals(ceil(48.5 * effectiveScale).toInt() * ceil(31.25 * effectiveScale).toInt(), cache.retainedSampleCount)
        }
    }

    @Test
    fun logicalDimensionsScaleAndSeedEachReplaceTheSingleCachedEntry() {
        val cache = LiveMaterialCloudCache(maxCachedSamples = 64)
        var previous = cache.textureFor(8.0, 8.0, 1.0, 42)
        assertSame(previous, cache.textureFor(8.0, 8.0, 1.0, 42))
        val baseline = previous.clouds.copyOf()
        var builds = 1
        // All variants still round to an 8x8 field. Keying only by array size
        // would incorrectly reuse samples with different logical coordinates.
        for (changed in listOf(
            CloudInput(7.5, 8.0, 1.0, 42),
            CloudInput(8.0, 7.5, 1.0, 42),
            CloudInput(8.0, 8.0, 0.95, 42),
            CloudInput(8.0, 8.0, 1.0, 43),
        )) {
            val replacement = cache.textureFor(changed.width, changed.height, changed.scale, changed.seed)
            assertNotSame(previous, replacement)
            assertSame(replacement, cache.textureFor(changed.width, changed.height, changed.scale, changed.seed))
            assertEquals(++builds, cache.buildCount)
            assertEquals(64, cache.retainedSampleCount)

            val restored = cache.textureFor(8.0, 8.0, 1.0, 42)
            assertNotSame(previous, restored, "The previous entry must have been evicted")
            assertContentEquals(baseline, restored.clouds, "Eviction must not change deterministic samples")
            assertEquals(++builds, cache.buildCount)
            previous = restored
        }
    }

    @Test
    fun oversizedCloudFieldsRenderExactlyWithoutGrowingTheRetainedCache() {
        val cache = LiveMaterialCloudCache(maxCachedSamples = 64)
        val retained = cache.textureFor(8.0, 8.0, 1.0, 42)
        // Fractional dimensions round up, so the 9x8 allocation exceeds the
        // budget even though checking the unrounded area would fit 64 samples.
        repeat(2) {
            val expected = makeLiveMaterialGeometry(8.1, 7.9, RenderShape.Rounded, 0.025, 42, composeMaskRasterizer)
            val actual = makeLiveMaterialGeometry(8.1, 7.9, RenderShape.Rounded, 0.025, 42, composeMaskRasterizer, cloudCache = cache)
            assertGeometryEquals(expected, actual)
            assertEquals(64, cache.retainedSampleCount)
        }
        assertEquals(3, cache.buildCount, "Oversized fields must not be retained")
        assertSame(retained, cache.textureFor(8.0, 8.0, 1.0, 42), "Transient oversized fields preserve the bounded entry")
        assertEquals(3, cache.buildCount)
        cache.clear()
        assertEquals(0, cache.retainedSampleCount, "Disposal must release the retained samples")
        val rebuilt = cache.textureFor(8.0, 8.0, 1.0, 42)
        assertNotSame(retained, rebuilt)
        assertContentEquals(retained.clouds, rebuilt.clouds)
        assertEquals(4, cache.buildCount)
    }

    private fun assertGeometryEquals(expected: LiveMaterialGeometry, actual: LiveMaterialGeometry) {
        assertEquals(expected.segments, actual.segments)
        assertEquals(expected.fieldScale, actual.fieldScale)
        assertContentEquals(expected.gradientScales, actual.gradientScales)
        assertEquals(expected.geometryA.width, actual.geometryA.width)
        assertEquals(expected.geometryA.height, actual.geometryA.height)
        assertContentEquals(expected.geometryA.rgbaF16, actual.geometryA.rgbaF16)
        assertEquals(expected.geometryB.width, actual.geometryB.width)
        assertEquals(expected.geometryB.height, actual.geometryB.height)
        assertContentEquals(expected.geometryB.rgbaF16, actual.geometryB.rgbaF16)
    }

    private data class CloudInput(val width: Double, val height: Double, val scale: Double, val seed: Int)
}
