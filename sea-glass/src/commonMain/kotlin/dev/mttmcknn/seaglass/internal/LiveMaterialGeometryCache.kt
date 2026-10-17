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
    private val entries = LinkedHashMap<LiveMaterialGeometryKey, Entry>()
    private var retainedBytes = 0L
    private var generation = 0L

    /** A draw may reuse a ready result, but must never wait for a worker. */
    internal fun getIfPresent(key: LiveMaterialGeometryKey): LiveMaterialGeometry? {
        if (!mutex.tryLock()) return null
        try {
            val entry = entries.remove(key) ?: return null
            entries[key] = entry
            return entry.geometry
        } finally {
            mutex.unlock()
        }
    }

    internal fun geometryFor(key: LiveMaterialGeometryKey, build: () -> LiveMaterialGeometry): LiveMaterialGeometry {
        if (maxEntries == 0 || maxBytes == 0L || !mutex.tryLock()) return build()
        val generationAtMiss: Long
        try {
            entries.remove(key)?.let { entry ->
                entries[key] = entry
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
            entries.remove(key)?.let { entry ->
                entries[key] = entry
                return entry.geometry
            }
            while (entries.size >= maxEntries || retainedBytes > maxBytes - bytes) {
                val oldest = entries.keys.first()
                retainedBytes -= checkNotNull(entries.remove(oldest)).bytes
            }
            entries[key] = Entry(geometry, bytes)
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
            entries.clear()
            retainedBytes = 0L
            return true
        } finally {
            mutex.unlock()
        }
    }

    /** Nonblocking diagnostics; a busy cache returns no snapshot. */
    internal fun snapshot(): GeometryCacheSnapshot? {
        if (!mutex.tryLock()) return null
        try {
            return GeometryCacheSnapshot(entries.size, retainedBytes)
        } finally {
            mutex.unlock()
        }
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
