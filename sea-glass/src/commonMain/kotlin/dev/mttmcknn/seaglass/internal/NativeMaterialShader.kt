package dev.mttmcknn.seaglass.internal

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Small geometry lookup texture, not a rendered component image.
 *
 * RGB contains normalized mathematical fields in 0..1; A must be 1. Keep signed
 * gradients encoded and decode them in the shader. This opaque representation
 * avoids premultiplication and color-management changes on Skiko's image API.
 * Channels are immutable little-endian IEEE binary16 bytes, matching both native
 * upload formats. Quantize once while building geometry, never again on upload.
 * The array must not be mutated after construction.
 */
internal class NativeFloatTexture(
    val width: Int,
    val height: Int,
    val rgbaF16: ByteArray,
) {
    init {
        require(width > 0 && height > 0)
        require(width.toLong() * height * 8L == rgbaF16.size.toLong())
    }

    /** Convenience for small fixtures; production geometry writes packed bytes directly. */
    constructor(width: Int, height: Int, rgba: FloatArray) : this(width, height, packFloat16(rgba))
}

private fun packFloat16(values: FloatArray): ByteArray {
    require(values.size.toLong() * 2L <= Int.MAX_VALUE)
    return ByteArray(values.size * 2).also { bytes ->
        for (index in values.indices) bytes.putFloat16(index * 2, values[index])
    }
}

internal fun ByteArray.putFloat16(byteOffset: Int, value: Float) {
    val bits = float16Bits(value)
    this[byteOffset] = bits.toByte()
    this[byteOffset + 1] = (bits ushr 8).toByte()
}

/** IEEE binary16, round-to-nearest-even, including the small-gradient range. */
internal fun float16Bits(value: Float): Int {
    val bits = value.toRawBits()
    val sign = (bits ushr 16) and 0x8000
    val exponent = (bits ushr 23) and 0xFF
    val mantissa = bits and 0x7FFFFF
    if (exponent == 0xFF) return sign or if (mantissa == 0) 0x7C00 else 0x7E00
    val halfExponent = exponent - 127 + 15
    if (halfExponent >= 31) return sign or 0x7C00
    if (halfExponent <= 0) {
        if (halfExponent < -10) return sign
        val significant = mantissa or 0x800000
        val shift = 14 - halfExponent
        var rounded = significant ushr shift
        val remainder = significant and ((1 shl shift) - 1)
        val halfway = 1 shl (shift - 1)
        if (remainder > halfway || (remainder == halfway && (rounded and 1) != 0)) rounded++
        return sign or rounded
    }
    val rounded = mantissa + 0xFFF + ((mantissa ushr 13) and 1)
    // Carry from the rounded mantissa naturally increments the half exponent.
    return sign or ((halfExponent shl 10) + (rounded ushr 13))
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
