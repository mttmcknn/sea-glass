package dev.mttmcknn.seaglass.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LargeNoiseGridTest {
    @Test
    fun randomAccessMatchesSequentialValuesAndPreservesFollowingStream() {
        for (seed in listOf(0, 42, -1, Int.MIN_VALUE)) {
            val sequential = SeededRandom(seed)
            val indexed = SeededRandom(seed)
            repeat(10_001) { index ->
                assertEquals(sequential.nextDouble(), indexed.sample(index.toLong()))
            }
            indexed.advance(10_001)
            repeat(32) { assertEquals(sequential.nextDouble(), indexed.nextDouble()) }
        }
        val wrapped = SeededRandom(42)
        wrapped.advance(1L shl 32)
        val original = SeededRandom(42)
        repeat(32) { assertEquals(original.nextDouble(), wrapped.nextDouble()) }
    }

    @Test
    fun lazyGridMatchesEagerGridAndKeepsLaterGridSeedsIdentical() {
        val eagerRandom = SeededRandom(-1)
        val lazyRandom = SeededRandom(-1)
        for (spacing in listOf(0.68, 1.7, 7.5)) {
            val eager = NoiseGrid(417.0, 319.0, spacing, eagerRandom, maxCachedCells = Int.MAX_VALUE)
            val lazy = NoiseGrid(417.0, 319.0, spacing, lazyRandom, maxCachedCells = 0)
            for (y in 0..64) for (x in 0..64) {
                val px = x * 417.0 / 64.0
                val py = y * 319.0 / 64.0
                assertEquals(eager(px, py), lazy(px, py), "Sample ($px, $py) at spacing $spacing")
            }
        }
        repeat(32) { assertEquals(eagerRandom.nextDouble(), lazyRandom.nextDouble()) }
    }

    @Test
    fun veryLargeDownsampledTextureNeedsOnlyItsOutputArrays() {
        // Eager fine noise alone would require over 500MB for these dimensions.
        val texture = makeTexture(10_000.0, 7_000.0, 0.01, seed = 42)
        assertEquals(100, texture.width)
        assertEquals(70, texture.height)
        assertTrue(texture.noise.all { it.isFinite() })
        assertTrue(texture.clouds.all { it.isFinite() })
    }

    @Test
    fun subpixelOutputExtendsTheNoiseGridWithoutAnOutOfBoundsSample() {
        val texture = makeTexture(5.3, 2.1, 0.03, seed = 42)
        assertEquals(1, texture.width)
        assertEquals(1, texture.height)
        assertTrue(texture.noise.single().isFinite())
        assertTrue(texture.clouds.single().isFinite())
        assertTrue(texture.roughX.single().isFinite())
        assertTrue(texture.roughY.single().isFinite())
    }
}
