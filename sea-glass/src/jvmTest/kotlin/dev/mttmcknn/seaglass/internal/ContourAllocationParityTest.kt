package dev.mttmcknn.seaglass.internal

import java.util.Collections
import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ContourAllocationParityTest {
    @Test
    fun fittedPointsAndSegmentsPreserveEveryCoordinateBit() {
        val dimensions = listOf(
            0.0 to 0.0, -9.0 to 3.0, 1.0 to 37.75, 29.5 to 1.0,
            48.5 to 31.25, 320.0 to 56.0, 56.0 to 320.0, 800.25 to 1200.5,
        )
        for ((width, height) in dimensions) for (shape in RenderShape.entries) {
            for (seed in listOf(0, 42, -1, Int.MIN_VALUE, Int.MAX_VALUE)) {
                for (tumble in listOf(-1.0, 0.0, 0.025, 0.065, 1.0)) {
                    assertContourMatchesReference(width, height, shape, tumble, seed, count = 160)
                }
            }
        }
    }

    @Test
    fun sampleCountClampingAndNonDefaultCountsPreserveEveryCoordinateBit() {
        for (count in listOf(-1, 0, 1, 63, 64, 65, 79, 160, 257, 513)) {
            for ((width, height) in listOf(1.0 to 1.0, 200.5 to 64.25, 43.75 to 179.5)) {
                for (shape in RenderShape.entries) for (seed in listOf(42, Int.MIN_VALUE)) {
                    val tumble = if (seed == 42) 0.0 else 0.065
                    assertContourMatchesReference(width, height, shape, tumble, seed, count)
                }
            }
        }
    }

    @Test
    fun segmentJoinsShareOnlyTheIdenticalImmutableEndpoints() {
        val points = buildContour(251.5, 79.25, RenderShape.Capsule, 0.057, seed = -419)
        val segments = contourSegments(points)
        val unique = Collections.newSetFromMap(IdentityHashMap<ContourPoint, Boolean>())
        for (i in segments.indices) {
            val current = segments[i]
            val next = segments[(i + 1) % segments.size]
            assertSame(current.end, next.start, "Shared join $i, including loop closure")
            unique.add(current.start)
            unique.add(current.control1)
            unique.add(current.control2)
            unique.add(current.end)
        }
        assertEquals(segments.size * 3, unique.size, "One join plus two controls per immutable segment")
        assertSegmentsEqual(referenceContourSegments(points), segments, "Shared joins")
        assertFailsWith<IllegalArgumentException> { contourSegments(points.take(3)) }
    }

    private fun assertContourMatchesReference(
        width: Double,
        height: Double,
        shape: RenderShape,
        tumble: Double,
        seed: Int,
        count: Int,
    ) {
        val input = "$width x $height, $shape, tumble=$tumble, seed=$seed, count=$count"
        val expected = referenceBuildContour(width, height, shape, tumble, seed, count)
        val actual = buildContour(width, height, shape, tumble, seed, count)
        assertEquals(expected.size, actual.size, input)
        for (i in expected.indices) assertPointEqual(expected[i], actual[i], "$input: fitted[$i]")
        assertSegmentsEqual(referenceContourSegments(expected), contourSegments(actual), input)
    }

    private fun assertSegmentsEqual(expected: List<ContourSegment>, actual: List<ContourSegment>, input: String) {
        assertEquals(expected.size, actual.size, input)
        for (i in expected.indices) {
            assertPointEqual(expected[i].start, actual[i].start, "$input: segment[$i].start")
            assertPointEqual(expected[i].control1, actual[i].control1, "$input: segment[$i].control1")
            assertPointEqual(expected[i].control2, actual[i].control2, "$input: segment[$i].control2")
            assertPointEqual(expected[i].end, actual[i].end, "$input: segment[$i].end")
        }
    }

    private fun assertPointEqual(expected: ContourPoint, actual: ContourPoint, input: String) {
        assertEquals(expected.x.toRawBits(), actual.x.toRawBits(), "$input: x")
        assertEquals(expected.y.toRawBits(), actual.y.toRawBits(), "$input: y")
    }
}
