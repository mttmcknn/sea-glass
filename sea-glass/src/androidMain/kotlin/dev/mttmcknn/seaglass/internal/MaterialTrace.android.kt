package dev.mttmcknn.seaglass.internal

import android.os.Trace
import android.os.Build
import java.util.concurrent.atomic.AtomicInteger

// The framework emits events only while tracing is enabled for the process.
internal actual fun beginMaterialTrace(name: String) = Trace.beginSection(name)
internal actual fun endMaterialTrace() = Trace.endSection()

private val materialTraceIds = AtomicInteger()
internal actual fun nextMaterialTraceId(): Int = materialTraceIds.incrementAndGet()
internal actual fun traceMaterialRevision(stage: String, surfaceId: Int, revision: Long) {
    if (Build.VERSION.SDK_INT >= 29 && Trace.isEnabled()) {
        Trace.beginSection("SeaGlass.$stage:$surfaceId:$revision")
        Trace.endSection()
    }
}
