package dev.mttmcknn.seaglass.internal

/** CPU stages visible when Android system tracing enables this app's events. */
internal inline fun <T> traceMaterial(name: String, block: () -> T): T {
    beginMaterialTrace(name)
    try {
        return block()
    } finally {
        endMaterialTrace()
    }
}

internal expect fun beginMaterialTrace(name: String)
internal expect fun endMaterialTrace()

/** Unique Android trace identity; no renderer state or logging outside tracing. */
internal expect fun nextMaterialTraceId(): Int
internal expect fun traceMaterialRevision(stage: String, surfaceId: Int, revision: Long)
