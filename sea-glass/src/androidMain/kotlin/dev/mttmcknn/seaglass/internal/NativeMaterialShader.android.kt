package dev.mttmcknn.seaglass.internal

import android.annotation.TargetApi
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas

internal actual class NativeMaterialProgram actual constructor(private val source: String) {
    actual fun createInstance(): NativeMaterialShader? =
        if (Build.VERSION.SDK_INT >= 33) AndroidMaterialShader(source) else null

    // RuntimeShader owns its native program; Android exposes no close operation.
    actual fun close() = Unit
}

@TargetApi(33)
private class AndroidMaterialShader(source: String) : NativeMaterialShader {
    private val shader = traceMaterial("SeaGlass.ProgramCreate") { RuntimeShader(source) }
    private val paint = Paint().apply {
        isAntiAlias = true
        this.shader = this@AndroidMaterialShader.shader
    }
    private val inputs = mutableMapOf<String, BitmapShader>()
    private val uploader = AndroidFloatTextureUploader()
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
        val bitmap = uploader.upload(texture)
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
        uploader.clear()
        // Dropping this instance releases RuntimeShader and its remaining child
        // textures through the framework's native allocation ownership.
    }
}
