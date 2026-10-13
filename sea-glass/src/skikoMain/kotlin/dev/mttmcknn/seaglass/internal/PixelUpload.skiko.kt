package dev.mttmcknn.seaglass.internal

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

internal actual fun PixelBuffer.toImageBitmap(): ImageBitmap {
    val bytes = ByteArray(pixels.size * 4)
    for (i in pixels.indices) {
        val argb = pixels[i]
        bytes[i * 4] = (argb ushr 16).toByte()
        bytes[i * 4 + 1] = (argb ushr 8).toByte()
        bytes[i * 4 + 2] = argb.toByte()
        bytes[i * 4 + 3] = (argb ushr 24).toByte()
    }
    return Image.makeRaster(
        ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL),
        bytes,
        width * 4,
    ).toComposeImageBitmap()
}
