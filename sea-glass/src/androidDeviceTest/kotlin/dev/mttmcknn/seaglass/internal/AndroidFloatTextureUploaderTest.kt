package dev.mttmcknn.seaglass.internal

import android.graphics.Bitmap
import android.util.Half
import androidx.test.filters.SdkSuppress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/** Runs on Android: the SDK's Bitmap/Half methods must not be replaced by host stubs. */
@SdkSuppress(minSdkVersion = 26)
class AndroidFloatTextureUploaderTest {
    @Test
    fun reusableUploadMatchesThePreviousDirectBufferAlgorithmBitForBit() {
        val uploader = AndroidFloatTextureUploader()
        val retained = mutableListOf<Pair<Bitmap, ByteArray>>()
        try {
            val sizes = listOf(1 to 1, 3 to 7, 17 to 9, 128 to 64, 17 to 9, 512 to 512, 3 to 7)
            for ((index, size) in sizes.withIndex()) {
                val texture = texture(size.first, size.second, index)
                val expected = referenceUpload(texture)
                try {
                    val actual = uploader.upload(texture)
                    retained.lastOrNull()?.let { assertNotSame(it.first, actual) }
                    val expectedBytes = bytes(expected)
                    retained += actual to expectedBytes
                    assertEquals(Bitmap.Config.RGBA_F16, actual.config)
                    assertEquals(expected.isPremultiplied, actual.isPremultiplied)
                    assertEquals(expected.colorSpace, actual.colorSpace)
                    assertEquals(expected.rowBytes, actual.rowBytes)
                    assertContentEquals(expectedBytes, bytes(actual), "Upload ${size.first}x${size.second}")
                    // Both shader inputs and earlier display lists retain their
                    // own bitmap after the one shared packing buffer is reused.
                    for ((previous, snapshot) in retained) {
                        assertContentEquals(snapshot, bytes(previous), "Later uploads must not mutate earlier bitmaps")
                    }
                } finally {
                    expected.recycle()
                }
            }
        } finally {
            uploader.clear()
            retained.forEach { it.first.recycle() }
        }
    }

    @Test
    fun scratchIsReusedBoundedAndReleased() {
        val uploader = AndroidFloatTextureUploader()
        fun uploadAndRelease(width: Int, height: Int) = uploader.upload(texture(width, height)).recycle()
        uploadAndRelease(512, 512)
        assertEquals(2 * 1024 * 1024, uploader.retainedScratchByteCount)
        val allocations = uploader.scratchAllocationCount
        repeat(3) { uploadAndRelease(17, 9) }
        assertEquals(allocations, uploader.scratchAllocationCount, "Smaller uploads should reuse the same scratch buffer")
        repeat(2) { uploadAndRelease(513, 512) }
        assertEquals(2 * 1024 * 1024, uploader.retainedScratchByteCount, "Oversized transient buffers must not grow retained memory")
        assertEquals(allocations + 2, uploader.scratchAllocationCount)
        uploadAndRelease(512, 512)
        assertEquals(allocations + 2, uploader.scratchAllocationCount, "Oversized uploads must not evict the bounded scratch buffer")
        uploader.clear()
        assertEquals(0, uploader.retainedScratchByteCount)
        uploadAndRelease(1, 1)
        assertEquals(8, uploader.retainedScratchByteCount)
        assertEquals(allocations + 3, uploader.scratchAllocationCount)
        uploader.clear()
    }

    @Test
    fun reusedRowsClearPaddingAndResetTheBufferWindow() {
        val uploader = AndroidFloatTextureUploader(maxScratchBytes = 128)
        try {
            // Populate every short first, then reuse that storage for narrower
            // padded rows. Exercise padding even if this device aligns F16 rows
            // exactly to width * 8 in ordinary freshly created bitmaps.
            uploader.pack(texture(4, 4, 3), rowBytes = 32, byteCount = 128).apply {
                position(limit())
            }
            val narrow = texture(3, 4, 1)
            for (byteCount in listOf(128, 120, 128)) {
                val actual = uploader.pack(narrow, rowBytes = 32, byteCount = byteCount)
                assertEquals(ByteOrder.nativeOrder(), actual.order())
                assertEquals(0, actual.position())
                assertEquals(byteCount / 2, actual.limit())
                val expected = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
                for (row in 0 until narrow.height) {
                    expected.position(row * 32)
                    for (channel in 0 until narrow.width * 4) {
                        expected.putShort(Half.toHalf(narrow.rgba[row * narrow.width * 4 + channel]))
                    }
                }
                expected.rewind()
                assertContentEquals(shorts(expected.asShortBuffer()), shorts(actual))
                actual.position(actual.limit())
            }
            assertEquals(1, uploader.scratchAllocationCount)
            assertTrue(uploader.retainedScratchByteCount <= 128)
        } finally {
            uploader.clear()
        }
    }

    private fun texture(width: Int, height: Int, phase: Int = 0): NativeFloatTexture {
        val values = floatArrayOf(
            0f, -0f, Float.MIN_VALUE, 0.00006103515625f, 0.000060975552f,
            0.33333334f, 0.49987793f, 0.50024414f, 1f, -1f, 65504f, 65520f,
            Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN,
        )
        return NativeFloatTexture(width, height, FloatArray(width * height * 4) { index ->
            if (index % 4 == 3) 1f else values[(index + phase) % values.size]
        })
    }

    private fun bytes(bitmap: Bitmap): ByteArray = ByteArray(bitmap.byteCount).also {
        bitmap.copyPixelsToBuffer(ByteBuffer.wrap(it))
    }

    private fun shorts(buffer: ShortBuffer): ShortArray = ShortArray(buffer.remaining()).also {
        buffer.duplicate().get(it)
    }

    // Copied from AndroidMaterialShader.setInputTexture before changing the
    // production uploader. Keep this independent reference packing algorithm.
    private fun referenceUpload(texture: NativeFloatTexture): Bitmap {
        val bitmap = Bitmap.createBitmap(texture.width, texture.height, Bitmap.Config.RGBA_F16)
        bitmap.setPremultiplied(false)
        // copyPixelsFromBuffer preserves the numeric F16 channel values. The
        // input-buffer API skips both color conversion and alpha premultiplication.
        val buffer = ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.nativeOrder())
        val channelsPerRow = texture.width * 4
        for (row in 0 until texture.height) {
            // Respect the bitmap allocator's native stride, including padding.
            buffer.position(row * bitmap.rowBytes)
            val start = row * channelsPerRow
            for (channel in 0 until channelsPerRow) {
                buffer.putShort(Half.toHalf(texture.rgba[start + channel]))
            }
        }
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }
}
