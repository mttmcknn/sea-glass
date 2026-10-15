package dev.mttmcknn.seaglass.internal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LiveMaterialGeometryCacheTest {
    private val base = LiveMaterialGeometryKey(24.5, 16.25, RenderShape.Rounded, 0.025, 42, 1.0)

    @Test
    fun hitsReuseGeometryAndEveryGeometryInputChangesTheKey() {
        val cache = LiveMaterialGeometryCache()
        var builds = 0
        fun cached(key: LiveMaterialGeometryKey) = cache.geometryFor(key) { builds++; render(key) }
        val first = cached(base)
        assertSame(first, cached(base.copy()))
        assertEquals(1, builds)
        val changes = listOf(
            base.copy(widthDp = 24.75),
            base.copy(heightDp = 16.5),
            base.copy(shape = RenderShape.Capsule),
            base.copy(tumble = 0.06),
            base.copy(seed = -43),
            base.copy(fieldScale = 0.95),
        )
        for (key in changes) {
            val actual = cached(key)
            assertNotSame(first, actual)
            assertGeometryEquals(render(key), actual)
            assertSame(actual, cached(key.copy()))
        }
        assertEquals(changes.size + 1, builds)
        assertSame(first, cached(base))
        assertGeometryEquals(render(base), first)
    }

    @Test
    fun entryAndByteLimitsBothEvictTheLeastRecentlyUsedGeometry() {
        val bytes = estimatedGeometryBytes(render(base))
        for (cache in listOf(
            LiveMaterialGeometryCache(maxEntries = 2),
            LiveMaterialGeometryCache(maxBytes = bytes * 2, maxEntries = 32),
        )) {
            var builds = 0
            fun cached(seed: Int) = cache.geometryFor(base.copy(seed = seed)) {
                builds++
                render(base.copy(seed = seed))
            }
            val first = cached(1)
            val second = cached(2)
            assertSame(first, cached(1)) // First is now newer than second.
            val third = cached(3)
            assertEquals(3, builds)
            assertSame(first, cached(1))
            assertSame(third, cached(3))
            assertNotSame(second, cached(2), "Least recently used second geometry must have been evicted")
            assertEquals(4, builds)
            assertEquals(GeometryCacheSnapshot(2, bytes * 2), cache.snapshot())
        }
    }

    @Test
    fun oversizedGeometryBypassesRetentionWithoutFlushingUsefulEntries() {
        val firstGeometry = render(base)
        val bytes = estimatedGeometryBytes(firstGeometry)
        val cache = LiveMaterialGeometryCache(maxBytes = bytes, maxEntries = 32)
        val first = cache.geometryFor(base) { firstGeometry }
        var oversizedBuilds = 0
        val oversizedKey = base.copy(widthDp = 48.5)
        repeat(2) {
            val oversized = cache.geometryFor(oversizedKey) { oversizedBuilds++; render(oversizedKey) }
            assertGeometryEquals(render(oversizedKey), oversized)
            assertEquals(GeometryCacheSnapshot(1, bytes), cache.snapshot())
        }
        assertEquals(2, oversizedBuilds)
        assertSame(first, cache.geometryFor(base) { error("A bypass must preserve the useful entry") })
        assertTrue(cache.clear())
        assertEquals(GeometryCacheSnapshot(0, 0L), cache.snapshot())
        assertNotSame(first, cache.geometryFor(base) { render(base) })
    }

    @Test
    fun buildersRunOutsideTheLockAndConcurrentMissesReuseThePublishedWinner() {
        val cache = LiveMaterialGeometryCache()
        val firstEntered = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<LiveMaterialGeometry> {
                cache.geometryFor(base) {
                    firstEntered.countDown()
                    assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
                    render(base)
                }
            }
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            val second = executor.submit<LiveMaterialGeometry> {
                cache.geometryFor(base) {
                    secondEntered.countDown()
                    assertTrue(releaseSecond.await(5, TimeUnit.SECONDS))
                    render(base)
                }
            }
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS), "A building miss must not block another drawing thread")
            releaseFirst.countDown()
            val published = first.get(5, TimeUnit.SECONDS)
            releaseSecond.countDown()
            assertSame(published, second.get(5, TimeUnit.SECONDS), "The later builder must reuse the published geometry")
            assertEquals(1, checkNotNull(cache.snapshot()).entries)
            assertGeometryEquals(render(base), published)
        } finally {
            releaseFirst.countDown()
            releaseSecond.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun clearingDuringABuildPreventsStalePublicationAndThrowingBuildersLeaveTheCacheUsable() {
        val cache = LiveMaterialGeometryCache()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val building = executor.submit<LiveMaterialGeometry> {
                cache.geometryFor(base) {
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    render(base)
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(cache.clear(), "An in-flight build must not hold the cache lock")
            release.countDown()
            val clearedResult = building.get(5, TimeUnit.SECONDS)
            assertEquals(GeometryCacheSnapshot(0, 0L), cache.snapshot())
            assertFailsWith<IllegalStateException> {
                cache.geometryFor(base) { error("Failed geometry construction") }
            }
            assertEquals(GeometryCacheSnapshot(0, 0L), cache.snapshot())
            val current = cache.geometryFor(base) { render(base) }
            assertNotSame(clearedResult, current)
            assertSame(current, cache.geometryFor(base) { error("Expected a cache hit") })
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    private fun render(key: LiveMaterialGeometryKey): LiveMaterialGeometry = makeLiveMaterialGeometry(
        key.widthDp, key.heightDp, key.shape, key.tumble, key.seed, composeMaskRasterizer, key.fieldScale,
    )

    private fun assertGeometryEquals(expected: LiveMaterialGeometry, actual: LiveMaterialGeometry) {
        assertEquals(expected.segments, actual.segments)
        assertEquals(expected.fieldScale, actual.fieldScale)
        assertContentEquals(expected.gradientScales, actual.gradientScales)
        assertEquals(expected.geometryA.width, actual.geometryA.width)
        assertEquals(expected.geometryA.height, actual.geometryA.height)
        assertContentEquals(expected.geometryA.rgba, actual.geometryA.rgba)
        assertEquals(expected.geometryB.width, actual.geometryB.width)
        assertEquals(expected.geometryB.height, actual.geometryB.height)
        assertContentEquals(expected.geometryB.rgba, actual.geometryB.rgba)
    }
}
