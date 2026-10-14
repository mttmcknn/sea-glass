package dev.mttmcknn.seaglass.internal

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path

/** Platform adapters only upload pixels; every material calculation is shared. */
internal expect fun PixelBuffer.toImageBitmap(): ImageBitmap

/** One worker-owned upload, reused when the renderer returns its cached pixels. */
internal class MaterialImageCache(
    private val upload: (PixelBuffer) -> ImageBitmap = { it.toImageBitmap() },
) {
    private var pixels: PixelBuffer? = null
    private var image: ImageBitmap? = null

    fun imageFor(next: PixelBuffer): ImageBitmap {
        // Renderer outputs are immutable. Identity avoids scanning the entire
        // image for edits that do not affect material pixels (for example ink).
        if (next === pixels) return checkNotNull(image)
        val uploaded = upload(next)
        pixels = next
        image = uploaded
        return uploaded
    }
}

internal fun contourToPath(segments: List<ContourSegment>, scale: Double = 1.0): Path = Path().apply {
    if (segments.isEmpty()) return@apply
    moveTo((segments[0].start.x * scale).toFloat(), (segments[0].start.y * scale).toFloat())
    for (segment in segments) {
        cubicTo(
            (segment.control1.x * scale).toFloat(), (segment.control1.y * scale).toFloat(),
            (segment.control2.x * scale).toFloat(), (segment.control2.y * scale).toFloat(),
            (segment.end.x * scale).toFloat(), (segment.end.y * scale).toFloat(),
        )
    }
    close()
}

internal val composeMaskRasterizer: MaskRasterizer = { segments, width, height, scale, offsetX, offsetY ->
    val bitmap = ImageBitmap(width, height)
    val canvas = Canvas(bitmap)
    canvas.translate(offsetX.toFloat(), offsetY.toFloat())
    canvas.drawPath(contourToPath(segments, scale), Paint().apply {
        color = Color.White
        isAntiAlias = true
    })
    val pixels = IntArray(width * height)
    bitmap.readPixels(pixels)
    ByteArray(pixels.size) { (pixels[it] ushr 24).toByte() }
}
