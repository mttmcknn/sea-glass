package dev.mttmcknn.seaglass.internal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GeometryFieldKernelTest {
    @Test
    fun optimizedFieldRetainsEveryFloatBitAcrossBoundarySizesScalesShapesAndSeeds() {
        val dimensions = listOf(1.0 to 1.0, 1.0 to 19.5, 27.25 to 1.0, 3.0 to 7.0, 48.5 to 31.25)
        for ((w, h) in dimensions) for (scale in listOf(0.1, 0.65, 1.0, 2.0)) {
            for (shape in RenderShape.entries) for (seed in listOf(42, Int.MIN_VALUE)) {
                val texture = makeTexture(w, h, scale, seed)
                val tumble = if (seed == 42) 0.0 else 0.065
                val expected = referenceGeometryField(w, h, shape, tumble, seed, texture, composeMaskRasterizer)
                val actual = makeGeometryField(w, h, shape, tumble, seed, texture, composeMaskRasterizer)
                val input = "$w x $h, $scale, $shape, $seed"
                assertEquals(expected.segments, actual.segments, input)
                assertContentEquals(expected.alpha, actual.alpha, input)
                for ((name, arrays) in listOf(
                    "profile" to (expected.profile to actual.profile),
                    "gradX" to (expected.gradX to actual.gradX),
                    "gradY" to (expected.gradY to actual.gradY),
                    "roundProfile" to (expected.roundProfile to actual.roundProfile),
                    "roundGradX" to (expected.roundGradX to actual.roundGradX),
                    "roundGradY" to (expected.roundGradY to actual.roundGradY),
                )) {
                    assertEquals(arrays.first.size, arrays.second.size, "$input: $name")
                    for (i in arrays.first.indices) {
                        assertEquals(arrays.first[i].toRawBits(), arrays.second[i].toRawBits(), "$input: $name[$i]")
                    }
                }
            }
        }
    }

    @Test
    fun cancellationDuringCloudSamplingDoesNotRetainAPartialField() {
        val cache = LiveMaterialCloudCache()
        var probes = 0
        assertFailsWith<CancelledBuild> {
            cache.textureFor(48.0, 96.0, 1.0, 42) {
                if (++probes == 2) throw CancelledBuild()
            }
        }
        assertEquals(2, probes)
        assertEquals(0, cache.buildCount)
        assertEquals(0, cache.retainedSampleCount)
        val texture = cache.textureFor(48.0, 96.0, 1.0, 42)
        assertEquals(1, cache.buildCount)
        assertEquals(48 * 96, texture.clouds.size)
    }

    @Test
    fun liveGeometryChecksCancellationAfterTheFieldHasBeenBuilt() {
        var fieldProbeCount = 0
        makeGeometryField(1.0, 1.0, RenderShape.Rounded, 0.025, 42, makeTexture(1.0, 1.0, 1.0, 42), composeMaskRasterizer) {
            fieldProbeCount++
        }
        var rasterized = false
        var probesAfterRasterization = 0
        val rasterizer: MaskRasterizer = { segments, width, height, scale, offsetX, offsetY ->
            composeMaskRasterizer(segments, width, height, scale, offsetX, offsetY).also { rasterized = true }
        }
        assertFailsWith<CancelledBuild> {
            makeLiveMaterialGeometry(1.0, 1.0, RenderShape.Rounded, 0.025, 42, rasterizer) {
                if (rasterized && ++probesAfterRasterization > fieldProbeCount) throw CancelledBuild()
            }
        }
        assertTrue(rasterized)
        assertEquals(fieldProbeCount + 1, probesAfterRasterization)
    }

    private class CancelledBuild : RuntimeException()
}
