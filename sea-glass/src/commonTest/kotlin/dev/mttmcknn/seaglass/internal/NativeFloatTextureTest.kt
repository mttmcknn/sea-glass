package dev.mttmcknn.seaglass.internal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeFloatTextureTest {
    @Test
    fun conversionPreservesHalfBoundariesAndRoundsTiesToEven() {
        val cases = listOf(
            0f to 0x0000,
            Float.MIN_VALUE to 0x0000,
            0.0000000298023223876953125f to 0x0000,
            0.000000059604644775390625f to 0x0001,
            0.0000000894069671630859375f to 0x0002,
            0.0000610053539276123046875f to 0x0400,
            0.00006103515625f to 0x0400,
            0.5f to 0x3800,
            0.500244140625f to 0x3800,
            0.500732421875f to 0x3802,
            1f to 0x3C00,
            1.00048828125f to 0x3C00,
            1.00146484375f to 0x3C02,
            65504f to 0x7BFF,
            65520f to 0x7C00,
            Float.POSITIVE_INFINITY to 0x7C00,
        )
        for ((value, expected) in cases) {
            assertEquals(expected, float16Bits(value), "Positive input $value")
            assertEquals(expected or 0x8000, float16Bits(-value), "Negative input $value")
        }
        assertEquals(0x7E00, float16Bits(Float.fromBits(0x7FC00001)))
        assertEquals(0xFE00, float16Bits(Float.fromBits(0xFFC00001.toInt())))
    }

    @Test
    fun textureStoresOnlyPackedLittleEndianChannels() {
        val source = floatArrayOf(.125f, .5f, .875f, 1f, .25f, .625f, .75f, 1f)
        val texture = NativeFloatTexture(2, 1, source)
        val expected = byteArrayOf(0, 0x30, 0, 0x38, 0, 0x3B, 0, 0x3C, 0, 0x34, 0, 0x39, 0, 0x3A, 0, 0x3C)
        assertContentEquals(expected, texture.rgbaF16)
        assertEquals(16, texture.rgbaF16.size)
        source.fill(0f)
        assertContentEquals(expected, texture.rgbaF16, "The float convenience input must not be retained")
        assertFailsWith<IllegalArgumentException> { NativeFloatTexture(2, 1, ByteArray(15)) }
        assertFailsWith<IllegalArgumentException> { NativeFloatTexture(0, 1, ByteArray(0)) }
    }
}
