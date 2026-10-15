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

/** Complete first paint, then coherent geometry updates prepared off the drawing thread. */
internal class LiveMaterialRenderer private constructor(
    private val shader: NativeMaterialShader,
    private val updates: LiveMaterialGeometryUpdates,
) : RememberObserver {
    private var geometryKey: LiveMaterialGeometryKey? = null
    private var pathDensity = 0f
    private var path: Path? = null
    private var displayedSegments: List<ContourSegment>? = null
    private var appliedRevision = 0L
    private var tracedRevision = 0L
    private val uniforms = LiveMaterialUniforms(shader)
    // Only synchronous first paint/resize uses this cache. The worker owns its own.
    private val cloudCache = LiveMaterialCloudCache()

    /** The composable's LaunchedEffect owns this consumer and its cancellation. */
    suspend fun prepareUpdates() = updates.run()

    fun draw(scope: DrawScope, ground: RealtimeGround, settings: RenderSettings, palette: RenderPalette, shape: RenderShape): Path? {
        val density = scope.density
        val widthDp = scope.size.width / density.toDouble()
        val heightDp = scope.size.height / density.toDouble()
        if (widthDp < 1.0 || heightDp < 1.0) return null
        val bounded = settings.normalized()
        val fieldScale = min(1.0, sqrt(262_144.0 / (widthDp * heightDp)))
        val key = LiveMaterialGeometryKey(widthDp, heightDp, shape, bounded.tumble, bounded.seed, fieldScale)
        // The only snapshot read lives in drawing; worker delivery redraws this
        // surface without recomposing or measuring it. Requests use plain fields.
        val ready = updates.completed.value
        val displayed = geometryKey
        if (displayed == null || displayed.widthDp != widthDp || displayed.heightDp != heightDp) {
            val geometry = sharedLiveMaterialGeometryCache.geometryFor(key) {
                traceMaterial("SeaGlass.Geometry") {
                    makeLiveMaterialGeometry(widthDp, heightDp, shape, bounded.tumble, bounded.seed, composeMaskRasterizer, fieldScale, cloudCache)
                }
            }
            appliedRevision = updates.invalidate(key)
            applyGeometry(key, geometry, density)
        } else if (key != displayed) {
            val cached = sharedLiveMaterialGeometryCache.getIfPresent(key)
            if (cached != null) {
                appliedRevision = updates.invalidate(key)
                applyGeometry(key, cached, density)
            } else {
                if (ready != null && ready.requestId > appliedRevision &&
                    ready.key.widthDp == widthDp && ready.key.heightDp == heightDp
                ) {
                    applyGeometry(ready.key, ready.geometry, density)
                    appliedRevision = ready.requestId
                }
                if (geometryKey != key) updates.request(key)
            }
        } else {
            // A -> B(active) -> A must keep A, even after B finishes.
            appliedRevision = maxOf(appliedRevision, updates.invalidate(key))
        }
        if (pathDensity != density) {
            shader.setFloatUniform("density", floatArrayOf(density))
            path = contourToPath(checkNotNull(displayedSegments), density.toDouble())
            pathDensity = density
        }
        val applied = checkNotNull(geometryKey)
        val effective = if (applied.seed == bounded.seed && applied.tumble == bounded.tumble) bounded
            else bounded.copy(seed = applied.seed, tumble = applied.tumble)
        uniforms.update(effective, palette)
        val contour = checkNotNull(path)
        traceMaterial("SeaGlass.Ground") {
            ground.draw(scope, contour, widthDp, heightDp, density, effective, palette)
        }
        traceMaterial("SeaGlass.Draw") {
            shader.draw(scope, contour)
        }
        if (tracedRevision != appliedRevision) {
            traceMaterialRevision("Applied", updates.traceId, appliedRevision)
            tracedRevision = appliedRevision
        }
        return contour
    }

    private fun applyGeometry(key: LiveMaterialGeometryKey, geometry: LiveMaterialGeometry, density: Float) {
        traceMaterial("SeaGlass.TextureUpload") {
            shader.setInputTexture("geometryA", geometry.geometryA)
            shader.setInputTexture("geometryB", geometry.geometryB)
        }
        shader.setFloatUniform("fieldScale", floatArrayOf(geometry.fieldScale))
        shader.setFloatUniform("gradientScales", geometry.gradientScales)
        shader.setFloatUniform("materialSize", floatArrayOf(key.widthDp.toFloat(), key.heightDp.toFloat()))
        shader.setFloatUniform("density", floatArrayOf(density))
        path = contourToPath(geometry.segments, density.toDouble())
        pathDensity = density
        geometryKey = key
        displayedSegments = geometry.segments
    }

    fun close() {
        updates.close()
        cloudCache.clear()
        shader.close()
    }

    override fun onRemembered() = Unit

    // Disposal also covers a composition that allocated the shader but was
    // abandoned before its effects committed. Shader.close is idempotent.
    override fun onForgotten() = close()

    override fun onAbandoned() = close()

    companion object {
        fun create(updates: LiveMaterialGeometryUpdates = LiveMaterialGeometryUpdates()): LiveMaterialRenderer? =
            liveMaterialProgram.createInstance()?.let { LiveMaterialRenderer(it, updates) }
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
