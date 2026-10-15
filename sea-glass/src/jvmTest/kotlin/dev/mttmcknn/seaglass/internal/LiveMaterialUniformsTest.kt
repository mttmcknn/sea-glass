package dev.mttmcknn.seaglass.internal

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveMaterialUniformsTest {
    private val settings = RenderSettings()
    private val palette = RenderPalette(0xFF578C83.toInt(), 0xFF264B40.toInt(), 0xFF102020.toInt())

    @Test
    fun groundAndContourOnlyEditsDoNotDirtyBodyUniforms() {
        val shader = RecordingShader()
        val uniforms = LiveMaterialUniforms(shader)
        uniforms.update(settings, palette)
        assertEquals(setOf("lightDirection", "halfway", "recipeA", "recipeB", "bodyColor", "edgeColor"), shader.writes.keys)
        shader.writes.clear()
        uniforms.update(settings.copy(elevation = 17.0, tumble = 0.06), palette.copy(
            body = palette.body and 0xFFFFFF, edge = palette.edge and 0xFFFFFF, ink = 0,
        ))
        assertTrue(shader.writes.isEmpty(), "Lift, contour and ignored pigment alpha must not recreate a body shader snapshot")
    }

    @Test
    fun eachEditOnlyUpdatesItsActualUniformsWithExactValues() {
        val shader = RecordingShader()
        val uniforms = LiveMaterialUniforms(shader)
        uniforms.update(settings, palette)
        shader.writes.clear()
        val frosted = settings.copy(frost = 0.9)
        uniforms.update(frosted, palette)
        assertEquals(setOf("recipeA"), shader.writes.keys)
        assertContentEquals(floatArrayOf(.9f, .48f, 6f, .65f), shader.writes.getValue("recipeA"))
        shader.writes.clear()
        val relit = frosted.copy(light = 75.0)
        uniforms.update(relit, palette)
        assertEquals(setOf("lightDirection", "halfway", "recipeB"), shader.writes.keys)
        shader.writes.clear()
        uniforms.update(relit.copy(seed = -17), palette)
        assertEquals(setOf("recipeB"), shader.writes.keys)
        assertContentEquals(floatArrayOf(75f, .58f, SeededRandom(-17).nextDouble().toFloat(), 0f), shader.writes.getValue("recipeB"))
        shader.writes.clear()
        uniforms.update(relit.copy(seed = -17), palette.copy(body = 0xABCDEF))
        assertEquals(setOf("bodyColor"), shader.writes.keys)
        assertContentEquals(floatArrayOf(0xAB / 255f, 0xCD / 255f, 0xEF / 255f), shader.writes.getValue("bodyColor"))
    }

    private class RecordingShader : NativeMaterialShader {
        val writes = mutableMapOf<String, FloatArray>()
        override fun setFloatUniform(name: String, values: FloatArray) { writes[name] = values.copyOf() }
        override fun setIntUniform(name: String, value: Int) = error("Unexpected integer uniform")
        override fun setInputTexture(name: String, texture: NativeFloatTexture) = error("Unexpected texture upload")
        override fun draw(scope: DrawScope, path: Path) = error("Unexpected draw")
        override fun close() = Unit
    }
}
