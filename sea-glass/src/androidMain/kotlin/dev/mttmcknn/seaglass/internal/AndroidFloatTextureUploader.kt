package dev.mttmcknn.seaglass.internal

import android.annotation.TargetApi
import android.graphics.Bitmap
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Upload immutable F16 bytes directly when the bitmap has tightly packed rows.
 * Only padded rows need shader-owned, bounded scratch. The synchronous bitmap
 * copy gives every upload independent storage, including earlier display lists.
 */
@TargetApi(26)
internal class AndroidFloatTextureUploader(private val maxScratchBytes: Int = 2 * 1024 * 1024) {
    init { require(maxScratchBytes >= 0) }

    private class Scratch(byteCount: Int) {
        val bytes = ByteArray(byteCount)
        val buffer: ByteBuffer = ByteBuffer.wrap(bytes)
    }

    private var scratch: Scratch? = null
    internal val retainedScratchByteCount: Int get() = scratch?.bytes?.size ?: 0
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
    internal fun pack(texture: NativeFloatTexture, rowBytes: Int, byteCount: Int): ByteBuffer {
        val packedRowBytes = texture.width * 8
        require(rowBytes >= packedRowBytes.toLong() && rowBytes % 2 == 0)
        require(byteCount.toLong() >= (texture.height - 1L) * rowBytes + packedRowBytes && byteCount % 2 == 0)
        require(byteCount.toLong() <= texture.height.toLong() * rowBytes)
        // Supported Android ABIs are little-endian, matching the canonical bytes.
        // This buffer view is fresh because copyPixelsFromBuffer advances it.
        if (rowBytes == packedRowBytes && byteCount == texture.rgbaF16.size) {
            return ByteBuffer.wrap(texture.rgbaF16)
        }
        val storage = scratch?.takeIf { it.bytes.size >= byteCount } ?: Scratch(byteCount).also {
            scratchAllocationCount++
            // A one-off oversized input must not enlarge or evict the bounded
            // buffer. Its temporary storage can be collected after this copy.
            if (byteCount <= maxScratchBytes) scratch = it
        }
        for (row in 0 until texture.height) {
            val source = row * packedRowBytes
            val destination = row * rowBytes
            texture.rgbaF16.copyInto(storage.bytes, destination, source, source + packedRowBytes)
            // A fresh direct buffer was zero-initialized, including allocator
            // padding. Preserve those bytes when reusing a larger staging array.
            storage.bytes.fill(0, destination + packedRowBytes, min(destination + rowBytes, byteCount))
        }
        return storage.buffer.apply {
            clear()
            limit(byteCount)
        }
    }

    fun clear() {
        scratch = null
    }
}
