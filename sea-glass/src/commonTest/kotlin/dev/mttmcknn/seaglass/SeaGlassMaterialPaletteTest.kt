package dev.mttmcknn.seaglass

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.test.Test
import kotlin.test.assertEquals

class SeaGlassMaterialPaletteTest {
    @Test
    fun eachRoleUsesItsContainerAndMatchingForegroundInLightAndDarkSchemes() {
        for (scheme in listOf(lightColorScheme(), darkColorScheme())) {
            val expected = listOf(
                Triple(SeaGlassColorRole.Primary, scheme.primaryContainer, scheme.onPrimaryContainer),
                Triple(SeaGlassColorRole.Secondary, scheme.secondaryContainer, scheme.onSecondaryContainer),
                Triple(SeaGlassColorRole.Tertiary, scheme.tertiaryContainer, scheme.onTertiaryContainer),
            )
            for ((role, container, foreground) in expected) {
                val palette = SeaGlassPalette.fromColorScheme(scheme, role)
                assertEquals(container, palette.body, "$role pigment")
                assertEquals(lerp(container, Color.White, .35f), palette.edge, "$role transmitted edge")
                assertEquals(foreground, palette.ink, "$role foreground")
            }
        }
    }

    @Test
    fun defaultRoleUsesCustomPrimaryContainerAndPreservesForegroundAlpha() {
        val body = Color(0xFF376D5C)
        val ink = Color(0xFFF4FFF8).copy(alpha = .7f)
        val scheme = lightColorScheme(
            primary = Color.Red,
            onPrimary = Color.Blue,
            primaryContainer = body,
            onPrimaryContainer = ink,
        )

        val palette = SeaGlassPalette.fromColorScheme(scheme)

        assertEquals(body, palette.body)
        assertEquals(lerp(body, Color.White, .35f), palette.edge)
        assertEquals(ink, palette.ink)
    }
}
