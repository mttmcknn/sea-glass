package dev.mttmcknn.seaglass.internal

internal actual fun beginMaterialTrace(name: String) = Unit
internal actual fun endMaterialTrace() = Unit
internal actual fun nextMaterialTraceId(): Int = 0
internal actual fun traceMaterialRevision(stage: String, surfaceId: Int, revision: Long) = Unit
