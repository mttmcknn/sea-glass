package dev.mttmcknn.seaglass.internal

import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader

internal actual class NativeMaterialProgram actual constructor(source: String) {
    // A program can be shared; its per-piece uniforms must never be shared.
    private val effect = traceMaterial("SeaGlass.ProgramCreate") { RuntimeEffect.makeForShader(source) }

    actual fun createInstance(): NativeMaterialShader? = SkiaMaterialShader(effect)

    actual fun close() = effect.close()
}

private class SkiaMaterialShader(effect: RuntimeEffect) : NativeMaterialShader {
    private val builder = RuntimeShaderBuilder(effect)
    private val paint = Paint().apply { isAntiAlias = true }
    private val inputs = mutableMapOf<String, SkiaInputTexture>()
    private var snapshot: Shader? = null
    private var dirty = true
    private var closed = false

    override fun setFloatUniform(name: String, values: FloatArray) {
        check(!closed)
        builder.uniform(name, values)
        dirty = true
    }

    override fun setIntUniform(name: String, value: Int) {
        check(!closed)
        builder.uniform(name, value)
        dirty = true
    }

    override fun setInputTexture(name: String, texture: NativeFloatTexture) {
        check(!closed)
        // Supported targets (Android/JVM/iOS arm64) use little-endian channels.
        // Null color space + opaque normalized RGB prevents color conversion or
        // premultiplication. Skiko 0.9.37.4 has no public makeRawShader binding.
        // Use the verified F16 path on both platform families. The pinned Skiko
        // native runtime does not round-trip F32 image data correctly.
        val bytes = ByteArray(texture.rgba.size * 2)
        for (index in texture.rgba.indices) {
            val bits = float16Bits(texture.rgba[index])
            val offset = index * 2
            bytes[offset] = bits.toByte()
            bytes[offset + 1] = (bits ushr 8).toByte()
        }
        val image = Image.makeRaster(
            ImageInfo(texture.width, texture.height, ColorType.RGBA_F16, ColorAlphaType.OPAQUE),
            bytes,
            texture.width * 8,
        )
        val input = image.makeShader(
            FilterTileMode.CLAMP,
            FilterTileMode.CLAMP,
            SamplingMode.LINEAR,
            null,
        )
        builder.child(name, input)
        inputs.put(name, SkiaInputTexture(image, input))?.close()
        dirty = true
    }

    override fun draw(scope: DrawScope, path: Path) {
        check(!closed)
        if (dirty) {
            // makeShader snapshots the builder's uniforms/children; it does not
            // recompile RuntimeEffect. Reuse the snapshot until inputs change.
            val next = builder.makeShader()
            paint.shader = next
            snapshot?.close()
            snapshot = next
            dirty = false
        }
        scope.drawIntoCanvas { canvas -> canvas.drawPath(path, paint) }
    }

    override fun close() {
        if (closed) return
        closed = true
        paint.shader = null
        snapshot?.close()
        snapshot = null
        builder.close()
        inputs.values.forEach { it.close() }
        inputs.clear()
        paint.asFrameworkPaint().close()
    }
}

private class SkiaInputTexture(val image: Image, val shader: Shader) {
    fun close() {
        shader.close()
        image.close()
    }
}

/** IEEE binary16, round-to-nearest-even, including the small-gradient range. */
private fun float16Bits(value: Float): Int {
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
