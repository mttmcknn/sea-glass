package dev.mttmcknn.seaglass.internal

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HorizontalDistanceTransformTest {
    @Test
    fun everySmallBinaryPatternRetainsTheOriginalFloatBits() {
        for (width in 1..12) for (pattern in 0 until (1 shl width)) {
            val coverage = ByteArray(width) { x -> if (pattern and (1 shl x) == 0) 127 else 128.toByte() }
            assertTransform(coverage, width, 1, "width=$width pattern=$pattern")
        }
    }

    @Test
    fun thresholdValuesBoundaryWidthsAndRowsMatchTheGeneralTransform() {
        for (width in listOf(1, 2, 3, 7, 63, 64, 65, 255, 256, 257, 511, 512, 513, 1023, 4096, 65_537)) {
            val coverage = ByteArray(width * 8) { i ->
                val x = i % width
                when (i / width) {
                    0 -> 0
                    1 -> 255.toByte()
                    2 -> if (x == 0) 0 else 255.toByte()
                    3 -> if (x == width - 1) 0 else 255.toByte()
                    4 -> if (x == width / 2) 127 else 128.toByte()
                    5 -> if (x % 2 == 0) 127 else 128.toByte()
                    6 -> if (x % 2 == 0) 128.toByte() else 127
                    else -> intArrayOf(0, 1, 126, 127, 128, 129, 254, 255)[x % 8].toByte()
                }
            }
            assertTransform(coverage, width, 8, "width=$width boundary patterns")
        }
    }

    @Test
    fun wideCoveredRunPreservesTheFiniteSentinelCap() {
        // This crosses sqrt(1e12f). A covered pixel may select itself at the
        // original finite sentinel instead of the more distant uncovered pixel.
        val width = 1_000_005
        val coverage = ByteArray(width) { 128.toByte() }.also { it[0] = 127 }
        val actual = assertTransform(coverage, width, 1, "finite sentinel")
        assertEquals(1e12f.toRawBits(), actual.last().toRawBits())
    }

    @Test
    fun randomizedMasksDoNotLeakSitesBetweenRows() {
        val random = Random(0x51EA61A5)
        repeat(200) { iteration ->
            val width = random.nextInt(1, 514)
            val height = random.nextInt(1, 10)
            val coverage = ByteArray(width * height) { random.nextInt(256).toByte() }
            assertTransform(coverage, width, height, "random=$iteration width=$width height=$height")
        }
    }

    @Test
    fun cancellationKeepsTheSameRowCheckpoints() {
        var probes = 0
        horizontalSquaredDistances(ByteArray(3 * 65), 3, 65) { probes++ }
        assertEquals(3, probes)
        probes = 0
        assertFailsWith<CancelledTransform> {
            horizontalSquaredDistances(ByteArray(3 * 65), 3, 65) {
                if (++probes == 2) throw CancelledTransform()
            }
        }
        assertEquals(2, probes)
    }

    @Test
    fun completeFieldsMatchTheFrozenKernelForAdversarialMasks() {
        for ((width, height) in listOf(1.0 to 1.0, 2.0 to 5.0, 17.5 to 9.25, 64.0 to 64.0)) {
            for (scale in listOf(0.25, 1.0, 2.0)) for (pattern in 0..5) {
                val rasterize: MaskRasterizer = { _, columns, rows, _, _, _ ->
                    ByteArray(columns * rows) { i ->
                        val x = i % columns
                        val y = i / columns
                        when (pattern) {
                            0 -> 0
                            1 -> 255.toByte()
                            2 -> if (x == 0) 127 else 128.toByte()
                            3 -> if (x == columns - 1) 127 else 128.toByte()
                            4 -> if ((x + y) % 2 == 0) 127 else 128.toByte()
                            else -> if (x == columns / 2 && y == rows / 2) 127 else 128.toByte()
                        }
                    }
                }
                val texture = makeTexture(width, height, scale, 42)
                val expected = referenceGeometryField(width, height, RenderShape.Rounded, 0.025, 42, texture, rasterize)
                val actual = makeGeometryField(width, height, RenderShape.Rounded, 0.025, 42, texture, rasterize)
                assertEquals(expected.segments, actual.segments)
                assertContentEquals(expected.alpha, actual.alpha)
                for ((a, b) in listOf(
                    expected.profile to actual.profile,
                    expected.gradX to actual.gradX,
                    expected.gradY to actual.gradY,
                    expected.roundProfile to actual.roundProfile,
                    expected.roundGradX to actual.roundGradX,
                    expected.roundGradY to actual.roundGradY,
                )) assertFloatBits(a, b, "$width x $height scale=$scale pattern=$pattern")
            }
        }
    }

    private fun assertTransform(coverage: ByteArray, width: Int, height: Int, description: String): FloatArray {
        val expected = referenceHorizontalDistances(coverage, width, height)
        return horizontalSquaredDistances(coverage, width, height).also { assertFloatBits(expected, it, description) }
    }

    private fun assertFloatBits(expected: FloatArray, actual: FloatArray, description: String) {
        assertEquals(expected.size, actual.size, description)
        for (i in expected.indices) {
            val expectedBits = expected[i].toRawBits()
            val actualBits = actual[i].toRawBits()
            if (expectedBits != actualBits) assertEquals(expectedBits, actualBits, "$description index=$i")
        }
    }

    private class CancelledTransform : RuntimeException()
}

/** Frozen pre-specialization initialization and horizontal pass, independent of the candidate. */
private fun referenceHorizontalDistances(coverage: ByteArray, width: Int, height: Int): FloatArray {
    val distances = FloatArray(width * height) { if ((coverage[it].toInt() and 255) > 127) 1e12f else 0f }
    val input = DoubleArray(width)
    val output = DoubleArray(width)
    val sites = IntArray(width)
    val separators = DoubleArray(width + 1)
    for (y in 0 until height) {
        for (x in 0 until width) input[x] = distances[y * width + x].toDouble()
        referenceHorizontalDistanceLine(input, width, output, sites, separators)
        for (x in 0 until width) distances[y * width + x] = output[x].toFloat()
    }
    return distances
}

private fun referenceHorizontalDistanceLine(input: DoubleArray, length: Int, output: DoubleArray, sites: IntArray, separators: DoubleArray) {
    var k = 0
    sites[0] = 0
    separators[0] = Double.NEGATIVE_INFINITY
    separators[1] = Double.POSITIVE_INFINITY
    for (q in 1 until length) {
        var cut: Double
        do {
            val v = sites[k]
            cut = ((input[q] + q.toDouble() * q) - (input[v] + v.toDouble() * v)) / (2.0 * q - 2.0 * v)
            if (cut <= separators[k]) k-- else break
        } while (k >= 0)
        k++
        sites[k] = q
        separators[k] = cut
        separators[k + 1] = Double.POSITIVE_INFINITY
    }
    k = 0
    for (q in 0 until length) {
        while (separators[k + 1] < q) k++
        val delta = q - sites[k]
        output[q] = delta.toDouble() * delta + input[sites[k]]
    }
}
