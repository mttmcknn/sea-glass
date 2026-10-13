package dev.mttmcknn.seaglass.internal

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** Detect native channel-format mismatches before testing the optical shader. */
class NativeMaterialShaderInputTest {
    @Test
    fun geometryTextureRetainsNormalizedFloatChannels() {
        val program = NativeMaterialProgram(
            """
            uniform shader data;
            half4 main(float2 position) {
                return half4(data.eval(position).rgb, 1.0);
            }
            """.trimIndent(),
        )
        val shader = checkNotNull(program.createInstance())
        try {
            shader.setInputTexture(
                "data",
                NativeFloatTexture(
                    2,
                    1,
                    floatArrayOf(.125f, .5f, .875f, 1f, .25f, .625f, .75f, 1f),
                ),
            )
            val image = ImageBitmap(2, 1)
            val path = Path().apply { addRect(Rect(0f, 0f, 2f, 1f)) }
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(2f, 1f)) {
                shader.draw(this, path)
            }
            val actual = IntArray(2)
            image.readPixels(actual)
            val expected = intArrayOf(0xFF2080DF.toInt(), 0xFF409FBF.toInt())
            for (pixel in expected.indices) for (shift in intArrayOf(24, 16, 8, 0)) {
                val difference = abs((actual[pixel] ushr shift and 255) - (expected[pixel] ushr shift and 255))
                assertTrue(difference <= 1, "Float texture pixel $pixel, channel $shift changed by $difference")
            }
        } finally {
            shader.close()
            program.close()
        }
    }
}
