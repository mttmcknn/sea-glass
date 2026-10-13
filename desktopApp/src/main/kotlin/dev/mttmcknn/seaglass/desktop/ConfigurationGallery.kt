package dev.mttmcknn.seaglass.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mttmcknn.seaglass.SeaGlassButton
import dev.mttmcknn.seaglass.SeaGlassPalette
import dev.mttmcknn.seaglass.SeaGlassShape
import dev.mttmcknn.seaglass.SeaGlassStyle
import dev.mttmcknn.seaglass.SeaGlassSurface

private val GalleryInk = Color(0xFF283E37)
private val GalleryMuted = Color(0xFF65796F)
private val GalleryPaper = Color(0xFFF5F6F1)
private val GalleryBorder = Color(0xFFE1E7DF)

/** Actual public composables, composed for reproducible native screenshot documentation. */
@Composable
fun SpecimenGallery() {
    GalleryPage(
        number = "01 / THE COMPOSABLES",
        title = "Sea glass, composed.",
        subtitle = "Native Compose content over a procedural material. One recipe, several kinds of component.",
    ) {
        GalleryCard(Modifier.fillMaxWidth().height(422.dp)) {
            GalleryEyebrow("BUTTONS, A TOOLBAR, AND A PAIR OF PURE SURFACES")
            Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1.1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        SeaGlassSurface(
                            modifier = Modifier.size(408.dp, 80.dp), palette = SeaGlassPalette.Lilac,
                            shape = SeaGlassShape.Capsule,
                        ) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                listOf(GalleryGlyph.Share, GalleryGlyph.Message, GalleryGlyph.Download,
                                    GalleryGlyph.Edit, GalleryGlyph.More).forEach { GalleryIcon(it) }
                            }
                        }
                        SeaGlassButton(onClick = {}, modifier = Modifier.size(80.dp),
                            palette = SeaGlassPalette.Rose, contentPadding = PaddingValues(0.dp)) {
                            GalleryIcon(GalleryGlyph.Plus, Modifier.size(32.dp))
                        }
                    }
                    Spacer(Modifier.height(55.dp))
                    SeaGlassButton(onClick = {}, modifier = Modifier.size(304.dp, 100.dp),
                        palette = SeaGlassPalette.Lagoon) {
                        GalleryIcon(GalleryGlyph.Edit, Modifier.size(30.dp))
                        Spacer(Modifier.width(18.dp))
                        Text("Compose", fontSize = 27.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.width(54.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(horizontalArrangement = Arrangement.spacedBy(35.dp), verticalAlignment = Alignment.CenterVertically) {
                        SeaGlassSurface(Modifier.size(126.dp), palette = SeaGlassPalette.Mint,
                            shape = SeaGlassShape.Disc) {}
                        SeaGlassSurface(Modifier.size(246.dp, 126.dp), palette = SeaGlassPalette.Mint) {}
                    }
                    Spacer(Modifier.height(54.dp))
                    Text("The light and grain belong to the material.\nThe text and icons remain native Compose.",
                        fontSize = 17.sp, lineHeight = 27.sp, color = GalleryMuted)
                }
            }
            Text("Reference recipe  ·  tumble 2.5%  ·  bulge 0.65  ·  seed 42", color = GalleryMuted, fontSize = 13.sp)
        }
        Spacer(Modifier.height(24.dp))
        GalleryCard(Modifier.fillMaxWidth().height(238.dp)) {
            GalleryEyebrow("FIVE PIGMENTS. THE SAME MATERIAL.")
            Spacer(Modifier.height(36.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("Lagoon" to SeaGlassPalette.Lagoon, "Lilac" to SeaGlassPalette.Lilac,
                    "Rose" to SeaGlassPalette.Rose, "Amber" to SeaGlassPalette.Amber,
                    "Mint" to SeaGlassPalette.Mint).forEach { (name, palette) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        SeaGlassButton(onClick = {}, modifier = Modifier.size(200.dp, 78.dp), palette = palette) {
                            Text(name, fontSize = 21.sp, fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.height(27.dp))
                        Text("body  /  edge  /  ink", color = GalleryMuted, fontSize = 12.sp)
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        GalleryFooter("SeaGlassButton  ·  SeaGlassSurface  ·  Modifier.seaGlass()")
    }
}

/** Two independent geometry controls, with pigment and all other optical settings held fixed. */
@Composable
fun TumbleBulgeGallery() {
    GalleryPage(
        number = "02 / A CONTROLLED SHAPE STUDY",
        title = "Tumble changes the outline. Bulge changes the body.",
        subtitle = "Each specimen uses Mint and seed 42. Only the row’s tumble and the column’s bulge change.",
    ) {
        GalleryCard(Modifier.fillMaxWidth().height(720.dp)) {
            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                GalleryEyebrow("TUMBLE", Modifier.width(116.dp))
                listOf("0.15  /  shallow", "0.65  /  reference", "1.00  /  full").forEach { label ->
                    Text("BULGE  $label", modifier = Modifier.weight(1f), fontSize = 15.sp,
                        fontWeight = FontWeight.Medium, color = GalleryInk)
                }
            }
            listOf(0f to "0.0%", .025f to "2.5%", .065f to "6.5%").forEachIndexed { row, (tumble, label) ->
                Row(Modifier.fillMaxWidth().height(191.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(116.dp)) {
                        Text(label, color = GalleryInk, fontSize = 24.sp, fontFamily = FontFamily.Serif)
                        Spacer(Modifier.height(4.dp))
                        Text(listOf("uniform", "gentle", "most worn")[row], fontSize = 12.sp, color = GalleryMuted)
                    }
                    listOf(.15f, .65f, 1f).forEach { bulge ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            SeaGlassSurface(
                                modifier = Modifier.size(274.dp, 122.dp),
                                style = SeaGlassStyle.Reference.copy(tumble = tumble, bulge = bulge),
                                palette = SeaGlassPalette.Mint,
                            ) {}
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text("Fixed: frost 0.55  ·  grain 0.48  ·  thickness 6 dp  ·  elevation 8 dp  ·  transmission 0.58  ·  light 315°",
                color = GalleryMuted, fontSize = 13.sp)
        }
        Spacer(Modifier.weight(1f))
        GalleryFooter("Same seed = the same piece. Increasing tumble amplifies its existing asymmetry.")
    }
}

/** The built-in recipes and the independent surface-light direction, captured from the actual renderer. */
@Composable
fun MaterialRecipesGallery() {
    GalleryPage(
        number = "03 / MATERIAL AND LIGHT",
        title = "A recipe is a set of relationships.",
        subtitle = "Three shipped presets in Mint, with seed 42 and 315° surface light. Shape and optical depth work together.",
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            RecipeCard("Quiet", "A lighter, shallower fragment.", SeaGlassStyle.Quiet,
                "0.38", "0.23", "1.2%", "0.38", "3 dp", "4 dp", "0.66", Modifier.weight(1f))
            RecipeCard("Reference", "The approved starting point.", SeaGlassStyle.Reference,
                "0.55", "0.48", "2.5%", "0.65", "6 dp", "8 dp", "0.58", Modifier.weight(1f))
            RecipeCard("Tumbled", "Cloudier, fuller, more weathered.", SeaGlassStyle.Tumbled,
                "0.72", "0.68", "5.5%", "0.80", "9 dp", "7 dp", "0.48", Modifier.weight(1f))
        }
        Spacer(Modifier.height(24.dp))
        GalleryCard(Modifier.fillMaxWidth().height(266.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GalleryEyebrow("SURFACE LIGHT MOVES. CAST SHADOWS STILL FALL DOWN.")
                Spacer(Modifier.weight(1f))
                Text("Reference recipe · Lagoon · seed 42", fontSize = 12.sp, color = GalleryMuted)
            }
            Spacer(Modifier.height(26.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                listOf(315f to "315° · upper left", 90f to "90° · right", 180f to "180° · below").forEach { (angle, label) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        SeaGlassSurface(Modifier.size(280.dp, 100.dp),
                            style = SeaGlassStyle.Reference.copy(lightDegrees = angle), palette = SeaGlassPalette.Lagoon) {}
                        Spacer(Modifier.height(35.dp))
                        Text(label, fontSize = 15.sp, color = GalleryInk)
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        GalleryFooter("Surface reflections respond to the angle; lower edge transmission and the downward ground light stay anchored.")
    }
}

@Composable
private fun RecipeCard(
    name: String, description: String, style: SeaGlassStyle,
    frost: String, grain: String, tumble: String, bulge: String,
    thickness: String, elevation: String, transmission: String,
    modifier: Modifier,
) {
    GalleryCard(modifier.height(444.dp)) {
        Text(name, fontFamily = FontFamily.Serif, fontSize = 27.sp, color = GalleryInk)
        Spacer(Modifier.height(7.dp))
        Text(description, fontSize = 14.sp, color = GalleryMuted)
        Spacer(Modifier.height(32.dp))
        SeaGlassSurface(Modifier.size(284.dp, 104.dp).align(Alignment.CenterHorizontally),
            style = style, palette = SeaGlassPalette.Mint) {}
        Spacer(Modifier.height(31.dp))
        RecipeLine("Frost / grain", "$frost / $grain")
        RecipeLine("Tumble / bulge", "$tumble / $bulge")
        RecipeLine("Thickness / lift", "$thickness / $elevation")
        RecipeLine("Transmission", transmission)
    }
}

@Composable
private fun RecipeLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = GalleryMuted, fontSize = 14.sp)
        Text(value, color = GalleryInk, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun GalleryPage(number: String, title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = GalleryInk, onSurface = GalleryInk)) {
        Column(Modifier.fillMaxSize().background(GalleryPaper).padding(horizontal = 52.dp, vertical = 38.dp)) {
            GalleryEyebrow("SEA GLASS   /   $number")
            Spacer(Modifier.height(15.dp))
            Text(title, fontSize = 35.sp, fontFamily = FontFamily.Serif, color = GalleryInk, lineHeight = 42.sp)
            Spacer(Modifier.height(10.dp))
            Text(subtitle, fontSize = 16.sp, lineHeight = 24.sp, color = GalleryMuted)
            Spacer(Modifier.height(25.dp))
            content()
        }
    }
}

@Composable
private fun GalleryCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.background(Color.White, RoundedCornerShape(24.dp))
        .border(1.dp, GalleryBorder, RoundedCornerShape(24.dp)).padding(28.dp), content = content)
}

@Composable
private fun GalleryEyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, color = GalleryMuted, fontSize = 11.sp,
        letterSpacing = 1.1.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun GalleryFooter(text: String) {
    Text(text, color = GalleryMuted, fontSize = 13.sp, lineHeight = 19.sp)
}

private enum class GalleryGlyph { Share, Message, Download, Edit, More, Plus }

@Composable
private fun GalleryIcon(glyph: GalleryGlyph, modifier: Modifier = Modifier.size(28.dp)) {
    val ink = LocalContentColor.current
    Canvas(modifier) {
        val unit = size.minDimension / 24f
        fun p(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(
            ink, p(x1, y1), p(x2, y2), strokeWidth = 2.4f * unit, cap = StrokeCap.Round)
        when (glyph) {
            GalleryGlyph.Plus -> { line(12f, 4f, 12f, 20f); line(4f, 12f, 20f, 12f) }
            GalleryGlyph.More -> listOf(5f, 12f, 19f).forEach { drawCircle(ink, 1.8f * unit, p(12f, it)) }
            GalleryGlyph.Share -> {
                line(6f, 12f, 18f, 5f); line(6f, 12f, 18f, 19f)
                listOf(p(6f, 12f), p(18f, 5f), p(18f, 19f)).forEach { drawCircle(ink, 2.7f * unit, it) }
            }
            GalleryGlyph.Download -> {
                line(12f, 3f, 12f, 15f); line(7f, 10f, 12f, 15f); line(17f, 10f, 12f, 15f)
                line(4f, 16f, 4f, 20f); line(4f, 20f, 20f, 20f); line(20f, 20f, 20f, 16f)
            }
            GalleryGlyph.Edit -> {
                val path = Path().apply {
                    moveTo(4f * unit, 16f * unit); lineTo(16f * unit, 4f * unit)
                    lineTo(20f * unit, 8f * unit); lineTo(8f * unit, 20f * unit)
                    lineTo(4f * unit, 20f * unit); close()
                }
                drawPath(path, ink, style = Stroke(2.3f * unit))
                line(13f, 7f, 17f, 11f)
            }
            GalleryGlyph.Message -> {
                val path = Path().apply {
                    moveTo(3f * unit, 4f * unit); lineTo(21f * unit, 4f * unit)
                    lineTo(21f * unit, 17f * unit); lineTo(14f * unit, 17f * unit)
                    lineTo(9f * unit, 21f * unit); lineTo(9f * unit, 17f * unit)
                    lineTo(3f * unit, 17f * unit); close()
                }
                drawPath(path, ink, style = Stroke(2.2f * unit))
                line(7f, 9f, 17f, 9f); line(7f, 13f, 17f, 13f)
            }
        }
    }
}
