package dev.mttmcknn.seaglass.sample

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
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
import dev.mttmcknn.seaglass.SeaGlassTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val Ink = Color(0xff283e37)
private val Muted = Color(0xff74847c)
private val Page = Color(0xfff4f5f0)
private val Border = Color(0xffe2e6dd)

// Store primitives so the current material survives rotation and activity recreation.
private val RecipeSaver = listSaver<SeaGlassStyle, Any>(
    save = { listOf(it.frost, it.grain, it.tumble, it.thickness.value, it.bulge,
        it.elevation.value, it.lightDegrees, it.transmission, it.seed) },
    restore = { SeaGlassStyle(frost = it[0] as Float, grain = it[1] as Float,
        tumble = it[2] as Float, thickness = (it[3] as Float).dp, bulge = it[4] as Float,
        elevation = (it[5] as Float).dp, lightDegrees = it[6] as Float,
        transmission = it[7] as Float, seed = it[8] as Int) },
)

private val PlaygroundColors = lightColorScheme(
    primary = Color(0xff507864),
    onPrimary = Color.White,
    secondaryContainer = Color(0xffe3ece2),
    onSecondaryContainer = Ink,
    surface = Page,
    background = Page,
    onSurface = Ink,
    onBackground = Ink,
)
private val FallbackMaterialColors = lightColorScheme()
private val LocalMaterialYouEnabled = compositionLocalOf { false }

/**
 * Live glass examples. Android hosts supply wallpaper colors; other hosts use
 * the fallback Material scheme. The showcase's light canvas uses a light scheme.
 */
@Composable
fun SeaGlassPlayground(
    materialColorScheme: ColorScheme = FallbackMaterialColors,
    materialColorSource: String = "Material theme colors",
    initialScene: String? = null,
    enableScrollDiagnostics: Boolean = false,
) {
    var materialYou by rememberSaveable { mutableStateOf(false) }
    var longList by rememberSaveable { mutableStateOf(initialScene == "long-list") }
    MaterialTheme(colorScheme = if (materialYou) materialColorScheme else PlaygroundColors) {
        CompositionLocalProvider(LocalMaterialYouEnabled provides materialYou) {
            var style by rememberSaveable(stateSaver = RecipeSaver) { mutableStateOf(SeaGlassStyle()) }
            var paletteIndex by rememberSaveable { mutableIntStateOf(0) }
            var preset by rememberSaveable { mutableStateOf("Reference") }
            var sceneName by rememberSaveable { mutableStateOf(SeaGlassScene.Components.name) }
            val scene = SeaGlassScene.valueOf(sceneName)
            var previewOnly by rememberSaveable { mutableStateOf(false) }
            var saved by rememberSaveable { mutableStateOf(false) }
            var showComposer by rememberSaveable { mutableStateOf(false) }
            var note by rememberSaveable { mutableStateOf("") }
            val previewToggleFocus = remember { FocusRequester() }
            var restorePreviewFocus by remember { mutableStateOf(false) }
            val previewScroll = rememberScrollState()
            val controlsScroll = rememberScrollState()
            val compactScroll = rememberScrollState()
            val snackbar = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            val report: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) }; Unit }
            val updateStyle: (SeaGlassStyle) -> Unit = { style = it; preset = "" }
            val reset = { style = SeaGlassStyle(); paletteIndex = 0; preset = "Reference"; materialYou = false }
            val selectPalette: (Int) -> Unit = { paletteIndex = it; materialYou = false }
            val toggleSaved = { saved = !saved }
            val composeNote = { showComposer = true }
            val showcaseActions = remember(selectPalette, toggleSaved, composeNote, report) {
                ShowcaseActions(selectPalette, toggleSaved, composeNote, report)
            }
            val movableShowcase = remember {
                movableContentOf<ShowcaseState, ShowcaseActions> { state, actions ->
                    ShowcaseContent(state, actions)
                }
            }
            val showcase: @Composable (SeaGlassScene) -> Unit = { selectedScene ->
                movableShowcase(ShowcaseState(selectedScene, paletteIndex, saved), showcaseActions)
            }

            val openPreview = {
                previewOnly = true
                report("Double-tap the background or press Escape to return to controls")
            }
            val closePreview = {
                previewOnly = false
                restorePreviewFocus = true
            }
            LaunchedEffect(previewOnly, restorePreviewFocus) {
                if (!previewOnly && restorePreviewFocus) {
                    previewToggleFocus.requestFocus()
                    restorePreviewFocus = false
                }
            }
            Box(Modifier.fillMaxSize().background(Page)) {
                if (longList) {
                    LongListScene(onBack = { longList = false }, enableScrollDiagnostics = enableScrollDiagnostics)
                } else if (previewOnly) {
                    ShowcasePreview(scene, style, paletteIndex, showcase, onExit = closePreview)
                } else {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
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
                            TextButton(onClick = { longList = true }, modifier = Modifier.testTag("playground-long-list")) {
                                Text("Long list")
                            }
                            TextButton(onClick = reset) { Text("Reset") }
                        }
                        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                            // Use the available app window, including live multi-window resizing.
                            val wide = maxWidth >= 600.dp
                            if (wide) {
                                val gutter = if (maxWidth >= 960.dp) 28.dp else 16.dp
                                val controlsWidth = (maxWidth * .34f).coerceIn(240.dp, 326.dp)
                                Row(Modifier.fillMaxSize().padding(gutter), horizontalArrangement = Arrangement.spacedBy(gutter)) {
                                    Column(Modifier.weight(1f).fillMaxHeight().testTag("playground-preview")
                                        .verticalScroll(previewScroll)) {
                                        PlaygroundPreview(style, paletteIndex, scene, { sceneName = it.name }, selectPalette,
                                            composeNote, saved, toggleSaved, report, openPreview, showcase, materialYou, { materialYou = it },
                                            materialColorSource, previewToggleFocus, compactIntro = true)
                                    }
                                    Column(Modifier.width(controlsWidth).fillMaxHeight().testTag("playground-controls")
                                        .verticalScroll(controlsScroll)) {
                                        MaterialControls(style, updateStyle, preset, { name ->
                                            style = presetStyle(name, style.seed); preset = name
                                        }, paletteIndex, selectPalette)
                                    }
                                }
                            } else {
                                Column(
                                    Modifier.fillMaxSize().testTag("playground-stacked")
                                        .verticalScroll(compactScroll).padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(28.dp),
                                ) {
                                    PlaygroundPreview(style, paletteIndex, scene, { sceneName = it.name }, selectPalette,
                                        composeNote, saved, toggleSaved, report, openPreview, showcase, materialYou, { materialYou = it }, materialColorSource, previewToggleFocus)
                                    MaterialControls(style, updateStyle, preset, { name ->
                                        style = presetStyle(name, style.seed); preset = name
                                    }, paletteIndex, selectPalette)
                                }
                            }
                        }
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp))
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
}

@Composable
private fun PlaygroundPreview(
    style: SeaGlassStyle,
    paletteIndex: Int,
    scene: SeaGlassScene,
    onScene: (SeaGlassScene) -> Unit,
    onPalette: (Int) -> Unit,
    onCompose: () -> Unit,
    saved: Boolean,
    onSave: () -> Unit,
    report: (String) -> Unit,
    onPreviewOnly: () -> Unit,
    showcase: @Composable (SeaGlassScene) -> Unit,
    materialYou: Boolean,
    onMaterialYou: (Boolean) -> Unit,
    materialColorSource: String,
    previewToggleFocus: FocusRequester,
    compactIntro: Boolean = false,
) {
    // The controls provide subtree defaults. Individual pieces only override identity,
    // and the mixed-pigment view overrides colors where the specimen calls for it.
    SeaGlassTheme(style = style, palette = scenePalette(scene, paletteIndex)) {
        Column(Modifier.fillMaxWidth()) {
            if (compactIntro || scene.ordinal >= SeaGlassScene.Music.ordinal) {
                Text("Live preview", fontSize = 26.sp, fontFamily = FontFamily.Serif, color = Ink)
                Spacer(Modifier.height(6.dp))
                Text("Shape the glass. See it change.", fontSize = 13.sp, color = Muted)
                Spacer(Modifier.height(16.dp))
            } else {
                Eyebrow("SMOOTHED BY THE SEA. SHAPED BY YOU.")
                Spacer(Modifier.height(14.dp))
                Text("A little less perfect.", fontSize = 34.sp, lineHeight = 40.sp, fontFamily = FontFamily.Serif, color = Ink)
                Text("A little more character.", fontSize = 34.sp, lineHeight = 40.sp, fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic, color = Color(0xff728879))
                Spacer(Modifier.height(14.dp))
                Text("Frosted color, softened edges, a little weight.\nFind the feel of your next interface.",
                    fontSize = 14.sp, lineHeight = 22.sp, color = Muted)
                Spacer(Modifier.height(28.dp))
            }
            Row(
                Modifier.fillMaxWidth().testTag("playground-material-you")
                    .toggleable(value = materialYou, role = Role.Switch, onValueChange = onMaterialYou)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Material You", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink)
                    Text(if (materialYou) materialColorSource else "Use your Material colors for the glass",
                        fontSize = 12.sp, color = Muted)
                }
                Switch(checked = materialYou, onCheckedChange = null)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                SceneSelector(scene, onScene, Modifier.weight(1f))
                TextButton(onClick = onPreviewOnly, modifier = Modifier.testTag("playground-preview-toggle")
                    .focusRequester(previewToggleFocus)) {
                    Text("Preview only", fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            if (scene.ordinal >= SeaGlassScene.Music.ordinal) {
                Box(Modifier.widthIn(max = 440.dp).fillMaxWidth().align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(24.dp)).background(Color.White)
                    .border(1.dp, Border, RoundedCornerShape(24.dp))) {
                    showcase(scene)
                }
                Spacer(Modifier.height(24.dp))
            } else {
                Column(
                    Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(20.dp))
                        .border(1.dp, Border, RoundedCornerShape(20.dp)).padding(if (compactIntro) 12.dp else 20.dp),
                ) {
                    Eyebrow(when (scene) {
                        SeaGlassScene.ShapeStudy -> "02 / NO TWO ALIKE"
                        SeaGlassScene.Fieldnotes -> "03 / A LITTLE EVERYDAY MAGIC"
                        else -> "01 / THE ORIGINAL FEEL"
                    })
                    when (scene) {
                        SeaGlassScene.ShapeStudy -> ShapeStudy(paletteIndex)
                        SeaGlassScene.Fieldnotes -> ContextStudy(saved, onSave, onCompose)
                        else -> ComponentStudy(paletteIndex, onPalette, onCompose, report)
                    }
                    Text(if (scene == SeaGlassScene.ShapeStudy) "Same recipe. Different histories." else "A soft surface. A solid little object.",
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 22.dp, bottom = 8.dp),
                        fontSize = 12.sp, color = Muted, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic)
                }
                Spacer(Modifier.height(28.dp))
                Eyebrow("SMALL, EVERYDAY OBJECTS")
                Spacer(Modifier.height(18.dp))
                SeaGlassButton(
                    onClick = onSave, modifier = Modifier.height(62.dp).align(Alignment.CenterHorizontally),
                    style = SeaGlassTheme.componentStyle.withSeed(style.seed + 144),
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
    }
}

@Composable
private fun scenePalette(scene: SeaGlassScene, paletteIndex: Int): SeaGlassPalette =
    if (LocalMaterialYouEnabled.current) SeaGlassPalette.materialTheme() else palettes.getOrNull(paletteIndex - 1) ?: when (scene) {
        SeaGlassScene.Music -> SeaGlassPalette.Mint
        SeaGlassScene.Phone -> SeaGlassPalette.Rose
        SeaGlassScene.Chat -> SeaGlassPalette.Lilac
        else -> SeaGlassPalette.Lagoon
    }

@Composable
private fun SceneSelector(scene: SeaGlassScene, onScene: (SeaGlassScene) -> Unit, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()
            .testTag("playground-scene-selector").semantics { contentDescription = "Choose an example interface" },
            shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
            Text(scene.title, modifier = Modifier.weight(1f), fontSize = 13.sp, color = Ink)
            Canvas(Modifier.size(12.dp)) {
                val path = Path().apply {
                    moveTo(size.width * .15f, size.height * .35f)
                    lineTo(size.width * .5f, size.height * .7f)
                    lineTo(size.width * .85f, size.height * .35f)
                }
                drawPath(path, Ink, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SeaGlassScene.entries.forEach { option ->
                DropdownMenuItem(text = { Text(option.title) }, onClick = { onScene(option); expanded = false })
            }
        }
    }
}

@Composable
private fun ShowcasePreview(scene: SeaGlassScene, style: SeaGlassStyle, paletteIndex: Int,
    showcase: @Composable (SeaGlassScene) -> Unit, onExit: () -> Unit) {
    val previewFocus = remember { FocusRequester() }
    // The clean preview has no chrome. Own initial focus so Escape also works
    // before a scene control is focused, and observe keys from its descendants.
    LaunchedEffect(previewFocus) { previewFocus.requestFocus() }
    Box(Modifier.fillMaxSize().safeDrawingPadding().testTag("playground-showcase")
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyUp && (event.key == Key.Escape || event.key == Key.Back)) {
                onExit()
                true
            } else false
        }
        .focusRequester(previewFocus).focusable()
        .pointerInput(onExit) { detectTapGestures(onDoubleTap = { onExit() }) }
        .semantics { customActions = listOf(CustomAccessibilityAction("Show controls") { onExit(); true }) }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.widthIn(max = 440.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp))
                .background(Color.White)) {
                SeaGlassTheme(style = style, palette = scenePalette(scene, paletteIndex)) {
                    showcase(scene)
                }
            }
        }
    }
}

/** A clean, native example interface for a gallery or screenshot, using the current theme. */
@Composable
fun SeaGlassShowcase(scene: SeaGlassScene, modifier: Modifier = Modifier) {
    ShowcaseContent(ShowcaseState(scene), ShowcaseActions.None, modifier)
}

private data class ShowcaseState(val scene: SeaGlassScene, val paletteIndex: Int = 0, val saved: Boolean = false)

private data class ShowcaseActions(
    val onPalette: (Int) -> Unit,
    val onSave: () -> Unit,
    val onCompose: () -> Unit,
    val report: (String) -> Unit,
) {
    companion object {
        val None = ShowcaseActions({}, {}, {}, {})
    }
}

/** Plain scene content shared by the playground and standalone galleries. */
@Composable
private fun ShowcaseContent(state: ShowcaseState, actions: ShowcaseActions, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth()) {
        when (state.scene) {
            SeaGlassScene.Music -> MusicPlayerScene()
            SeaGlassScene.Phone -> PhoneCallScene()
            SeaGlassScene.Email -> EmailScene()
            SeaGlassScene.Chat -> AiChatScene()
            else -> Column(Modifier.fillMaxWidth().padding(24.dp)) {
                when (state.scene) {
                    SeaGlassScene.ShapeStudy -> ShapeStudy(state.paletteIndex)
                    SeaGlassScene.Fieldnotes -> ContextStudy(state.saved, actions.onSave, actions.onCompose)
                    else -> ComponentStudy(state.paletteIndex, actions.onPalette, actions.onCompose, actions.report)
                }
            }
        }
    }
}

@Composable
private fun ComponentStudy(
    paletteIndex: Int, onPalette: (Int) -> Unit,
    onCompose: () -> Unit, report: (String) -> Unit,
) {
    val style = SeaGlassTheme.style
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(42.dp))
        BoxWithConstraints(Modifier.widthIn(max = 550.dp).fillMaxWidth()) {
            val compact = maxWidth < 350.dp
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    GlassToolbar(paletteIndex, onCompose, report, Modifier.weight(1f))
                    if (!compact) AddMomentButton(paletteIndex, onCompose)
                }
                Spacer(Modifier.height(48.dp))
                if (compact) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        ComposeMomentButton(onCompose, Modifier.weight(1f), compact = true)
                        AddMomentButton(paletteIndex, onCompose)
                    }
                } else {
                    ComposeMomentButton(onCompose, Modifier.widthIn(max = 280.dp).fillMaxWidth())
                }
            }
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
private fun ComposeMomentButton(onCompose: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    SeaGlassButton(
        onClick = onCompose, modifier = modifier.height(if (compact) 72.dp else 86.dp),
        style = SeaGlassTheme.componentStyle.withSeed(SeaGlassTheme.style.seed + 33),
        contentPadding = PaddingValues(horizontal = if (compact) 14.dp else 30.dp, vertical = 20.dp),
    ) {
        GlassIcon(GlassGlyph.Edit, modifier = Modifier.size(if (compact) 24.dp else 30.dp))
        Spacer(Modifier.width(if (compact) 10.dp else 18.dp))
        Text("Compose", fontSize = if (compact) 20.sp else 24.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AddMomentButton(paletteIndex: Int, onCompose: () -> Unit) {
    SeaGlassButton(
        onClick = onCompose, modifier = Modifier.size(64.dp).semantics { contentDescription = "Add a moment" },
        style = SeaGlassTheme.style.copy(seed = SeaGlassTheme.style.seed + 25),
        palette = selectedPalette(paletteIndex, SeaGlassPalette.Rose),
        contentPadding = PaddingValues(12.dp),
    ) { GlassIcon(GlassGlyph.Plus, modifier = Modifier.size(28.dp)) }
}

@Composable
private fun GlassToolbar(paletteIndex: Int, onCompose: () -> Unit, report: (String) -> Unit, modifier: Modifier) {
    val style = SeaGlassTheme.style
    var showMenu by remember { mutableStateOf(false) }
    val toolbarPalette = selectedPalette(paletteIndex, SeaGlassPalette.Lilac)
    SeaGlassSurface(
        modifier = modifier.height(66.dp), style = style.copy(seed = style.seed + 11),
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

}

@Composable
private fun ShapeStudy(paletteIndex: Int) {
    val style = SeaGlassTheme.style
    Column(Modifier.fillMaxWidth().padding(top = 34.dp), verticalArrangement = Arrangement.spacedBy(30.dp)) {
        listOf(SeaGlassShape.Rounded, SeaGlassShape.Capsule, SeaGlassShape.Disc).forEachIndexed { row, shape ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                repeat(3) { column ->
                    val seed = style.seed + row * 31 + column * 13
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        SeaGlassSurface(
                            Modifier.size(width = if (shape == SeaGlassShape.Disc) 64.dp else 76.dp, height = 64.dp)
                                .testTag("shape-study-${shape.name}-$column"),
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
private fun ContextStudy(saved: Boolean, onSave: () -> Unit, onCompose: () -> Unit) {
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
            SeaGlassButton(onClick = onSave, style = SeaGlassTheme.componentStyle.withSeed(SeaGlassTheme.style.seed + 130),
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
@Composable
private fun selectedPalette(index: Int, fallback: SeaGlassPalette): SeaGlassPalette =
    if (index == 0 && !LocalMaterialYouEnabled.current) fallback else SeaGlassTheme.palette

private fun presetStyle(name: String, seed: Int): SeaGlassStyle = when (name) {
    "Tumbled" -> SeaGlassStyle.Tumbled.copy(seed = seed)
    "Quiet" -> SeaGlassStyle.Quiet.copy(seed = seed)
    else -> SeaGlassStyle.Reference.copy(seed = seed)
}

@Composable
private fun MaterialControls(
    style: SeaGlassStyle, onStyle: (SeaGlassStyle) -> Unit, preset: String, onPreset: (String) -> Unit,
    paletteIndex: Int, onPalette: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Color(0xfffbfcf8), RoundedCornerShape(18.dp))
        .border(1.dp, Border, RoundedCornerShape(18.dp)).padding(16.dp)) {
        Eyebrow("MAKE IT YOURS")
        Spacer(Modifier.height(8.dp))
        Text("Shape the material", color = Ink, fontSize = 22.sp, fontFamily = FontFamily.Serif)
        Spacer(Modifier.height(6.dp))
        Text("One recipe. Every component.", fontSize = 13.sp, color = Muted)
        Spacer(Modifier.height(24.dp))
        Text("Start with a feeling", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ink)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Reference", "Tumbled", "Quiet").forEach { name ->
                FilterChip(selected = preset == name, onClick = { onPreset(name) }, label = { Text(name, fontSize = 11.sp) })
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Glass color", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ink)
        if (LocalMaterialYouEnabled.current) {
            Text("Choose a palette to leave Material You", fontSize = 11.sp, color = Muted)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.SpaceBetween) {
            paletteNames.forEachIndexed { index, name ->
                val selected = index == paletteIndex && !LocalMaterialYouEnabled.current
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = { onPalette(index) })
                        .semantics { contentDescription = "Choose $name palette" }) {
                    Box(Modifier.size(32.dp).border(if (selected) 2.dp else 1.dp,
                        if (selected) Ink else Border, CircleShape).padding(4.dp)
                        .background(if (index == 0) Color(0xffe6e4dc) else palettes[index - 1].body, CircleShape)) {
                        if (index == 0) Text("∴", modifier = Modifier.align(Alignment.Center), color = Muted, fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(name, fontSize = 9.sp, color = if (selected) Ink else Muted)
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
