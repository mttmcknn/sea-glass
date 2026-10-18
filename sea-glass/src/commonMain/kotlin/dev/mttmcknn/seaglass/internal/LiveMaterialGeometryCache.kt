package dev.mttmcknn.seaglass.internal

import kotlinx.coroutines.sync.Mutex

/** Geometry is in dp; display density belongs to the native path and uniforms. */
internal data class LiveMaterialGeometryKey(
    val widthDp: Double,
    val heightDp: Double,
    val shape: RenderShape,
    val tumble: Double,
    val seed: Int,
    /** The effective sampling scale, already capped to 1.0. */
    val fieldScale: Double,
)

/**
 * Bounded reuse of immutable geometry between surfaces and scene visits.
 *
 * A drawing thread never waits for another drawing thread: a contended cache is
 * bypassed. Builders run outside the lock. Once published, geometry arrays and
 * contour objects must remain read-only, including while native inputs upload.
 *
 * First use enters probation immediately. A cache hit earns protection against
 * one-off scans; protection has a 75% target, while both queues share the same
 * total byte and entry limits. Large entries can borrow protected capacity.
 */
internal class LiveMaterialGeometryCache(
    private val maxBytes: Long = 4L * 1024 * 1024,
    private val maxEntries: Int = 32,
) {
    init {
        require(maxBytes >= 0)
        require(maxEntries >= 0)
    }

    private val mutex = Mutex()
    private val probation = LinkedHashMap<LiveMaterialGeometryKey, Entry>()
    private val protectedEntries = LinkedHashMap<LiveMaterialGeometryKey, Entry>()
    // Divide before multiplying to avoid overflow for caller-supplied budgets.
    private val protectedMaxBytes = maxBytes / 4 * 3 + maxBytes % 4 * 3 / 4
    private val protectedMaxEntries = (maxEntries.toLong() * 3 / 4).toInt()
    private var retainedBytes = 0L
    private var protectedBytes = 0L
    private var generation = 0L

    /** A draw may reuse a ready result, but must never wait for a worker. */
    internal fun getIfPresent(key: LiveMaterialGeometryKey): LiveMaterialGeometry? {
        if (!mutex.tryLock()) return null
        try {
            val entry = getAndProtect(key) ?: return null
            return entry.geometry
        } finally {
            mutex.unlock()
        }
    }

    internal fun geometryFor(key: LiveMaterialGeometryKey, build: () -> LiveMaterialGeometry): LiveMaterialGeometry {
        if (maxEntries == 0 || maxBytes == 0L || !mutex.tryLock()) return build()
        val generationAtMiss: Long
        try {
            getAndProtect(key)?.let { entry ->
                return entry.geometry
            }
            generationAtMiss = generation
        } finally {
            mutex.unlock()
        }

        val geometry = build()
        val bytes = estimatedGeometryBytes(geometry)
        if (bytes > maxBytes || !mutex.tryLock()) return geometry
        try {
            // clear() also invalidates misses that were already building.
            if (generation != generationAtMiss) return geometry
            getAndProtect(key)?.let { entry ->
                return entry.geometry
            }
            // Make room before insertion, so even an entry larger than the
            // probation allowance is retained on its first use. Protection is
            // a preference, not another allocation or a hard admission limit.
            while (entryCount >= maxEntries || retainedBytes > maxBytes - bytes) {
                val evicted = if (probation.isNotEmpty()) {
                    checkNotNull(probation.remove(probation.keys.first()))
                } else {
                    checkNotNull(protectedEntries.remove(protectedEntries.keys.first())).also {
                        protectedBytes -= it.bytes
                    }
                }
                retainedBytes -= evicted.bytes
            }
            probation[key] = Entry(geometry, bytes)
            retainedBytes += bytes
            return geometry
        } finally {
            mutex.unlock()
        }
    }

    /** False means another thread currently owns the cache; this never waits. */
    internal fun clear(): Boolean {
        if (!mutex.tryLock()) return false
        try {
            generation++
            probation.clear()
            protectedEntries.clear()
            retainedBytes = 0L
            protectedBytes = 0L
            return true
        } finally {
            mutex.unlock()
        }
    }

    /** Nonblocking diagnostics; a busy cache returns no snapshot. */
    internal fun snapshot(): GeometryCacheSnapshot? {
        if (!mutex.tryLock()) return null
        try {
            return GeometryCacheSnapshot(entryCount, retainedBytes)
        } finally {
            mutex.unlock()
        }
    }

    private val entryCount: Int get() = probation.size + protectedEntries.size

    /** Called with the cache lock held; moving an entry never changes total bytes. */
    private fun getAndProtect(key: LiveMaterialGeometryKey): Entry? {
        protectedEntries.remove(key)?.let { entry ->
            protectedEntries[key] = entry
            return entry
        }
        val entry = probation.remove(key) ?: return null
        protectedEntries[key] = entry
        protectedBytes += entry.bytes
        while (protectedEntries.size > protectedMaxEntries || protectedBytes > protectedMaxBytes) {
            val oldest = protectedEntries.keys.first()
            val demoted = checkNotNull(protectedEntries.remove(oldest))
            protectedBytes -= demoted.bytes
            probation[oldest] = demoted
        }
        return entry
    }

    private data class Entry(val geometry: LiveMaterialGeometry, val bytes: Long)
}

internal data class GeometryCacheSnapshot(val entries: Int, val retainedBytes: Long)

// Count actual sample storage plus conservative object/list/contour estimates.
// Count all four points per segment even if a producer shares endpoints.
// This budget excludes each renderer's GPU inputs.
internal fun estimatedGeometryBytes(geometry: LiveMaterialGeometry): Long =
    geometry.geometryA.rgbaF16.size.toLong() + geometry.geometryB.rgbaF16.size + geometry.gradientScales.size * 4L +
        geometry.segments.size * 192L + 512L

internal val sharedLiveMaterialGeometryCache = LiveMaterialGeometryCache()
