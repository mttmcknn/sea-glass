package dev.mttmcknn.seaglass.internal

import android.annotation.TargetApi
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Half
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal actual class NativeMaterialProgram actual constructor(private val source: String) {
    actual fun createInstance(): NativeMaterialShader? =
        if (Build.VERSION.SDK_INT >= 33) AndroidMaterialShader(source) else null

    // RuntimeShader owns its native program; Android exposes no close operation.
    actual fun close() = Unit
}

@TargetApi(33)
private class AndroidMaterialShader(source: String) : NativeMaterialShader {
    private val shader = RuntimeShader(source)
    private val paint = Paint().apply {
        isAntiAlias = true
        this.shader = this@AndroidMaterialShader.shader
    }
    private val inputs = mutableMapOf<String, BitmapShader>()
    private var closed = false

    override fun setFloatUniform(name: String, values: FloatArray) {
        check(!closed)
        shader.setFloatUniform(name, values)
    }

    override fun setIntUniform(name: String, value: Int) {
        check(!closed)
        shader.setIntUniform(name, value)
    }

    override fun setInputTexture(name: String, texture: NativeFloatTexture) {
        check(!closed)
        val bitmap = Bitmap.createBitmap(texture.width, texture.height, Bitmap.Config.RGBA_F16)
        bitmap.setPremultiplied(false)
        // copyPixelsFromBuffer preserves the numeric F16 channel values. The
        // input-buffer API skips both color conversion and alpha premultiplication.
        val buffer = ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.nativeOrder())
        val channelsPerRow = texture.width * 4
        for (row in 0 until texture.height) {
            // Respect the bitmap allocator's native stride, including padding.
            buffer.position(row * bitmap.rowBytes)
            val start = row * channelsPerRow
            for (channel in 0 until channelsPerRow) {
                buffer.putShort(Half.toHalf(texture.rgba[start + channel]))
            }
        }
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
        val input = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        }
        shader.setInputBuffer(name, input)
        inputs[name] = input
        // Do not recycle a replaced bitmap: a previously recorded display list
        // may still reference its shader. Native reference/GC ownership is safe.
    }

    override fun draw(scope: DrawScope, path: Path) {
        check(!closed)
        scope.drawIntoCanvas { canvas -> canvas.drawPath(path, paint) }
    }

    override fun close() {
        if (closed) return
        closed = true
        paint.shader = null
        inputs.clear()
        // Dropping this instance releases RuntimeShader and its remaining child
        // textures through the framework's native allocation ownership.
    }
}
