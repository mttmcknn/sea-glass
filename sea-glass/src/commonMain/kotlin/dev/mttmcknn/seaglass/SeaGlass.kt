package dev.mttmcknn.seaglass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.mttmcknn.seaglass.internal.MATERIAL_PADDING_DP
import dev.mttmcknn.seaglass.internal.SeaGlassRenderer
import dev.mttmcknn.seaglass.internal.LiveMaterialRenderer
import dev.mttmcknn.seaglass.internal.RealtimeGround
import dev.mttmcknn.seaglass.internal.buildContour
import dev.mttmcknn.seaglass.internal.composeMaskRasterizer
import dev.mttmcknn.seaglass.internal.contourSegments
import dev.mttmcknn.seaglass.internal.contourToPath
import dev.mttmcknn.seaglass.internal.toImageBitmap
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

/**
 * Draw a procedurally rendered sea-glass material behind any Compose content.
 *
 * The drawing extends up to 48.dp outside the layout bounds for transmitted
 * light and shadow. A clipping ancestor can crop it. Android 13+ and Skia draw
 * the complete material during the first draw, with no asynchronous placeholder.
 * Geometry is cached per surface; lighting and color changes update the shader.
 * Older Android versions use an asynchronous CPU compatibility path that keeps
 * one active render and the newest pending recipe, coalescing intermediate edits.
 * This modifier adds no layout padding, input handling, or semantics.
 * Reuse immutable styles between components, but call this modifier separately
 * for each layout: its remembered renderer and ground layers belong to one piece.
 */
@Composable
public fun Modifier.seaGlass(
    style: SeaGlassStyle = SeaGlassTheme.style,
    palette: SeaGlassPalette = SeaGlassTheme.palette,
    shape: SeaGlassShape = SeaGlassTheme.shape,
): Modifier {
    val live = remember { LiveMaterialRenderer.create() }
    if (live != null) {
        val cast = rememberGraphicsLayer()
        val contact = rememberGraphicsLayer()
        val transmission = rememberGraphicsLayer()
        val ground = remember(cast, contact, transmission) { RealtimeGround(cast, contact, transmission) }
        val settings = remember(style) { style.renderSettings() }
        val colors = remember(palette) { palette.renderPalette() }
        // DrawScope already has the measured size on its first draw. Rendering
        // the material here avoids the post-layout state/bitmap delivery cycle.
        return this.drawBehind { live.draw(this, ground, settings, colors, shape.renderShape()) }
    }
    return cpuSeaGlass(style, palette, shape)
}

/** Compatibility path for Android versions without runtime shader support. */
@Composable
private fun Modifier.cpuSeaGlass(style: SeaGlassStyle, palette: SeaGlassPalette, shape: SeaGlassShape): Modifier {
    val density = LocalDensity.current.density
    var dimensions by remember { mutableStateOf(IntSize.Zero) }
    val renderer = remember { SeaGlassRenderer(composeMaskRasterizer, fastGround = true) }
    val settings = remember(style) { style.renderSettings() }
    val colors = remember(palette) { palette.renderPalette() }
    val request = remember(dimensions, density, settings, colors, shape) {
        RenderRequest(dimensions, density, style, palette, shape)
    }
    val latestRequest by rememberUpdatedState(request)
    val image by produceState<ImageBitmap?>(initialValue = null, renderer) {
        // One sequential worker owns the renderer caches. Keep at most the active
        // request and the latest pending request: slider updates cannot cancel
        // every render before it reaches the screen, and cannot build a backlog.
        // Leaving composition still cancels this producer and its active worker.
        snapshotFlow { latestRequest }.conflate().collect { activeRequest ->
            val size = activeRequest.size
            val requestDensity = activeRequest.density
            if (size.width <= 0 || size.height <= 0 || requestDensity <= 0f) return@collect
            val widthDp = size.width / requestDensity.toDouble()
            val heightDp = size.height / requestDensity.toDouble()
            if (widthDp < 1.0 || heightDp < 1.0) return@collect
            // Preserve the same full-quality texture scale and working-memory cap.
            val area = (widthDp + MATERIAL_PADDING_DP * 2) * (heightDp + MATERIAL_PADDING_DP * 2)
            val scale = min(min(requestDensity.toDouble(), 2.0), sqrt(1_048_576.0 / area))
            value = withContext(Dispatchers.Default) {
                val context = currentCoroutineContext()
                renderer.render(
                    widthDp, heightDp, scale, activeRequest.shape.renderShape(),
                    activeRequest.style.renderSettings(), activeRequest.palette.renderPalette(),
                ) {
                    context.ensureActive()
                }.toImageBitmap()
            }
        }
    }
    val outline = remember(style.tumble, style.seed, shape) { TumbledOutline(shape, style) }
    return this.onSizeChanged { dimensions = it }.drawBehind {
        val ready = image
        if (ready != null) {
            val padding = (MATERIAL_PADDING_DP * density).roundToInt()
            drawImage(
                image = ready,
                dstOffset = IntOffset(-padding, -padding),
                dstSize = IntSize(size.width.roundToInt() + padding * 2, size.height.roundToInt() + padding * 2),
                filterQuality = FilterQuality.Low,
            )
        } else {
            // First paint stays readable while the cached material is prepared.
            val path = (outline.createOutline(size, layoutDirection, this) as Outline.Generic).path
            drawPath(path, palette.body.copy(alpha = 1f))
        }
    }
}

/** Draw a complete reusable [style], with optional explicit pigment/shape overrides. */
@Composable
public fun Modifier.seaGlass(
    style: SeaGlassComponentStyle,
    palette: SeaGlassPalette = style.palette,
    shape: SeaGlassShape = style.shape,
): Modifier = seaGlass(style.material, palette, shape)

private data class RenderRequest(
    val size: IntSize,
    val density: Float,
    val style: SeaGlassStyle,
    val palette: SeaGlassPalette,
    val shape: SeaGlassShape,
)

private data class TumbledOutline(val shape: SeaGlassShape, val style: SeaGlassStyle) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val points = buildContour(
            size.width / density.density.toDouble(), size.height / density.density.toDouble(),
            shape.renderShape(), style.renderSettings().tumble, style.seed,
        )
        return Outline.Generic(contourToPath(contourSegments(points), density.density.toDouble()))
    }
}

/** A content container with sea-glass material and palette foreground color. */
@Composable
public fun SeaGlassSurface(
    modifier: Modifier = Modifier,
    style: SeaGlassStyle = SeaGlassTheme.style,
    palette: SeaGlassPalette = SeaGlassTheme.palette,
    shape: SeaGlassShape = SeaGlassTheme.shape,
    content: @Composable BoxScope.() -> Unit,
) {
    val outline = remember(style.tumble, style.seed, shape) { TumbledOutline(shape, style) }
    CompositionLocalProvider(LocalContentColor provides palette.ink) {
        // The drawing modifier is outside the content clip, keeping light visible.
        Box(modifier.seaGlass(style, palette, shape).clip(outline), contentAlignment = Alignment.Center, content = content)
    }
}

/** A content container using a complete reusable component [style]. */
@Composable
public fun SeaGlassSurface(
    modifier: Modifier = Modifier,
    style: SeaGlassComponentStyle,
    palette: SeaGlassPalette = style.palette,
    shape: SeaGlassShape = style.shape,
    content: @Composable BoxScope.() -> Unit,
) {
    SeaGlassSurface(modifier, style.material, palette, shape, content)
}

/** Accessible button with a stable material and a small physical press offset. */
@Composable
public fun SeaGlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: SeaGlassStyle = SeaGlassTheme.style,
    palette: SeaGlassPalette = SeaGlassTheme.palette,
    shape: SeaGlassShape = SeaGlassTheme.shape,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val density = LocalDensity.current
    val outline = remember(style.tumble, style.seed, shape) { TumbledOutline(shape, style) }
    CompositionLocalProvider(LocalContentColor provides palette.ink) {
        Box(
            modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .graphicsLayer {
                    translationY = if (pressed) with(density) { 2.dp.toPx() } else 0f
                    alpha = if (enabled) 1f else .45f
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
                .seaGlass(style, palette, shape)
                .clip(outline)
                .then(if (focused) Modifier.border(2.dp, palette.ink.copy(alpha = .65f), outline) else Modifier)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier.padding(contentPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                content = content,
            )
        }
    }
}

/** A button using a complete reusable component [style]. Explicit colors/shapes win. */
@Composable
public fun SeaGlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: SeaGlassComponentStyle,
    palette: SeaGlassPalette = style.palette,
    shape: SeaGlassShape = style.shape,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
    content: @Composable RowScope.() -> Unit,
) {
    SeaGlassButton(onClick, modifier, style.material, palette, shape, enabled, contentPadding, content)
}
