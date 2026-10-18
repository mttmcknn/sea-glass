package dev.mttmcknn.seaglass.internal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LiveMaterialGeometryCacheTest {
    private val base = LiveMaterialGeometryKey(24.5, 16.25, RenderShape.Rounded, 0.025, 42, 1.0)

    @Test
    fun defaultCacheRetainsAtMostFourMiBIncludingPackedStorage() {
        val cache = LiveMaterialGeometryCache()
        repeat(32) { seed ->
            cache.geometryFor(base.copy(seed = seed)) {
                LiveMaterialGeometry(
                    emptyList(),
                    NativeFloatTexture(128, 128, ByteArray(128 * 128 * 8)),
                    NativeFloatTexture(128, 128, ByteArray(128 * 128 * 8)),
                    1f,
                    floatArrayOf(1f, 1f),
                )
            }
            val retained = checkNotNull(cache.snapshot())
            assertTrue(retained.retainedBytes <= 4L * 1024 * 1024)
            assertTrue(retained.entries <= 32)
        }
        assertEquals(15, checkNotNull(cache.snapshot()).entries, "The budget must count both packed inputs and overhead")
    }

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
    fun entryAndByteLimitsBothPreferRecentlyReusedGeometry() {
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
    fun firstUseIsCachedImmediatelyAndReturningToAnEarlierRecipeHits() {
        val firstGeometry = fixtureGeometry()
        val secondGeometry = fixtureGeometry()
        val bytes = estimatedGeometryBytes(firstGeometry)
        val cache = LiveMaterialGeometryCache(maxBytes = bytes * 2, maxEntries = 2)
        val secondKey = base.copy(tumble = 0.06)
        assertSame(firstGeometry, cache.geometryFor(base) { firstGeometry })
        assertEquals(GeometryCacheSnapshot(1, bytes), cache.snapshot())
        assertSame(secondGeometry, cache.geometryFor(secondKey) { secondGeometry })
        assertSame(firstGeometry, cache.geometryFor(base) { error("A -> B -> A must reuse the first result") })
        assertSame(secondGeometry, cache.geometryFor(secondKey) { error("First-use admission must preserve B too") })
        assertEquals(GeometryCacheSnapshot(2, bytes * 2), cache.snapshot())
    }

    @Test
    fun repeatedGeometrySurvivesAScanWhileFirstUseEntriesStayBounded() {
        val bytes = estimatedGeometryBytes(fixtureGeometry())
        val cache = LiveMaterialGeometryCache(maxBytes = bytes * 4, maxEntries = 4)
        val hotKeys = listOf(base.copy(seed = 1), base.copy(seed = 2))
        val hot = hotKeys.map { key ->
            cache.geometryFor(key) { fixtureGeometry() }.also {
                assertSame(it, cache.getIfPresent(key), "A draw-time hit also earns protection")
            }
        }
        repeat(40) { index ->
            cache.geometryFor(base.copy(seed = 100 + index)) { fixtureGeometry() }
            val retained = checkNotNull(cache.snapshot())
            assertTrue(retained.retainedBytes <= bytes * 4)
            assertTrue(retained.entries <= 4)
        }
        hotKeys.forEachIndexed { index, key ->
            assertSame(hot[index], cache.getIfPresent(key), "A one-off scan must not displace proven reuse")
        }
        assertNull(cache.getIfPresent(base.copy(seed = 100)))
        assertEquals(GeometryCacheSnapshot(4, bytes * 4), cache.snapshot())
    }

    @Test
    fun protectedEntryAndByteTargetsDemoteTheLeastRecentlyUsedHit() {
        val bytes = estimatedGeometryBytes(fixtureGeometry())
        for (cache in listOf(
            LiveMaterialGeometryCache(maxBytes = bytes * 8, maxEntries = 4),
            LiveMaterialGeometryCache(maxBytes = bytes * 4, maxEntries = 32),
        )) {
            val values = (1..4).associateWith { seed ->
                val key = base.copy(seed = seed)
                cache.geometryFor(key) { fixtureGeometry() }
            }
            for (seed in 1..3) assertSame(values[seed], cache.getIfPresent(base.copy(seed = seed)))
            // Refresh protected A; promoting D must demote B, not A or C.
            assertSame(values[1], cache.getIfPresent(base.copy(seed = 1)))
            assertSame(values[4], cache.getIfPresent(base.copy(seed = 4)))
            val fifth = cache.geometryFor(base.copy(seed = 5)) { fixtureGeometry() }
            assertNull(cache.getIfPresent(base.copy(seed = 2)))
            for (seed in listOf(1, 3, 4)) assertSame(values[seed], cache.getIfPresent(base.copy(seed = seed)))
            assertSame(fifth, cache.getIfPresent(base.copy(seed = 5)))
            assertEquals(GeometryCacheSnapshot(4, bytes * 4), cache.snapshot())
        }
    }

    @Test
    fun aLargeFirstUseCanBorrowProtectedCapacityWithoutEvictingItself() {
        val smallBytes = estimatedGeometryBytes(fixtureGeometry())
        val cache = LiveMaterialGeometryCache(maxBytes = smallBytes * 4, maxEntries = 4)
        for (seed in 1..3) {
            val key = base.copy(seed = seed)
            cache.geometryFor(key) { fixtureGeometry() }
            if (seed < 3) assertTrue(cache.getIfPresent(key) != null)
        }
        val large = fixtureGeometry(samples = 100)
        val largeBytes = estimatedGeometryBytes(large)
        assertTrue(largeBytes > smallBytes * 3)
        assertTrue(largeBytes <= smallBytes * 4)
        val key = base.copy(seed = 4)
        assertSame(large, cache.geometryFor(key) { large })
        assertEquals(GeometryCacheSnapshot(1, largeBytes), cache.snapshot())
        repeat(3) {
            assertSame(large, cache.geometryFor(key) { error("The protected target must not become an admission limit") })
        }
        assertEquals(GeometryCacheSnapshot(1, largeBytes), cache.snapshot())
    }

    @Test
    fun clearReleasesBothQueuesAndSingleEntryAndDisabledBudgetsRemainUsable() {
        val bytes = estimatedGeometryBytes(fixtureGeometry())
        val cache = LiveMaterialGeometryCache(maxBytes = bytes * 4, maxEntries = 4)
        val first = cache.geometryFor(base) { fixtureGeometry() }
        assertSame(first, cache.getIfPresent(base))
        val secondKey = base.copy(seed = 43)
        cache.geometryFor(secondKey) { fixtureGeometry() }
        assertTrue(cache.clear())
        assertEquals(GeometryCacheSnapshot(0, 0L), cache.snapshot())
        assertNull(cache.getIfPresent(base))
        assertNull(cache.getIfPresent(secondKey))
        assertNotSame(first, cache.geometryFor(base) { fixtureGeometry() })

        val single = LiveMaterialGeometryCache(maxBytes = bytes, maxEntries = 1)
        val one = single.geometryFor(base) { fixtureGeometry() }
        repeat(3) { assertSame(one, single.getIfPresent(base)) }
        val replacement = single.geometryFor(secondKey) { fixtureGeometry() }
        assertNull(single.getIfPresent(base))
        assertSame(replacement, single.getIfPresent(secondKey))
        assertEquals(GeometryCacheSnapshot(1, bytes), single.snapshot())

        for (disabled in listOf(LiveMaterialGeometryCache(maxBytes = 0), LiveMaterialGeometryCache(maxEntries = 0))) {
            val uncached = disabled.geometryFor(base) { fixtureGeometry() }
            assertNotSame(uncached, disabled.geometryFor(base) { fixtureGeometry() })
            assertEquals(GeometryCacheSnapshot(0, 0L), disabled.snapshot())
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

    private fun fixtureGeometry(samples: Int = 1) = LiveMaterialGeometry(
        segments = emptyList(),
        geometryA = NativeFloatTexture(samples, 1, ByteArray(samples * 8)),
        geometryB = NativeFloatTexture(samples, 1, ByteArray(samples * 8)),
        fieldScale = 1f,
        gradientScales = floatArrayOf(1f, 1f),
    )

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
}
