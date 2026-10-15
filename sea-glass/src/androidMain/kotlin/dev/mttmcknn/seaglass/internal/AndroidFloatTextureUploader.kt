package dev.mttmcknn.seaglass.internal

import android.annotation.TargetApi
import android.graphics.Bitmap
import android.util.Half
import java.nio.ShortBuffer
import kotlin.math.min

/**
 * One shader's F16 packing scratch, confined to its drawing thread. A synchronous
 * copy gives every upload its own bitmap; only the staging array is reused.
 */
@TargetApi(26)
internal class AndroidFloatTextureUploader(private val maxScratchBytes: Int = 2 * 1024 * 1024) {
    init { require(maxScratchBytes >= 0) }

    private class Scratch(shortCount: Int) {
        val channels = ShortArray(shortCount)
        // A wrapped ShortArray uses the device's native byte order, matching the
        // previous native-order ByteBuffer.putShort packing exactly.
        val buffer: ShortBuffer = ShortBuffer.wrap(channels)
    }

    private var scratch: Scratch? = null
    internal val retainedScratchByteCount: Int get() = (scratch?.channels?.size ?: 0) * 2
    internal var scratchAllocationCount: Int = 0
        private set

    fun upload(texture: NativeFloatTexture): Bitmap {
        val bitmap = Bitmap.createBitmap(texture.width, texture.height, Bitmap.Config.RGBA_F16)
        bitmap.setPremultiplied(false)
        // This copies bytes synchronously without color conversion. The returned
        // bitmap never aliases the staging array, which may serve the next input.
        bitmap.copyPixelsFromBuffer(pack(texture, bitmap.rowBytes, bitmap.byteCount))
        return bitmap
    }

    /** Returned storage is valid only until the next pack or clear on this instance. */
    internal fun pack(texture: NativeFloatTexture, rowBytes: Int, byteCount: Int): ShortBuffer {
        val channelsPerRow = texture.width * 4
        require(rowBytes >= channelsPerRow.toLong() * 2L && rowBytes % 2 == 0)
        require(byteCount.toLong() >= (texture.height - 1L) * rowBytes + channelsPerRow * 2L && byteCount % 2 == 0)
        require(byteCount.toLong() <= texture.height.toLong() * rowBytes)
        val shortCount = byteCount / 2
        val storage = scratch?.takeIf { it.channels.size >= shortCount } ?: Scratch(shortCount).also {
            scratchAllocationCount++
            // A one-off oversized input must not enlarge or evict the bounded
            // buffer. Its temporary storage can be collected after this copy.
            if (byteCount <= maxScratchBytes) scratch = it
        }
        val rowShorts = rowBytes / 2
        for (row in 0 until texture.height) {
            val source = row * channelsPerRow
            val destination = row * rowShorts
            for (channel in 0 until channelsPerRow) {
                storage.channels[destination + channel] = Half.toHalf(texture.rgba[source + channel])
            }
            // A fresh direct buffer was zero-initialized, including allocator
            // padding. Preserve those bytes when reusing a larger staging array.
            storage.channels.fill(0, destination + channelsPerRow, min(destination + rowShorts, shortCount))
        }
        return storage.buffer.apply {
            clear()
            limit(shortCount)
        }
    }

    fun clear() {
        scratch = null
    }
}
