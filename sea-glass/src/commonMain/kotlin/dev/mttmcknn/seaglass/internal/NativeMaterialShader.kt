package dev.mttmcknn.seaglass.internal

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Small geometry lookup texture, not a rendered component image.
 *
 * RGB contains normalized mathematical fields in 0..1; A must be 1. Keep signed
 * gradients encoded and decode them in the shader. This opaque representation
 * avoids premultiplication and color-management changes on Skiko's image API.
 * The array must not be mutated while it is being uploaded.
 */
internal class NativeFloatTexture(
    val width: Int,
    val height: Int,
    val rgba: FloatArray,
) {
    init {
        require(width > 0 && height > 0)
        require(width.toLong() * height * 4L == rgba.size.toLong())
    }
}

/**
 * Immutable source/program shared by all pieces of the same material.
 *
 * Keep one lazy program for the shader source, then create one instance per
 * control. Skia compiles its immutable RuntimeEffect once per program. Android
 * has no public compiled-program cloning API, so each instance retains one
 * RuntimeShader compiled from this source and changes only its inputs later.
 */
internal expect class NativeMaterialProgram(source: String) {
    /** Null on platforms where the runtime shader API is unavailable. */
    fun createInstance(): NativeMaterialShader?

    /** Release the program only after all of its instances have been closed. */
    fun close()
}

/**
 * Mutable state confined to one control's drawing thread.
 *
 * Upload geometry only when dimensions, shape, or seed changes. Uniform changes
 * reuse the compiled shader. Draw calls use physical-pixel coordinates; shader
 * uniforms define their conversion into the material's logical dp coordinates.
 */
internal interface NativeMaterialShader {
    fun setFloatUniform(name: String, values: FloatArray)
    fun setIntUniform(name: String, value: Int)
    fun setInputTexture(name: String, texture: NativeFloatTexture)

    /** Draw the shader directly through the exact antialiased vector contour. */
    fun draw(scope: DrawScope, path: Path)

    fun close()
}
