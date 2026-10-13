package dev.mttmcknn.seaglass.internal

import androidx.compose.runtime.RememberObserver
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.min
import kotlin.math.sqrt

private val liveMaterialProgram by lazy { NativeMaterialProgram(SeaGlassBodyShader) }

/** One synchronous draw-time material. No placeholder or completed-image swap. */
internal class LiveMaterialRenderer private constructor(private val shader: NativeMaterialShader) : RememberObserver {
    private var geometryKey: GeometryKey? = null
    private var materialKey: MaterialKey? = null
    private var path: Path? = null

    fun draw(scope: DrawScope, ground: RealtimeGround, settings: RenderSettings, palette: RenderPalette, shape: RenderShape) {
        val density = scope.density
        val widthDp = scope.size.width / density.toDouble()
        val heightDp = scope.size.height / density.toDouble()
        if (widthDp < 1.0 || heightDp < 1.0) return
        val bounded = settings.normalized()
        val key = GeometryKey(widthDp, heightDp, density, shape, bounded.tumble, bounded.seed)
        if (key != geometryKey) {
            // Only the smooth height field is sampled; the silhouette and grain
            // retain device-resolution rendering. Bound field allocation for
            // large containers without accumulating old sizes or recipes.
            val fieldScale = min(1.0, sqrt(262_144.0 / (widthDp * heightDp)))
            val geometry = makeLiveMaterialGeometry(widthDp, heightDp, shape, bounded.tumble, bounded.seed, composeMaskRasterizer, fieldScale)
            shader.setInputTexture("geometryA", geometry.geometryA)
            shader.setInputTexture("geometryB", geometry.geometryB)
            shader.setFloatUniform("fieldScale", floatArrayOf(geometry.fieldScale))
            shader.setFloatUniform("gradientScales", geometry.gradientScales)
            shader.setFloatUniform("materialSize", floatArrayOf(widthDp.toFloat(), heightDp.toFloat()))
            shader.setFloatUniform("density", floatArrayOf(density))
            path = contourToPath(geometry.segments, density.toDouble())
            geometryKey = key
        }
        val nextMaterialKey = MaterialKey(bounded, palette.body, palette.edge)
        if (nextMaterialKey != materialKey) {
            shader.setMaterialLight(bounded.light)
            shader.setFloatUniform("recipeA", floatArrayOf(bounded.frost.toFloat(), bounded.grain.toFloat(), bounded.thickness.toFloat(), bounded.bulge.toFloat()))
            shader.setFloatUniform("recipeB", floatArrayOf(bounded.light.toFloat(), bounded.transmission.toFloat(), SeededRandom(bounded.seed).nextDouble().toFloat(), 0f))
            shader.setFloatUniform("bodyColor", rgbComponents(palette.body).map { it / 255f }.toFloatArray())
            shader.setFloatUniform("edgeColor", rgbComponents(palette.edge).map { it / 255f }.toFloatArray())
            materialKey = nextMaterialKey
        }
        val contour = checkNotNull(path)
        ground.draw(scope, contour, widthDp, heightDp, density, bounded, palette)
        shader.draw(scope, contour)
    }

    fun close() = shader.close()

    override fun onRemembered() = Unit

    // Disposal also covers a composition that allocated the shader but was
    // abandoned before its effects committed. Shader.close is idempotent.
    override fun onForgotten() = close()

    override fun onAbandoned() = close()

    private data class GeometryKey(val width: Double, val height: Double, val density: Float, val shape: RenderShape, val tumble: Double, val seed: Int)
    private data class MaterialKey(val settings: RenderSettings, val body: Int, val edge: Int)

    companion object {
        fun create(): LiveMaterialRenderer? = liveMaterialProgram.createInstance()?.let(::LiveMaterialRenderer)
    }
}

/** Light-only transcendental work runs once per recipe change, not per fragment. */
internal fun NativeMaterialShader.setMaterialLight(angleDegrees: Double) {
    val theta = angleDegrees * PI / 180.0
    val lx = sin(theta)
    val ly = -cos(theta)
    val lightZ = 0.52
    val lightSide = sqrt(1.0 - lightZ * lightZ)
    val halfX = lx * lightSide
    val halfY = ly * lightSide
    val halfZ = lightZ + 1.0
    val halfLength = sqrt(halfX * halfX + halfY * halfY + halfZ * halfZ)
    setFloatUniform("lightDirection", floatArrayOf(lx.toFloat(), ly.toFloat()))
    setFloatUniform("halfway", floatArrayOf((halfX / halfLength).toFloat(), (halfY / halfLength).toFloat(), (halfZ / halfLength).toFloat()))
}
