package dev.mttmcknn.seaglass.internal

import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Platform-rendered ground light, retaining the reference's three-layer recipe.
 *
 * The owner supplies composition-managed graphics layers and only selects this
 * backend where render effects are supported. Each complete colored/gradient
 * path is recorded before blur, so transmission opacity is blurred together
 * with contour coverage. Layer pixels remain on the rendering backend.
 *
 * [Path] coordinates are physical pixels with the body origin at (0, 0). Reuse
 * the same path while its contour is unchanged; a new path invalidates records.
 */
internal class RealtimeGround(
    private val castLayer: GraphicsLayer,
    private val contactLayer: GraphicsLayer,
    private val transmissionLayer: GraphicsLayer,
) {
    private var recordedKey: GroundRecordKey? = null
    private var castKey: ShadowKey? = null
    private var contactKey: ShadowKey? = null
    private var transmissionKey: TransmissionKey? = null
    // Count actual layer recordings independently of platform effect caching.
    internal var castRecordCount: Int = 0
        private set
    internal var contactRecordCount: Int = 0
        private set
    internal var transmissionRecordCount: Int = 0
        private set

    fun draw(
        scope: DrawScope,
        path: Path,
        widthDp: Double,
        heightDp: Double,
        density: Float,
        settings: RenderSettings,
        palette: RenderPalette,
    ) {
        if (!widthDp.isFinite() || !heightDp.isFinite() || widthDp <= 0.0 || heightDp <= 0.0 ||
            !density.isFinite() || density <= 0f
        ) return
        val bounded = settings.normalized()
        val key = GroundRecordKey(
            path, widthDp, heightDp, density, scope.layoutDirection,
            bounded.tumble, bounded.seed, bounded.thickness, bounded.bulge,
            bounded.frost, bounded.elevation, bounded.transmission, palette.body and 0xFFFFFF,
        )
        if (recordedKey != key) {
            record(scope, path, widthDp, heightDp, density, bounded, palette)
            recordedKey = key
        }
        val padding = (MATERIAL_PADDING_DP * density).toFloat()
        with(scope) {
            translate(left = -padding, top = -padding) {
                drawLayer(castLayer)
                drawLayer(contactLayer)
                if (bounded.transmission > 0.001) drawLayer(transmissionLayer)
            }
        }
    }

    private fun record(
        scope: DrawScope,
        path: Path,
        widthDp: Double,
        heightDp: Double,
        density: Float,
        settings: RenderSettings,
        palette: RenderPalette,
    ) {
        val padding = (MATERIAL_PADDING_DP * density).toFloat()
        val layerSize = IntSize(
            ceil((widthDp + MATERIAL_PADDING_DP * 2.0) * density).toInt(),
            ceil((heightDp + MATERIAL_PADDING_DP * 2.0) * density).toInt(),
        )
        val short = min(widthDp, heightDp)
        val transmission = settings.transmission
        val geometry = LayerGeometryKey(path, widthDp, heightDp, density, scope.layoutDirection)

        fun recordLayer(
            layer: GraphicsLayer,
            offsetDp: Double,
            sigmaDp: Double,
            paint: DrawScope.() -> Unit,
        ) {
            // Android RenderEffect and Compose's Skiko BlurEffect both convert
            // radius to Gaussian sigma using sigma = radius * 0.57735 + 0.5.
            // Invert that mapping to retain the CPU/reference blur softness.
            val radius = (((sigmaDp * density).toFloat() - 0.5f) / 0.57735f).coerceAtLeast(0f)
            layer.renderEffect = if (radius > 0f) BlurEffect(radius, radius, TileMode.Decal) else null
            layer.clip = false
            layer.record(scope, scope.layoutDirection, layerSize) {
                translate(left = padding, top = padding + (offsetDp * density).toFloat(), block = paint)
            }
        }

        val nextCastKey = ShadowKey(
            geometry,
            offsetDp = min(16.0, settings.thickness * 0.40 + settings.elevation * 0.72 + settings.bulge * short * 0.03),
            sigmaDp = min(6.0, 2.5 + settings.elevation * 0.18 + settings.frost * 0.7 + settings.bulge * short * 0.018),
            alpha = ((0.18 + settings.elevation * 0.001) * (1.0 - transmission * 0.82)).toFloat(),
        )
        if (nextCastKey != castKey) {
            recordLayer(castLayer, nextCastKey.offsetDp, nextCastKey.sigmaDp) {
                drawPath(path, Color(0xFF3F4342), alpha = nextCastKey.alpha)
            }
            castRecordCount++
            castKey = nextCastKey
        }
        val nextContactKey = ShadowKey(
            geometry,
            offsetDp = min(8.0, settings.thickness * 0.32 + 0.8),
            sigmaDp = 1.4 + settings.elevation * 0.065,
            alpha = (0.10 * (1.0 - transmission * 0.70)).toFloat(),
        )
        if (nextContactKey != contactKey) {
            recordLayer(contactLayer, nextContactKey.offsetDp, nextContactKey.sigmaDp) {
                drawPath(path, Color(0xFF3D413F), alpha = nextContactKey.alpha)
            }
            contactRecordCount++
            contactKey = nextContactKey
        }
        if (transmission > 0.001) {
            val travel = 4.0 + settings.elevation * 0.56 + short * 0.055
            val nextTransmissionKey = TransmissionKey(
                geometry,
                offsetDp = min(22.0, settings.thickness * 0.42 + travel),
                sigmaDp = min(8.0, 2.8 + short * 0.033 + settings.frost * 1.6),
                transmission = transmission,
                bodyColor = palette.body and 0xFFFFFF,
            )
            if (nextTransmissionKey == transmissionKey) return
            fun tint(channel: Int): Int = (255.0 - (255 - channel) * 1.35).roundToInt().coerceIn(0, 255)
            val tintColor = Color(
                (255 shl 24) or (tint((palette.body ushr 16) and 255) shl 16) or
                    (tint((palette.body ushr 8) and 255) shl 8) or tint(palette.body and 255),
            )
            val gradient = Brush.verticalGradient(
                0f to tintColor.copy(alpha = 0f),
                0.45f to tintColor.copy(alpha = (transmission * 0.06).toFloat()),
                0.75f to tintColor.copy(alpha = (transmission * 0.48).toFloat()),
                1f to tintColor.copy(alpha = (transmission * 0.94).toFloat()),
                startY = 0f,
                endY = (heightDp * density).toFloat(),
                tileMode = TileMode.Clamp,
            )
            recordLayer(
                transmissionLayer,
                offsetDp = nextTransmissionKey.offsetDp,
                sigmaDp = nextTransmissionKey.sigmaDp,
            ) {
                drawPath(path, gradient)
            }
            transmissionRecordCount++
            transmissionKey = nextTransmissionKey
        }
    }

    // Cache each layer by its actual paint inputs. A palette edit changes the
    // transmitted light, while the two neutral shadows remain recorded.
    private data class LayerGeometryKey(
        val path: Path,
        val widthDp: Double,
        val heightDp: Double,
        val density: Float,
        val layoutDirection: LayoutDirection,
    )

    private data class ShadowKey(
        val geometry: LayerGeometryKey,
        val offsetDp: Double,
        val sigmaDp: Double,
        val alpha: Float,
    )

    private data class TransmissionKey(
        val geometry: LayerGeometryKey,
        val offsetDp: Double,
        val sigmaDp: Double,
        val transmission: Double,
        val bodyColor: Int,
    )

    private data class GroundRecordKey(
        val path: Path,
        val widthDp: Double,
        val heightDp: Double,
        val density: Float,
        val layoutDirection: LayoutDirection,
        val tumble: Double,
        val seed: Int,
        val thickness: Double,
        val bulge: Double,
        val frost: Double,
        val elevation: Double,
        val transmission: Double,
        val bodyColor: Int,
    )
}
