package dev.mttmcknn.seaglass.sample

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mttmcknn.seaglass.SeaGlassButton
import dev.mttmcknn.seaglass.SeaGlassPalette
import dev.mttmcknn.seaglass.SeaGlassShape
import dev.mttmcknn.seaglass.SeaGlassStyle
import dev.mttmcknn.seaglass.SeaGlassSurface
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val Ink = Color(0xff283e37)
private val Muted = Color(0xff74847c)
private val Page = Color(0xfff4f5f0)
private val Border = Color(0xffe2e6dd)

/** The same material recipe and specimens as the HTML playground, rendered by Compose. */
@Composable
fun SeaGlassPlayground() {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xff507864),
            onPrimary = Color.White,
            secondaryContainer = Color(0xffe3ece2),
            onSecondaryContainer = Ink,
            surface = Page,
            background = Page,
            onSurface = Ink,
            onBackground = Ink,
        ),
    ) {
        var style by remember { mutableStateOf(SeaGlassStyle()) }
        var paletteIndex by remember { mutableIntStateOf(0) }
        var preset by remember { mutableStateOf("Reference") }
        var tab by remember { mutableIntStateOf(0) }
        var saved by remember { mutableStateOf(false) }
        var showComposer by remember { mutableStateOf(false) }
        var note by remember { mutableStateOf("") }
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val report: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) }; Unit }
        val updateStyle: (SeaGlassStyle) -> Unit = { style = it; preset = "" }
        val reset = { style = SeaGlassStyle(); paletteIndex = 0; preset = "Reference" }

        Box(Modifier.fillMaxSize().background(Page)) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().border(1.dp, Border).padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BrandMark()
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("sea glass", fontSize = 24.sp, fontFamily = FontFamily.Serif, color = Ink)
                        Eyebrow("MATERIAL PLAYGROUND")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = reset) { Text("Reset") }
                }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    val wide = maxWidth >= 960.dp
                    if (wide) {
                        Row(Modifier.fillMaxSize().padding(28.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                                PlaygroundPreview(style, paletteIndex, tab, { tab = it }, { paletteIndex = it },
                                    { showComposer = true }, saved, { saved = !saved }, report)
                            }
                            Column(Modifier.width(326.dp).verticalScroll(rememberScrollState())) {
                                MaterialControls(style, updateStyle, preset, { name ->
                                    style = presetStyle(name, style.seed); preset = name
                                }, paletteIndex, { paletteIndex = it })
                            }
                        }
                    } else {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(28.dp),
                        ) {
                            PlaygroundPreview(style, paletteIndex, tab, { tab = it }, { paletteIndex = it },
                                { showComposer = true }, saved, { saved = !saved }, report)
                            MaterialControls(style, updateStyle, preset, { name ->
                                style = presetStyle(name, style.seed); preset = name
                            }, paletteIndex, { paletteIndex = it })
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
        if (showComposer) {
            AlertDialog(
                onDismissRequest = { showComposer = false },
                title = { Text("Collect a small moment", fontFamily = FontFamily.Serif) },
                text = {
                    OutlinedTextField(
                        value = note, onValueChange = { note = it },
                        label = { Text("A note to yourself") },
                        modifier = Modifier.fillMaxWidth(), minLines = 3,
                    )
                },
                confirmButton = {
                    TextButton(enabled = note.isNotBlank(), onClick = {
                        showComposer = false; saved = true; report("Your moment is saved for this session")
                    }) { Text("Save moment") }
                },
                dismissButton = { TextButton(onClick = { showComposer = false }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun PlaygroundPreview(
    style: SeaGlassStyle,
    paletteIndex: Int,
    tab: Int,
    onTab: (Int) -> Unit,
    onPalette: (Int) -> Unit,
    onCompose: () -> Unit,
    saved: Boolean,
    onSave: () -> Unit,
    report: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Eyebrow("SMOOTHED BY THE SEA. SHAPED BY YOU.")
        Spacer(Modifier.height(14.dp))
        Text("A little less perfect.", fontSize = 34.sp, lineHeight = 40.sp, fontFamily = FontFamily.Serif, color = Ink)
        Text("A little more character.", fontSize = 34.sp, lineHeight = 40.sp, fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic, color = Color(0xff728879))
        Spacer(Modifier.height(14.dp))
        Text("Frosted color, softened edges, a little weight.\nFind the feel of your next interface.",
            fontSize = 14.sp, lineHeight = 22.sp, color = Muted)
        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf("Components", "Shape study", "In context").forEachIndexed { index, title ->
                TextButton(onClick = { onTab(index) }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Text(title, color = if (tab == index) Ink else Muted,
                        fontWeight = if (tab == index) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(20.dp))
                .border(1.dp, Border, RoundedCornerShape(20.dp)).padding(20.dp),
        ) {
            Eyebrow(when (tab) {
                1 -> "02 / NO TWO ALIKE"
                2 -> "03 / A LITTLE EVERYDAY MAGIC"
                else -> "01 / THE ORIGINAL FEEL"
            })
            when (tab) {
                1 -> ShapeStudy(style, paletteIndex)
                2 -> ContextStudy(style, paletteIndex, saved, onSave, onCompose)
                else -> ComponentStudy(style, paletteIndex, onPalette, onCompose, report)
            }
            Text(if (tab == 1) "Same recipe. Different histories." else "A soft surface. A solid little object.",
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 22.dp, bottom = 8.dp),
                fontSize = 12.sp, color = Muted, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic)
        }
        Spacer(Modifier.height(28.dp))
        Eyebrow("SMALL, EVERYDAY OBJECTS")
        Spacer(Modifier.height(18.dp))
        SeaGlassButton(
            onClick = onSave, modifier = Modifier.height(62.dp).align(Alignment.CenterHorizontally),
            style = style.copy(seed = style.seed + 144), palette = selectedPalette(paletteIndex, SeaGlassPalette.Lagoon),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
        ) {
            GlassIcon(if (saved) GlassGlyph.Check else GlassGlyph.Bookmark)
            Spacer(Modifier.width(12.dp))
            Text(if (saved) "Moment saved" else "Save a moment", fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(30.dp))
        Text("Made of curves, color, and a little imperfection.", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ComponentStudy(
    style: SeaGlassStyle, paletteIndex: Int, onPalette: (Int) -> Unit,
    onCompose: () -> Unit, report: (String) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(42.dp))
        Row(Modifier.widthIn(max = 550.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val toolbarPalette = selectedPalette(paletteIndex, SeaGlassPalette.Lilac)
            SeaGlassSurface(
                modifier = Modifier.weight(1f).height(66.dp), style = style.copy(seed = style.seed + 11),
                palette = toolbarPalette, shape = SeaGlassShape.Capsule,
            ) {
                Row(Modifier.fillMaxSize().padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly) {
                    ActionIcon(GlassGlyph.Share, "Share", toolbarPalette.ink, Modifier.weight(1f)) { report("Sharing is a visual demo") }
                    ActionIcon(GlassGlyph.Message, "Comments", toolbarPalette.ink, Modifier.weight(1f)) { report("No comments yet. Start with a small moment.") }
                    ActionIcon(GlassGlyph.Download, "Download recipe", toolbarPalette.ink, Modifier.weight(1f)) { report("Use the HTML playground to export a material recipe") }
                    ActionIcon(GlassGlyph.Edit, "Edit", toolbarPalette.ink, Modifier.weight(1f), onCompose)
                    Box(Modifier.weight(1f)) {
                        ActionIcon(GlassGlyph.More, "More actions", toolbarPalette.ink, Modifier.fillMaxWidth()) { showMenu = true }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(text = { Text("Compose a moment") }, onClick = { showMenu = false; onCompose() })
                            DropdownMenuItem(text = { Text("About this material") }, onClick = {
                                showMenu = false; report("Seeded curves, frosted color, and soft transmitted light")
                            })
                        }
                    }
                }
            }
            SeaGlassButton(
                onClick = onCompose, modifier = Modifier.size(64.dp).semantics { contentDescription = "Add a moment" },
                style = style.copy(seed = style.seed + 25), palette = selectedPalette(paletteIndex, SeaGlassPalette.Rose),
                contentPadding = PaddingValues(12.dp),
            ) { GlassIcon(GlassGlyph.Plus, modifier = Modifier.size(28.dp)) }
        }
        Spacer(Modifier.height(48.dp))
        SeaGlassButton(
            onClick = onCompose, modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth().height(86.dp),
            style = style.copy(seed = style.seed + 33), palette = selectedPalette(paletteIndex, SeaGlassPalette.Lagoon),
            contentPadding = PaddingValues(horizontal = 30.dp, vertical = 20.dp),
        ) {
            GlassIcon(GlassGlyph.Edit, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(18.dp))
            Text("Compose", fontSize = 24.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(46.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            listOf(4 to 61, 5 to 72, 2 to 86).forEach { (colorIndex, seed) ->
                SeaGlassButton(
                    onClick = { onPalette(colorIndex) }, modifier = Modifier.size(64.dp)
                        .semantics { contentDescription = "Choose ${paletteNames[colorIndex]} glass" },
                    style = style.copy(seed = style.seed + seed), palette = selectedPalette(paletteIndex, palettes[colorIndex - 1]),
                    shape = SeaGlassShape.Disc, contentPadding = PaddingValues(0.dp),
                ) {}
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ShapeStudy(style: SeaGlassStyle, paletteIndex: Int) {
    Column(Modifier.fillMaxWidth().padding(top = 34.dp), verticalArrangement = Arrangement.spacedBy(30.dp)) {
        listOf(SeaGlassShape.Rounded, SeaGlassShape.Capsule, SeaGlassShape.Disc).forEachIndexed { row, shape ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                repeat(3) { column ->
                    val seed = style.seed + row * 31 + column * 13
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        SeaGlassSurface(
                            Modifier.size(width = if (shape == SeaGlassShape.Disc) 64.dp else 76.dp, height = 64.dp),
                            style = style.copy(seed = seed), palette = selectedPalette(paletteIndex, palettes[(row + column) % palettes.size]),
                            shape = shape,
                        ) {}
                        Spacer(Modifier.height(20.dp))
                        Text("${shape.name.lowercase()} / $seed", fontSize = 9.sp, color = Muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextStudy(style: SeaGlassStyle, paletteIndex: Int, saved: Boolean, onSave: () -> Unit, onCompose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 28.dp)) {
        Text("fieldnotes", fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 22.sp, color = Ink)
        Spacer(Modifier.height(34.dp))
        Eyebrow("A NOTE TO SELF")
        Spacer(Modifier.height(12.dp))
        Text("Collect small\nmoments.", fontSize = 36.sp, lineHeight = 42.sp, fontFamily = FontFamily.Serif, color = Ink)
        Spacer(Modifier.height(18.dp))
        Text("The pale green piece on the shore.\nThe first coffee of the morning.\nA good idea, before it disappears.",
            fontSize = 14.sp, lineHeight = 24.sp, color = Muted)
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            SeaGlassButton(onClick = onSave, style = style.copy(seed = style.seed + 130),
                palette = selectedPalette(paletteIndex, SeaGlassPalette.Lagoon),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
                GlassIcon(if (saved) GlassGlyph.Check else GlassGlyph.Bookmark)
                Spacer(Modifier.width(8.dp))
                Text(if (saved) "Saved" else "Save note")
            }
            TextButton(onClick = onCompose) { Text("New note") }
        }
    }
}

private val palettes = listOf(SeaGlassPalette.Lagoon, SeaGlassPalette.Lilac, SeaGlassPalette.Rose, SeaGlassPalette.Amber, SeaGlassPalette.Mint)
private val paletteNames = listOf("Mix", "Lagoon", "Lilac", "Rose", "Amber", "Mint")
private fun selectedPalette(index: Int, fallback: SeaGlassPalette): SeaGlassPalette = palettes.getOrNull(index - 1) ?: fallback

private fun presetStyle(name: String, seed: Int): SeaGlassStyle = when (name) {
    "Tumbled" -> SeaGlassStyle(frost = .72f, grain = .68f, tumble = .055f, thickness = 9.dp, bulge = .80f,
        elevation = 7.dp, lightDegrees = 315f, transmission = .48f, seed = seed)
    "Quiet" -> SeaGlassStyle(frost = .38f, grain = .23f, tumble = .012f, thickness = 3.dp, bulge = .38f,
        elevation = 4.dp, lightDegrees = 315f, transmission = .66f, seed = seed)
    else -> SeaGlassStyle(seed = seed)
}

@Composable
private fun MaterialControls(
    style: SeaGlassStyle, onStyle: (SeaGlassStyle) -> Unit, preset: String, onPreset: (String) -> Unit,
    paletteIndex: Int, onPalette: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Color(0xfffbfcf8), RoundedCornerShape(18.dp))
        .border(1.dp, Border, RoundedCornerShape(18.dp)).padding(20.dp)) {
        Eyebrow("MAKE IT YOURS")
        Spacer(Modifier.height(8.dp))
        Text("Shape the material", color = Ink, fontSize = 25.sp, fontFamily = FontFamily.Serif)
        Spacer(Modifier.height(6.dp))
        Text("One recipe. Every component.", fontSize = 13.sp, color = Muted)
        Spacer(Modifier.height(24.dp))
        Text("Start with a feeling", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ink)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Reference", "Tumbled", "Quiet").forEach { name ->
                FilterChip(selected = preset == name, onClick = { onPreset(name) }, label = { Text(name, fontSize = 11.sp) })
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Glass color", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ink)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            paletteNames.forEachIndexed { index, name ->
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { onPalette(index) }.semantics { contentDescription = "Choose $name palette" }) {
                    Box(Modifier.size(32.dp).border(if (index == paletteIndex) 2.dp else 1.dp,
                        if (index == paletteIndex) Ink else Border, CircleShape).padding(4.dp)
                        .background(if (index == 0) Color(0xffe6e4dc) else palettes[index - 1].body, CircleShape)) {
                        if (index == 0) Text("∴", modifier = Modifier.align(Alignment.Center), color = Muted, fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(name, fontSize = 9.sp, color = if (index == paletteIndex) Ink else Muted)
                }
            }
        }
        ControlHeading("The surface")
        RecipeSlider("Frost", percent(style.frost), "Clear color → cloudy, milky glass", style.frost) { onStyle(style.copy(frost = it)) }
        RecipeSlider("Fine grain", percent(style.grain), "Tiny traces of a life in the sand", style.grain) { onStyle(style.copy(grain = it)) }
        ControlHeading("The shape")
        RecipeSlider("Tumble", "${(style.tumble * 1000).roundToInt() / 10f}%", "Precise edges → gently worn contours",
            style.tumble, 0f.. .065f) { onStyle(style.copy(tumble = it)) }
        RecipeSlider("Bulge", percent(style.bulge), "Flatter fragment → full, rounded pebble", style.bulge) { onStyle(style.copy(bulge = it)) }
        RecipeSlider("Thickness", "${style.thickness.value.roundToInt()} dp", "Thin fragment → deeper rounded body",
            style.thickness.value, 0f..14f) { onStyle(style.copy(thickness = it.dp)) }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("A different piece", fontSize = 12.sp, color = Ink)
                Text("Shape seed ${style.seed}", fontSize = 10.sp, color = Muted)
            }
            TextButton(onClick = { onStyle(style.copy(seed = if (style.seed > Int.MAX_VALUE - 137) 42 else style.seed + 137)) },
                contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Tumble again", fontSize = 12.sp) }
        }
        ControlHeading("The light")
        RecipeSlider("Lift", "${style.elevation.value.roundToInt()} dp", "Resting on the page → floating above it",
            style.elevation.value, 0f..18f) { onStyle(style.copy(elevation = it.dp)) }
        RecipeSlider("Light transmission", percent(style.transmission), "Colored body → luminous edge and light below",
            style.transmission) { onStyle(style.copy(transmission = it)) }
        RecipeSlider("Surface light angle", "${style.lightDegrees.roundToInt()}°", "Moves curved reflections; shadows stay below",
            style.lightDegrees, 0f..360f) { onStyle(style.copy(lightDegrees = it)) }
    }
}

private fun percent(value: Float) = "${(value * 100).roundToInt()}%"

@Composable
private fun ControlHeading(title: String) {
    Spacer(Modifier.height(22.dp))
    HorizontalDivider(color = Border)
    Spacer(Modifier.height(18.dp))
    Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ink)
    Spacer(Modifier.height(18.dp))
}

@Composable
private fun RecipeSlider(label: String, valueLabel: String, hint: String, value: Float,
    range: ClosedFloatingPointRange<Float> = 0f..1f, onValue: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 12.sp, color = Ink)
            Text(valueLabel, fontSize = 11.sp, color = Muted)
        }
        Slider(value = value.coerceIn(range), onValueChange = onValue, valueRange = range,
            modifier = Modifier.fillMaxWidth().height(38.dp).semantics { contentDescription = label })
        Text(hint, color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
    }
}

@Composable
private fun Eyebrow(text: String) {
    Text(text, color = Muted, fontSize = 9.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun BrandMark() {
    Box(Modifier.size(34.dp)) {
        Box(Modifier.size(20.dp).align(Alignment.TopStart).background(Color(0xffc8ded0), RoundedCornerShape(8.dp)))
        Box(Modifier.size(20.dp).align(Alignment.CenterEnd).background(Color(0xffd4d7ec), RoundedCornerShape(8.dp)))
        Box(Modifier.size(18.dp).align(Alignment.BottomStart).background(Color(0xffe8d9c9), RoundedCornerShape(8.dp)))
    }
}

private enum class GlassGlyph { Share, Message, Download, Edit, More, Plus, Bookmark, Check }

@Composable
private fun ActionIcon(glyph: GlassGlyph, label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    IconButton(onClick, modifier = modifier.height(48.dp).semantics { contentDescription = label }) {
        GlassIcon(glyph, color = color)
    }
}

/** Small original outline glyphs, so the sample needs no platform icon or font dependency. */
@Composable
private fun GlassIcon(glyph: GlassGlyph, modifier: Modifier = Modifier.size(22.dp), color: Color = LocalContentColor.current) {
    Canvas(modifier) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(x1, y1), Offset(x2, y2),
                strokeWidth = 2f, cap = StrokeCap.Round)
            fun path(vararg points: Pair<Float, Float>, closed: Boolean = false) {
                val outline = Path().apply {
                    moveTo(points[0].first, points[0].second)
                    points.drop(1).forEach { (x, y) -> lineTo(x, y) }
                    if (closed) close()
                }
                drawPath(outline, color, style = Stroke(2f, cap = StrokeCap.Round))
            }
            when (glyph) {
                GlassGlyph.Share -> {
                    line(6f, 12f, 18f, 5f); line(6f, 12f, 18f, 19f)
                    listOf(Offset(5f, 12f), Offset(19f, 4f), Offset(19f, 20f)).forEach { drawCircle(color, 2.5f, it) }
                }
                GlassGlyph.Message -> {
                    path(3f to 4f, 21f to 4f, 21f to 18f, 9f to 18f, 3f to 22f, closed = true)
                    line(7f, 9f, 17f, 9f); line(7f, 13f, 17f, 13f)
                }
                GlassGlyph.Download -> {
                    line(12f, 3f, 12f, 15f); path(7f to 11f, 12f to 16f, 17f to 11f)
                    path(3f to 17f, 3f to 21f, 21f to 21f, 21f to 17f)
                }
                GlassGlyph.Edit -> {
                    path(4f to 16f, 16f to 4f, 21f to 9f, 9f to 21f, 3f to 22f, closed = true)
                    line(14f, 6f, 19f, 11f)
                }
                GlassGlyph.More -> listOf(5f, 12f, 19f).forEach { drawCircle(color, 1.8f, Offset(12f, it)) }
                GlassGlyph.Plus -> { line(12f, 4f, 12f, 20f); line(4f, 12f, 20f, 12f) }
                GlassGlyph.Bookmark -> path(6f to 3f, 18f to 3f, 18f to 21f, 12f to 17f, 6f to 21f, closed = true)
                GlassGlyph.Check -> path(4f to 12f, 9f to 17f, 20f to 6f)
            }
        }
    }
}
