package dev.mttmcknn.seaglass.internal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class MaterialImageCacheTest {
    @Test
    fun ignoredPaletteChangesReuseTheUploadedImageButMaterialEditsUploadAgain() {
        val renderer = SeaGlassRenderer(composeMaskRasterizer, fastGround = true)
        var uploads = 0
        val images = MaterialImageCache { pixels ->
            uploads++
            pixels.toImageBitmap()
        }
        val settings = RenderSettings()
        val palette = RenderPalette(0xFF9ABBA9.toInt(), 0xFF659580.toInt(), 0xFF20332A.toInt())
        fun render(colors: RenderPalette) = renderer.render(48.0, 32.0, 1.0, RenderShape.Rounded, settings, colors)

        val firstPixels = render(palette)
        val first = images.imageFor(firstPixels)
        val inkChanged = images.imageFor(render(palette.copy(ink = 0xFFFFFFFF.toInt())))
        val alphaChanged = images.imageFor(render(palette.copy(body = palette.body and 0xFFFFFF, edge = palette.edge and 0xFFFFFF)))
        assertSame(first, inkChanged, "Text color changes must not allocate or upload a material bitmap")
        assertSame(first, alphaChanged, "Ignored pigment alpha must not upload a material bitmap")
        assertEquals(1, uploads)

        val changedPixels = render(palette.copy(body = 0xFFBBA0B9.toInt()))
        val changed = images.imageFor(changedPixels)
        assertNotSame(first, changed)
        assertEquals(2, uploads)
        val expected = changedPixels.toImageBitmap()
        val actualPixels = IntArray(changed.width * changed.height)
        val expectedPixels = IntArray(expected.width * expected.height)
        changed.readPixels(actualPixels)
        expected.readPixels(expectedPixels)
        assertContentEquals(expectedPixels, actualPixels, "A changed material must upload all new pixels")
        assertSame(changed, images.imageFor(changedPixels))
        assertEquals(2, uploads)
    }
}
