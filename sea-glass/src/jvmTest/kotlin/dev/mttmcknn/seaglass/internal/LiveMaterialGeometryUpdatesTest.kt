package dev.mttmcknn.seaglass.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class LiveMaterialGeometryUpdatesTest {
    private val base = LiveMaterialGeometryKey(24.5, 16.25, RenderShape.Rounded, 0.025, 42, 1.0)

    @Test
    fun anActiveBuildCompletesAndOnlyTheLatestPendingEditRuns() = runTest {
        val builder = ControlledBuilder()
        val updates = LiveMaterialGeometryUpdates(StandardTestDispatcher(testScheduler), builder::build)
        backgroundScope.launch { updates.run() }
        val first = base.copy(tumble = 0.01)
        val latest = base.copy(tumble = 0.06)
        val firstId = updates.request(first)
        runCurrent()
        updates.request(base.copy(tumble = 0.02))
        updates.request(base.copy(tumble = 0.04))
        val latestId = updates.request(latest)
        runCurrent()
        assertEquals(listOf(first), builder.started.map { it.key })

        val firstGeometry = builder.started[0].finish()
        runCurrent()
        assertEquals(firstId, updates.completed.value?.requestId)
        assertSame(firstGeometry, updates.completed.value?.geometry)
        assertEquals(listOf(first, latest), builder.started.map { it.key })

        val finalGeometry = builder.started[1].finish()
        runCurrent()
        assertEquals(latestId, updates.completed.value?.requestId)
        assertEquals(latest, updates.completed.value?.key)
        assertSame(finalGeometry, updates.completed.value?.geometry)
        assertEquals(1, builder.maxActive, "The cloud cache must have one worker at a time")
    }

    @Test
    fun repeatedDrawRequestsDoNotBuildTheSameDesiredKeyAgain() = runTest {
        val builder = ControlledBuilder()
        val updates = LiveMaterialGeometryUpdates(StandardTestDispatcher(testScheduler), builder::build)
        backgroundScope.launch { updates.run() }
        val id = updates.request(base)
        assertEquals(id, updates.request(base.copy()))
        runCurrent()
        assertEquals(id, updates.request(base))
        builder.started.single().finish()
        runCurrent()
        assertEquals(id, updates.request(base))
        runCurrent()
        assertEquals(1, builder.started.size)
        assertEquals(id, updates.completed.value?.requestId)
    }

    @Test
    fun returningToSynchronouslyDisplayedGeometrySuppressesActiveAndQueuedEdits() = runTest {
        val builder = ControlledBuilder()
        val updates = LiveMaterialGeometryUpdates(StandardTestDispatcher(testScheduler), builder::build)
        backgroundScope.launch { updates.run() }
        val initialId = updates.invalidate(base)
        val obsolete = base.copy(seed = 43)
        updates.request(obsolete)
        runCurrent()
        updates.request(base.copy(seed = 44))
        val adoptedId = updates.invalidate(base)
        assertTrue(adoptedId > initialId)
        assertEquals(adoptedId, updates.invalidate(base.copy()))

        builder.started.single().finish()
        runCurrent()
        assertNull(updates.completed.value)
        assertEquals(listOf(obsolete), builder.started.map { it.key }, "A synchronous adoption replaces queued work")

        val finalKey = base.copy(seed = 45)
        val finalId = updates.request(finalKey)
        runCurrent()
        builder.started.last().finish()
        runCurrent()
        assertEquals(finalKey, updates.completed.value?.key)
        assertEquals(finalId, updates.completed.value?.requestId)
    }

    @Test
    fun requestingAnOldActiveKeyAgainAfterAdoptionKeepsTheNewRevision() = runTest {
        val builder = ControlledBuilder()
        val updates = LiveMaterialGeometryUpdates(StandardTestDispatcher(testScheduler), builder::build)
        backgroundScope.launch { updates.run() }
        updates.invalidate(base)
        val changed = base.copy(tumble = 0.06)
        val obsoleteId = updates.request(changed)
        runCurrent()
        updates.invalidate(base)
        val newestId = updates.request(changed)
        assertTrue(newestId > obsoleteId)

        builder.started[0].finish()
        runCurrent()
        assertNull(updates.completed.value, "The old active result must not cross the adoption boundary")
        assertEquals(listOf(changed, changed), builder.started.map { it.key })
        val latestGeometry = builder.started[1].finish()
        runCurrent()
        assertEquals(newestId, updates.completed.value?.requestId)
        assertSame(latestGeometry, updates.completed.value?.geometry)
    }

    @Test
    fun synchronousAdoptionDoesNotWriteCompletionStateFromTheDrawCallback() = runTest {
        val builder = ControlledBuilder()
        val updates = LiveMaterialGeometryUpdates(StandardTestDispatcher(testScheduler), builder::build)
        backgroundScope.launch { updates.run() }
        updates.request(base)
        runCurrent()
        builder.started.single().finish()
        runCurrent()
        val completed = updates.completed.value

        val adoptedId = updates.invalidate(base.copy(shape = RenderShape.Disc))
        assertSame(completed, updates.completed.value)
        assertTrue(adoptedId > checkNotNull(completed).requestId)
        // The renderer's applied revision rejects this retained, older snapshot.
        runCurrent()
        assertSame(completed, updates.completed.value)
    }

    @Test
    fun closingDuringABuildSuppressesItsResultAndDoesNotClearItsCache() = runTest {
        val builder = ControlledBuilder(populateCloudCache = true)
        val updates = LiveMaterialGeometryUpdates(StandardTestDispatcher(testScheduler), builder::build)
        val owner = backgroundScope.launch { updates.run() }
        updates.request(base)
        runCurrent()
        val build = builder.started.single()
        assertTrue(build.clouds.retainedSampleCount > 0)

        updates.close()
        updates.close()
        runCurrent()
        assertFalse(build.finished, "Closing the queue must not race with an active worker's storage")
        assertTrue(build.clouds.retainedSampleCount > 0)
        build.finish()
        runCurrent()
        assertNull(updates.completed.value)
        assertTrue(owner.isCompleted)
        assertEquals(0, build.clouds.retainedSampleCount, "The run owner cleans up after its worker finishes")
    }

    @Test
    fun cancellingTheUiOwnerCancelsItsWorkerAndReleasesCloudSamples() = runTest {
        val builder = ControlledBuilder(populateCloudCache = true)
        val updates = LiveMaterialGeometryUpdates(StandardTestDispatcher(testScheduler), builder::build)
        val owner = backgroundScope.launch { updates.run() }
        updates.request(base)
        runCurrent()
        val build = builder.started.single()
        owner.cancel()
        runCurrent()

        assertTrue(build.finished)
        assertTrue(owner.isCancelled)
        assertEquals(0, build.clouds.retainedSampleCount)
        assertNull(updates.completed.value)
        updates.close()
    }

    private class ControlledBuilder(private val populateCloudCache: Boolean = false) {
        val started = mutableListOf<Build>()
        private var active = 0
        var maxActive = 0
            private set

        suspend fun build(key: LiveMaterialGeometryKey, clouds: LiveMaterialCloudCache): LiveMaterialGeometry {
            val build = Build(key, clouds)
            started += build
            active++
            maxActive = maxOf(maxActive, active)
            try {
                if (populateCloudCache) clouds.textureFor(8.0, 8.0, 1.0, key.seed)
                return build.result.await()
            } finally {
                active--
                build.finished = true
            }
        }
    }

    private class Build(val key: LiveMaterialGeometryKey, val clouds: LiveMaterialCloudCache) {
        val result = CompletableDeferred<LiveMaterialGeometry>()
        var finished = false

        fun finish(): LiveMaterialGeometry {
            val geometry = LiveMaterialGeometry(
                segments = emptyList(),
                geometryA = NativeFloatTexture(1, 1, floatArrayOf(0.25f, 0.5f, 0.5f, 1f)),
                geometryB = NativeFloatTexture(1, 1, floatArrayOf(0.5f, 0.5f, 0.5f, 1f)),
                fieldScale = key.fieldScale.toFloat(),
                gradientScales = floatArrayOf(1f, 1f),
            )
            result.complete(geometry)
            return geometry
        }
    }
}
