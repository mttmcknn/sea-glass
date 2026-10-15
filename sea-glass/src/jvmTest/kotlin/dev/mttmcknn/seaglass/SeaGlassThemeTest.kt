package dev.mttmcknn.seaglass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat

/** Integration tests use real native composition, layout, and asynchronous material rendering. */
@OptIn(ExperimentalComposeUiApi::class)
class SeaGlassThemeTest {
    @Test
    fun unthemedComponentsKeepTheOriginalRecipe() = withScene {
        val observed = mutableMapOf<String, ThemeSnapshot>()
        setContent { Probe("default", observed) }
        awaitFrame { observed.isNotEmpty() }

        assertEquals(
            ThemeSnapshot(SeaGlassStyle.Reference, SeaGlassPalette.Lagoon, SeaGlassShape.Rounded),
            observed.getValue("default"),
        )
    }

    @Test
    fun nestedThemesInheritUnspecifiedValuesAndRestoreTheirParent() = withScene {
        val parent = SeaGlassComponentStyle(
            material = SeaGlassStyle.Tumbled.copy(seed = 73),
            palette = SeaGlassPalette.Mint,
            shape = SeaGlassShape.Capsule,
        )
        val quiet = SeaGlassStyle.Quiet.copy(seed = 91)
        val observed = mutableMapOf<String, ThemeSnapshot>()
        setContent {
            SeaGlassTheme(style = parent) {
                Probe("parent", observed)
                SeaGlassTheme(palette = SeaGlassPalette.Rose) {
                    Probe("palette-only", observed)
                    SeaGlassTheme(style = quiet, shape = SeaGlassShape.Disc) {
                        Probe("recipe-and-shape", observed)
                    }
                    Probe("restored-child", observed)
                }
                Probe("restored-parent", observed)
                SeaGlassTheme(style = SeaGlassComponentStyle(palette = SeaGlassPalette.Amber)) {
                    Probe("complete-style", observed)
                }
            }
            Probe("outside", observed)
        }
        awaitFrame { observed.size == 7 }

        val expectedParent = ThemeSnapshot(parent.material, parent.palette, parent.shape)
        val expectedChild = expectedParent.copy(palette = SeaGlassPalette.Rose)
        assertEquals(expectedParent, observed["parent"])
        assertEquals(expectedChild, observed["palette-only"])
        assertEquals(expectedChild.copy(style = quiet, shape = SeaGlassShape.Disc), observed["recipe-and-shape"])
        assertEquals(expectedChild, observed["restored-child"])
        assertEquals(expectedParent, observed["restored-parent"])
        assertEquals(ThemeSnapshot(SeaGlassStyle.Reference, SeaGlassPalette.Amber, SeaGlassShape.Rounded), observed["complete-style"])
        assertEquals(ThemeSnapshot(SeaGlassStyle.Reference, SeaGlassPalette.Lagoon, SeaGlassShape.Rounded), observed["outside"])
    }

    @Test
    fun materialThemeSurvivesAndComponentsProvideTheResolvedForeground() = withScene {
        val typography = Typography(bodyLarge = TextStyle(fontSize = 23.sp))
        val scheme = lightColorScheme(primary = Color(0xFF612DA5))
        val observed = mutableMapOf<String, Color>()
        var observedTypography: Typography? = null
        var observedPrimary: Color? = null
        setContent {
            MaterialTheme(typography = typography, colorScheme = scheme) {
                SeaGlassTheme(palette = SeaGlassPalette.Mint) {
                    val currentTypography = MaterialTheme.typography
                    val currentPrimary = MaterialTheme.colorScheme.primary
                    SideEffect {
                        observedTypography = currentTypography
                        observedPrimary = currentPrimary
                    }
                    Column {
                        SeaGlassSurface(Modifier.size(72.dp)) { InkProbe("surface", observed) }
                        SeaGlassButton(onClick = {}, modifier = Modifier.size(72.dp)) { InkProbe("button", observed) }
                        SeaGlassSurface(Modifier.size(72.dp), palette = SeaGlassPalette.Rose) {
                            InkProbe("override", observed)
                        }
                    }
                }
            }
        }
        awaitFrame { observed.size == 3 }

        assertEquals(typography, observedTypography)
        assertEquals(scheme.primary, observedPrimary)
        assertEquals(SeaGlassPalette.Mint.ink, observed["surface"])
        assertEquals(SeaGlassPalette.Mint.ink, observed["button"])
        assertEquals(SeaGlassPalette.Rose.ink, observed["override"])
    }

    @Test
    fun materialAdapterInheritsRecipeAndShapeAndTracksTheMaterialTheme() = withScene {
        val parent = SeaGlassComponentStyle(
            material = SeaGlassStyle.Tumbled.copy(seed = 73),
            palette = SeaGlassPalette.Mint,
            shape = SeaGlassShape.Capsule,
        )
        val scheme = mutableStateOf(lightColorScheme())
        val observed = mutableMapOf<String, ThemeSnapshot>()
        setContent {
            MaterialTheme(colorScheme = scheme.value) {
                SeaGlassTheme(style = parent) {
                    SeaGlassMaterialTheme {
                        Probe("primary", observed)
                        SeaGlassMaterialTheme(role = SeaGlassColorRole.Tertiary) {
                            Probe("tertiary", observed)
                            SeaGlassTheme(palette = SeaGlassPalette.Rose) {
                                Probe("override", observed)
                            }
                        }
                        Probe("restored-primary", observed)
                    }
                    Probe("restored-parent", observed)
                }
            }
        }
        awaitFrame { observed.size == 5 }
        fun verify() {
            val primary = ThemeSnapshot(parent.material, SeaGlassPalette.fromColorScheme(scheme.value), parent.shape)
            assertEquals(primary, observed["primary"])
            assertEquals(primary, observed["restored-primary"])
            assertEquals(
                primary.copy(palette = SeaGlassPalette.fromColorScheme(scheme.value, SeaGlassColorRole.Tertiary)),
                observed["tertiary"],
            )
            assertEquals(primary.copy(palette = SeaGlassPalette.Rose), observed["override"])
            assertEquals(ThemeSnapshot(parent.material, parent.palette, parent.shape), observed["restored-parent"])
        }
        verify()

        scheme.value = darkColorScheme()
        awaitFrame { observed["primary"]?.palette?.body == scheme.value.primaryContainer }
        verify()
    }

    @Test
    fun materialPaletteIsMemoizedByResolvedColorsAndUpdatesWithRoleAndInk() = withScene {
        val scheme = mutableStateOf(lightColorScheme())
        val role = mutableStateOf(SeaGlassColorRole.Primary)
        val unrelated = mutableStateOf(0)
        var observed: SeaGlassPalette? = null
        var observedRevision = -1
        setContent {
            MaterialTheme(colorScheme = scheme.value) {
                val palette = SeaGlassPalette.materialTheme(role.value)
                val revision = unrelated.value
                SideEffect {
                    observed = palette
                    observedRevision = revision
                }
            }
        }
        awaitFrame { observed != null }
        val initial = observed

        unrelated.value++
        awaitFrame { observedRevision == 1 }
        assertSame(initial, observed, "Unrelated recomposition must retain the palette")

        scheme.value = scheme.value.copy(surface = Color.Red)
        unrelated.value++
        awaitFrame { observedRevision == 2 }
        assertSame(initial, observed, "An unrelated Material color must retain the palette")

        role.value = SeaGlassColorRole.Secondary
        awaitFrame { observed?.body == scheme.value.secondaryContainer }
        assertEquals(SeaGlassPalette.fromColorScheme(scheme.value, SeaGlassColorRole.Secondary), observed)
        assertNotSame(initial, observed)

        val secondary = observed
        scheme.value = scheme.value.copy(secondaryContainer = Color(0xFFCDE9E0))
        awaitFrame { observed?.body == scheme.value.secondaryContainer }
        assertNotSame(secondary, observed)

        val changedBody = observed
        val ink = Color(0xFF143528).copy(alpha = .8f)
        scheme.value = scheme.value.copy(onSecondaryContainer = ink)
        awaitFrame { observed?.ink == ink }
        assertNotSame(changedBody, observed)
        assertEquals(changedBody?.body, observed?.body)
        assertEquals(changedBody?.edge, observed?.edge)
    }

    @Test
    fun aLiveThemeChangeUpdatesBothCompositionAndTheRenderedMaterial() = withScene {
        val initial = SeaGlassComponentStyle(
            material = SeaGlassStyle.Reference.copy(seed = 31),
            palette = SeaGlassPalette.Mint,
            shape = SeaGlassShape.Rounded,
        )
        val selected = mutableStateOf(initial)
        val observed = mutableMapOf<String, ThemeSnapshot>()
        setContent {
            Box(Modifier.size(256.dp).background(Color.White)) {
                SeaGlassTheme(style = selected.value) {
                    Probe("current", observed)
                    SeaGlassSurface(Modifier.offset(64.dp, 64.dp).size(128.dp, 72.dp)) {}
                }
            }
        }
        // The fallback has no light below the shape: this waits for the asynchronous renderer.
        val before = awaitFrame { image -> image.getRGB(128, 145) != WHITE }
        val beforeCenter = before.getRGB(128, 100)
        val next = initial.copy(
            material = SeaGlassStyle.Tumbled.copy(seed = 97),
            palette = SeaGlassPalette.Rose,
            shape = SeaGlassShape.Capsule,
        )
        selected.value = next
        val after = awaitFrame { image ->
            observed["current"] == ThemeSnapshot(next.material, next.palette, next.shape) &&
                image.getRGB(128, 100) != beforeCenter
        }

        assertNotEquals(beforeCenter, after.getRGB(128, 100))
        assertNotEquals(before.getRGB(128, 145), after.getRGB(128, 145))
    }

    @Test
    fun themedDefaultsRenderLikeExplicitReusableStylesForEveryEntryPoint() = withScene(width = 304, height = 540) {
        val selected = SeaGlassComponentStyle(
            material = SeaGlassStyle.Tumbled.copy(seed = 73, grain = .32f),
            palette = SeaGlassPalette.Mint,
            shape = SeaGlassShape.Capsule,
        )
        val useTheme = mutableStateOf(true)
        var showingTheme: Boolean? = null
        setContent {
            val themed = useTheme.value
            SideEffect { showingTheme = themed }
            Box(Modifier.size(304.dp, 540.dp).background(Color.White)) {
                if (themed) {
                    SeaGlassTheme(style = selected) {
                        SeaGlassButton(onClick = {}, modifier = specimen(72, 72)) {}
                        SeaGlassSurface(modifier = specimen(72, 236)) {}
                        Box(specimen(72, 400).seaGlass())
                    }
                } else {
                    // Use exactly the same raster coordinates. Translating a
                    // native antialiased path can round a boundary channel by
                    // one level; that is independent of theme resolution.
                    SeaGlassButton(onClick = {}, modifier = specimen(72, 72), style = selected) {}
                    SeaGlassSurface(modifier = specimen(72, 236), style = selected) {}
                    Box(specimen(72, 400).seaGlass(style = selected))
                }
            }
        }
        fun complete(image: BufferedImage): Boolean = listOf(72, 236, 400).all { y -> image.getRGB(150, y + 78) != WHITE }
        val inheritedFrame = awaitFrame { showingTheme == true && complete(it) }
        useTheme.value = false
        val explicitFrame = awaitFrame { showingTheme == false && complete(it) }
        for ((entryPoint, y) in listOf("button" to 72, "surface" to 236, "modifier" to 400)) {
            val inherited = inheritedFrame.getRGB(24, y - 48, 252, 164, null, 0, 252)
            val explicit = explicitFrame.getRGB(24, y - 48, 252, 164, null, 0, 252)
            assertTrue(inherited.contentEquals(explicit), "$entryPoint: inherited and explicit styles must render identically")
        }
    }

    private fun specimen(x: Int, y: Int) = Modifier.offset(x.dp, y.dp).size(156.dp, 68.dp)

    private fun withScene(
        width: Int = 256,
        height: Int = 256,
        block: suspend ImageComposeScene.() -> Unit,
    ) = runBlocking {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f), coroutineContext = coroutineContext)
        try {
            scene.block()
        } finally {
            scene.close()
        }
    }

    private suspend fun ImageComposeScene.awaitFrame(ready: (BufferedImage) -> Boolean): BufferedImage {
        val start = System.nanoTime()
        while (true) {
            val now = System.nanoTime()
            val image = render(now).use { snapshot ->
                checkNotNull(snapshot.encodeToData(EncodedImageFormat.PNG)).use { data ->
                    ImageIO.read(ByteArrayInputStream(data.bytes))
                }
            }
            if (ready(image)) return image
            check(now - start < 15_000_000_000L) { "Native Compose scene did not reach the expected state within 15 seconds" }
            delay(16)
        }
    }

    private data class ThemeSnapshot(val style: SeaGlassStyle, val palette: SeaGlassPalette, val shape: SeaGlassShape)

    @Composable
    private fun Probe(name: String, observed: MutableMap<String, ThemeSnapshot>) {
        val current = ThemeSnapshot(SeaGlassTheme.style, SeaGlassTheme.palette, SeaGlassTheme.shape)
        val component = SeaGlassTheme.componentStyle
        SideEffect {
            assertEquals(current, ThemeSnapshot(component.material, component.palette, component.shape))
            observed[name] = current
        }
    }

    @Composable
    private fun InkProbe(name: String, observed: MutableMap<String, Color>) {
        val ink = LocalContentColor.current
        SideEffect { observed[name] = ink }
    }

    private companion object {
        const val WHITE = -1
    }
}
