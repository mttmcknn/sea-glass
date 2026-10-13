package dev.mttmcknn.seaglass.internal

import kotlin.math.min

/**
 * A renderer instance belongs to one material and must be confined to one worker.
 * Each stage retains at most one entry: changing light reuses frost, geometry,
 * absorption and ground layers; changing elevation reuses the whole body field.
 */
internal class SeaGlassRenderer(private val rasterizeMask: MaskRasterizer) {
    private var textureKey: TextureKey? = null
    private var texture: TextureField? = null
    private var geometryKey: GeometryKey? = null
    private var geometry: GeometryField? = null
    private var opticalKey: OpticalKey? = null
    private var optics: OpticalField? = null
    private var groundKey: GroundKey? = null
    private var ground: PixelBuffer? = null
    private var finalKey: RenderKey? = null
    private var finalImage: PixelBuffer? = null

    fun render(
        widthDp: Double,
        heightDp: Double,
        scale: Double,
        shape: RenderShape,
        settings: RenderSettings,
        palette: RenderPalette,
        cancellationProbe: () -> Unit = {},
    ): PixelBuffer {
        require(widthDp.isFinite() && widthDp >= 1.0 && heightDp.isFinite() && heightDp >= 1.0)
        require(scale.isFinite() && scale > 0.0)
        cancellationProbe()
        val bounded = settings.normalized()
        // The source demo also caps the material texture at two samples per CSS px.
        val sampleScale = min(scale, 2.0)
        val nextTextureKey = TextureKey(widthDp, heightDp, sampleScale, bounded.seed)
        val nextGeometryKey = GeometryKey(nextTextureKey, shape, bounded.tumble)
        val nextOpticalKey = OpticalKey(nextGeometryKey, bounded.thickness, bounded.bulge, bounded.frost, bounded.grain, bounded.transmission, palette)
        val nextGroundKey = GroundKey(nextGeometryKey, bounded.thickness, bounded.bulge, bounded.frost, bounded.elevation, bounded.transmission, palette.body)
        val nextFinalKey = RenderKey(nextOpticalKey, nextGroundKey, bounded.light)
        if (nextFinalKey == finalKey) return checkNotNull(finalImage)
        if (nextTextureKey != textureKey) {
            texture = makeTexture(widthDp, heightDp, sampleScale, bounded.seed, cancellationProbe)
            textureKey = nextTextureKey
        }
        if (nextGeometryKey != geometryKey) {
            geometry = makeGeometryField(widthDp, heightDp, shape, bounded.tumble, bounded.seed, checkNotNull(texture), rasterizeMask, cancellationProbe)
            geometryKey = nextGeometryKey
        }
        val field = checkNotNull(geometry)
        if (nextOpticalKey != opticalKey) {
            optics = prepareOptics(field, bounded, palette, cancellationProbe)
            opticalKey = nextOpticalKey
        }
        if (nextGroundKey != groundKey) {
            ground = renderGroundLayers(widthDp, heightDp, sampleScale, bounded, palette, field.alpha, field.texture.width, field.texture.height, cancellationProbe)
            groundKey = nextGroundKey
        }
        val body = shadeBody(field, bounded, palette, checkNotNull(optics), cancellationProbe)
        val result = compositeBody(checkNotNull(ground), body, sampleScale, widthDp, heightDp, cancellationProbe)
        finalImage = result
        finalKey = nextFinalKey
        return result
    }

    fun clear() {
        textureKey = null
        texture = null
        geometryKey = null
        geometry = null
        opticalKey = null
        optics = null
        groundKey = null
        ground = null
        finalKey = null
        finalImage = null
    }

    private data class TextureKey(val width: Double, val height: Double, val scale: Double, val seed: Int)
    private data class GeometryKey(val texture: TextureKey, val shape: RenderShape, val tumble: Double)
    private data class OpticalKey(
        val geometry: GeometryKey,
        val thickness: Double,
        val bulge: Double,
        val frost: Double,
        val grain: Double,
        val transmission: Double,
        val palette: RenderPalette,
    )
    private data class GroundKey(
        val geometry: GeometryKey,
        val thickness: Double,
        val bulge: Double,
        val frost: Double,
        val elevation: Double,
        val transmission: Double,
        val body: Int,
    )
    private data class RenderKey(val optics: OpticalKey, val ground: GroundKey, val light: Double)
}
