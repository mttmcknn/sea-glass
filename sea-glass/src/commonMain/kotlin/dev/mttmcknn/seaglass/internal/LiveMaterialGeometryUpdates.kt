package dev.mttmcknn.seaglass.internal

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

// Share the limit across surfaces: a scene with several pieces must leave CPU
// capacity for composition, input and the platform renderer.
private val liveGeometryDispatcher = Dispatchers.Default.limitedParallelism(2)

internal data class PreparedLiveGeometry(
    val key: LiveMaterialGeometryKey,
    val requestId: Long,
    val geometry: LiveMaterialGeometry,
)

/**
 * One active geometry build and the latest pending edit, owned by a UI effect.
 *
 * Call [run] once from the surface's LaunchedEffect. All other methods, and
 * completion publication, stay on that effect's UI thread. Only [build] runs on
 * [dispatcher]; it owns its cloud cache and returns immutable geometry. Native
 * shaders, paths and graphics layers never enter this worker.
 *
 * Requests and synchronous adoptions use ordinary fields, so draw callbacks can
 * enqueue work without writing snapshot state. A completed intermediate edit may
 * be displayed during a drag; a synchronous adoption invalidates all older work.
 * The renderer must also compare requestId with its applied revision and reject
 * completions whose logical dimensions no longer match its current surface.
 */
internal class LiveMaterialGeometryUpdates(
    private val dispatcher: CoroutineDispatcher = liveGeometryDispatcher,
    private val build: suspend (LiveMaterialGeometryKey, LiveMaterialCloudCache) -> LiveMaterialGeometry = ::buildLiveGeometry,
) {
    val traceId = nextMaterialTraceId()
    private val requests = Channel<Request>(Channel.CONFLATED)
    private val completedState = mutableStateOf<PreparedLiveGeometry?>(null)
    val completed: State<PreparedLiveGeometry?> get() = completedState

    private var desired: Request? = null
    private var revision = 0L
    private var lastEnqueuedRevision = 0L
    private var adoptedRevision = 0L
    private var started = false
    private var closed = false

    /** Repeated draws of the latest requested key do not enqueue another build. */
    fun request(key: LiveMaterialGeometryKey): Long {
        check(!closed)
        val request = desired(key)
        if (request.id > lastEnqueuedRevision && request.id > adoptedRevision) {
            check(requests.trySend(request).isSuccess)
            lastEnqueuedRevision = request.id
        }
        return request.id
    }

    /**
     * Mark a complete geometry synchronously adopted by the renderer. This also
     * replaces obsolete queued work with a marker, without cancelling an active
     * build or clearing snapshot state from a draw callback.
     */
    fun invalidate(key: LiveMaterialGeometryKey): Long {
        check(!closed)
        val request = desired(key)
        if (request.id > adoptedRevision) {
            adoptedRevision = request.id
            check(requests.trySend(request.copy(needsBuild = false)).isSuccess)
            lastEnqueuedRevision = request.id
        }
        return request.id
    }

    /** The caller owns cancellation, completion and failure for this lifetime. */
    suspend fun run() {
        check(!started) { "Geometry updates need exactly one lifetime owner" }
        started = true
        val clouds = LiveMaterialCloudCache()
        try {
            for (request in requests) {
                if (!request.needsBuild || request.id <= adoptedRevision) continue
                val geometry = withContext(dispatcher) { build(request.key, clouds) }
                currentCoroutineContext().ensureActive()
                if (!closed && request.id > adoptedRevision) {
                    completedState.value = PreparedLiveGeometry(request.key, request.id, geometry)
                }
            }
        } finally {
            // withContext has finished (or cancelled) its child before cleanup.
            // close() never clears this cache while a worker might be using it.
            clouds.clear()
        }
    }

    /** Stop accepting requests and suppress late results; the UI cancels [run]. */
    fun close() {
        if (closed) return
        closed = true
        requests.cancel()
    }

    private fun desired(key: LiveMaterialGeometryKey): Request {
        desired?.takeIf { it.key == key }?.let { return it }
        return Request(key, ++revision).also {
            desired = it
            traceMaterialRevision("Request", traceId, it.id)
        }
    }

    private data class Request(val key: LiveMaterialGeometryKey, val id: Long, val needsBuild: Boolean = true)
}

private suspend fun buildLiveGeometry(key: LiveMaterialGeometryKey, clouds: LiveMaterialCloudCache): LiveMaterialGeometry {
    val context = currentCoroutineContext()
    context.ensureActive()
    return sharedLiveMaterialGeometryCache.geometryFor(key) {
        traceMaterial("SeaGlass.Geometry") {
            makeLiveMaterialGeometry(
                key.widthDp, key.heightDp, key.shape, key.tumble, key.seed,
                composeMaskRasterizer, key.fieldScale, clouds,
                cancellationProbe = { context.ensureActive() },
            )
        }
    }
}
