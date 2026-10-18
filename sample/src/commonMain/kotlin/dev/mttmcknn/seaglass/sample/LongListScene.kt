package dev.mttmcknn.seaglass.sample

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mttmcknn.seaglass.SeaGlassButton
import dev.mttmcknn.seaglass.SeaGlassPalette
import dev.mttmcknn.seaglass.SeaGlassShape
import dev.mttmcknn.seaglass.SeaGlassStyle
import dev.mttmcknn.seaglass.SeaGlassSurface
import kotlinx.coroutines.launch

private val ListInk = Color(0xff283e37)
private val ListMuted = Color(0xff65796e)
private val ListPaper = Color(0xfff4f5f0)
private val ListPalettes = listOf(SeaGlassPalette.Mint, SeaGlassPalette.Lagoon,
    SeaGlassPalette.Amber, SeaGlassPalette.Lilac, SeaGlassPalette.Rose)

/** Read-only sample diagnostics. No renderer or application needs to consume this. */
@Immutable
data class LongListSnapshot(
    val datasetId: String = "coastal-288-v1",
    val active: Boolean = false,
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0,
    val visibleKeys: List<String> = emptyList(),
    val isScrollInProgress: Boolean = false,
)

object SeaGlassLongListDiagnostics {
    // Only the observation coroutine writes this; reading it does not drive composition.
    var snapshot: LongListSnapshot by mutableStateOf(LongListSnapshot())
        internal set
}

private enum class CollectionKind { Note, Listening, Actions, Walk, Pebbles, Reminder }

@Immutable
private data class CollectionEntry(
    val index: Int,
    val kind: CollectionKind,
    val title: String,
    val detail: String,
    val seed: Int,
    val variant: Int,
    val paletteIndex: Int,
) {
    val key: String get() = "collection-$index"
    val style: SeaGlassStyle get() = SeaGlassStyle.Reference.copy(seed = seed)
}

private val Places = listOf("Pebble Cove", "North Shore", "Stillwater Bay", "Cedar Point",
    "Little Harbor", "Silver Sands", "The Headland", "Driftwood Beach")
private val Tracks = listOf("Slow current", "The water is quiet", "A softer morning", "Light on the shore")

// 48 groups of six distinct item families. Three groups in four reuse two exact
// size/seed variants; the fourth uses unique seeds. Pigment rotates independently.
// There are no clocks, random calls, network content, or geometry preparation here.
private val CollectionEntries = List(288) { index ->
    val group = index / 6
    val kind = CollectionKind.entries[index % 6]
    val place = Places[group % Places.size]
    val variant = group % 2
    CollectionEntry(
        index = index,
        kind = kind,
        title = when (kind) {
            CollectionKind.Note -> "A moment at $place"
            CollectionKind.Listening -> Tracks[group % Tracks.size]
            CollectionKind.Actions -> "Make a little room"
            CollectionKind.Walk -> "$place loop"
            CollectionKind.Pebbles -> "Colors from the coast"
            CollectionKind.Reminder -> "Take the long way home"
        },
        detail = when (kind) {
            CollectionKind.Note -> "Pale glass, salt in the air, and nowhere to hurry. A small thing worth keeping."
            CollectionKind.Listening -> "Sora Vale  ·  ${3 + group % 3}:24"
            CollectionKind.Actions -> "A few good things for today."
            CollectionKind.Walk -> "${2 + group % 5}.${group % 9} km  ·  ${25 + group % 36} min  ·  Easy pace"
            CollectionKind.Pebbles -> "$place  ·  Three finds"
            CollectionKind.Reminder -> "A little fresh air before the day is done."
        },
        seed = if (group % 4 == 3) 10000 + index * 37 else 1000 + kind.ordinal * 31 + variant,
        variant = variant,
        paletteIndex = (group + kind.ordinal) % ListPalettes.size,
    )
}

/** A standalone, viewport-bounded list; never nested in the playground scroll view. */
@Composable
internal fun LongListScene(onBack: () -> Unit, enableScrollDiagnostics: Boolean = false) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var openEntry by rememberSaveable { mutableStateOf<Int?>(null) }
    if (enableScrollDiagnostics) {
        LaunchedEffect(listState) {
            snapshotFlow {
                LongListSnapshot(
                    active = true,
                    firstVisibleItemIndex = listState.firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                    visibleKeys = listState.layoutInfo.visibleItemsInfo.map { it.key.toString() },
                    isScrollInProgress = listState.isScrollInProgress,
                )
            }.collect { SeaGlassLongListDiagnostics.snapshot = it }
        }
        DisposableEffect(Unit) {
            onDispose {
                SeaGlassLongListDiagnostics.snapshot = SeaGlassLongListDiagnostics.snapshot.copy(active = false)
            }
        }
    }
    Column(
        Modifier.fillMaxSize().background(ListPaper).safeDrawingPadding().testTag("long-list-screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("long-list-back")) { Text("Back") }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text("Collected", fontFamily = FontFamily.Serif, fontSize = 25.sp, color = ListInk)
                Text("288 small moments", fontSize = 11.sp, color = ListMuted)
            }
            TextButton(
                onClick = { scope.launch { listState.scrollToItem(0) } },
                modifier = Modifier.testTag("long-list-top").semantics { contentDescription = "Back to top" },
            ) { Text("Top") }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().weight(1f).testTag("long-list-scroll"),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 64.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            item(key = "collection-intro", contentType = "intro") {
                Column(Modifier.padding(bottom = 4.dp).testTag("long-list-intro")) {
                    Text("KEEP WHAT MOVES YOU", fontSize = 10.sp, letterSpacing = 1.8.sp, color = ListMuted)
                    Spacer(Modifier.height(8.dp))
                    Text("Days by the water.", fontFamily = FontFamily.Serif, fontSize = 32.sp,
                        lineHeight = 38.sp, color = ListInk, modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.height(8.dp))
                    Text("Notes, favorite sounds, and paths worth taking again.",
                        fontSize = 13.sp, lineHeight = 20.sp, color = ListMuted)
                }
            }
            items(CollectionEntries, key = { it.key }, contentType = { it.kind }) { entry ->
                CollectionRow(entry, onOpen = { openEntry = entry.index })
            }
            item(key = "collection-end", contentType = "end") {
                Text("A good place to begin again.", fontFamily = FontFamily.Serif,
                    fontSize = 20.sp, color = ListMuted, modifier = Modifier.padding(vertical = 16.dp))
            }
        }
    }
    openEntry?.let { index ->
        val entry = CollectionEntries[index]
        AlertDialog(
            onDismissRequest = { openEntry = null },
            title = { Text(entry.title, fontFamily = FontFamily.Serif) },
            text = { Text("${entry.detail}\n\nFrom the coastal collection, moment ${index + 1}.") },
            confirmButton = { TextButton(onClick = { openEntry = null }) { Text("Done") } },
        )
    }
}

@Composable
private fun CollectionRow(entry: CollectionEntry, onOpen: () -> Unit) {
    var saved by rememberSaveable(entry.key) { mutableStateOf(false) }
    var playing by rememberSaveable(entry.key) { mutableStateOf(false) }
    val palette = ListPalettes[entry.paletteIndex]
    val style = remember(entry.seed) { entry.style }
    Column(Modifier.fillMaxWidth().testTag("long-list-row-${entry.index}")) {
        when (entry.kind) {
            CollectionKind.Note -> SeaGlassSurface(
                Modifier.fillMaxWidth().heightIn(min = if (entry.variant == 0) 166.dp else 186.dp),
                style = style, palette = palette,
            ) {
                // Leave room for the rounded shoulder, including longer titles.
                Column(Modifier.fillMaxWidth().padding(horizontal = 44.dp, vertical = 36.dp)) {
                    Text("FIELDNOTE  /  ${(entry.index + 1).toString().padStart(3, '0')}",
                        fontSize = 9.sp, letterSpacing = 1.2.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Text(entry.title, fontFamily = FontFamily.Serif, fontSize = 23.sp, lineHeight = 29.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(entry.detail, fontSize = 12.sp, lineHeight = 19.sp)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onOpen, modifier = Modifier.testTag("long-list-open-${entry.index}")) {
                            Text("Read note", color = palette.ink)
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = { saved = !saved },
                            modifier = Modifier.testTag("long-list-save-${entry.index}").semantics {
                                contentDescription = if (saved) "Unsave ${entry.title}" else "Save ${entry.title}"
                                stateDescription = if (saved) "Saved" else "Not saved"
                            },
                        ) { CollectionIcon(if (saved) CollectionGlyph.Check else CollectionGlyph.Bookmark, palette.ink) }
                    }
                }
            }
            CollectionKind.Listening -> Row(
                Modifier.fillMaxWidth().heightIn(min = 92.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SeaGlassButton(
                    onClick = { playing = !playing }, modifier = Modifier.size(if (entry.variant == 0) 68.dp else 76.dp)
                        .testTag("long-list-play-${entry.index}").semantics {
                            contentDescription = if (playing) "Pause ${entry.title}" else "Play ${entry.title}"
                            stateDescription = if (playing) "Playing" else "Paused"
                        },
                    style = style, palette = palette, shape = SeaGlassShape.Disc, contentPadding = PaddingValues(0.dp),
                ) { CollectionIcon(if (playing) CollectionGlyph.Pause else CollectionGlyph.Play, palette.ink) }
                Column(Modifier.weight(1f)) {
                    Text("A LITTLE LISTENING", color = ListMuted, fontSize = 9.sp, letterSpacing = 1.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(entry.title, fontFamily = FontFamily.Serif, fontSize = 22.sp, lineHeight = 27.sp, color = ListInk)
                    Spacer(Modifier.height(4.dp))
                    Text(if (playing) "Playing · ${entry.detail}" else entry.detail, color = ListMuted, fontSize = 11.sp)
                }
            }
            CollectionKind.Actions -> {
                CollectionHeading(entry)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("Breathe", "Wander", "Notice").forEachIndexed { index, title ->
                        // Deliberately identical geometry with independent pigment and content.
                        SeaGlassButton(
                            onClick = onOpen, modifier = Modifier.weight(1f).heightIn(min = if (entry.variant == 0) 52.dp else 60.dp)
                                .testTag("long-list-action-${entry.index}-$index"),
                            style = style, palette = ListPalettes[(entry.paletteIndex + index) % ListPalettes.size],
                            shape = SeaGlassShape.Capsule, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 14.dp),
                        ) { Text(title, fontSize = 12.sp) }
                    }
                }
            }
            CollectionKind.Walk -> SeaGlassSurface(
                Modifier.fillMaxWidth().heightIn(min = if (entry.variant == 0) 132.dp else 154.dp),
                style = style, palette = palette,
            ) {
                Row(Modifier.fillMaxWidth().padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("OUTSIDE, FOR A WHILE", fontSize = 9.sp, letterSpacing = 1.sp)
                        Spacer(Modifier.height(9.dp))
                        Text(entry.title, fontFamily = FontFamily.Serif, fontSize = 24.sp, lineHeight = 29.sp)
                        Spacer(Modifier.height(7.dp))
                        Text(entry.detail, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                    IconButton(onClick = onOpen, modifier = Modifier.testTag("long-list-open-${entry.index}")
                        .semantics { contentDescription = "View ${entry.title}" }) {
                        CollectionIcon(CollectionGlyph.Arrow, palette.ink)
                    }
                }
            }
            CollectionKind.Pebbles -> {
                CollectionHeading(entry)
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically) {
                    val colors = listOf("Seafoam", "Dawn", "Tide")
                    colors.forEachIndexed { index, name ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            SeaGlassButton(
                                onClick = onOpen,
                                modifier = Modifier.size(if (entry.variant == 0) 64.dp else 72.dp)
                                    .testTag("long-list-pebble-${entry.index}-$index")
                                    .semantics { contentDescription = "View $name find from ${Places[(entry.index / 6) % Places.size]}" },
                                style = style, palette = ListPalettes[(entry.paletteIndex + index) % ListPalettes.size],
                                shape = SeaGlassShape.Disc, contentPadding = PaddingValues(0.dp),
                            ) { CollectionIcon(CollectionGlyph.Wave, LocalContentColor.current) }
                            Spacer(Modifier.height(12.dp))
                            Text(name, fontSize = 11.sp, color = ListMuted)
                        }
                    }
                }
            }
            CollectionKind.Reminder -> Row(Modifier.fillMaxWidth().heightIn(min = 92.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.title, fontFamily = FontFamily.Serif, fontSize = 22.sp, lineHeight = 28.sp, color = ListInk)
                    Spacer(Modifier.height(5.dp))
                    Text(entry.detail, color = ListMuted, fontSize = 12.sp, lineHeight = 18.sp)
                }
                SeaGlassButton(onClick = { saved = !saved }, modifier = Modifier.size(if (entry.variant == 0) 56.dp else 64.dp)
                    .testTag("long-list-reminder-${entry.index}").semantics {
                        contentDescription = if (saved) "Mark ${entry.title} incomplete" else "Complete ${entry.title}"
                        stateDescription = if (saved) "Completed" else "Not completed"
                    }, style = style, palette = palette, contentPadding = PaddingValues(0.dp)) {
                    CollectionIcon(if (saved) CollectionGlyph.Check else CollectionGlyph.Plus, palette.ink)
                }
            }
        }
    }
}

@Composable
private fun CollectionHeading(entry: CollectionEntry) {
    Text(entry.title, fontFamily = FontFamily.Serif, fontSize = 23.sp, lineHeight = 29.sp, color = ListInk)
    Spacer(Modifier.height(5.dp))
    Text(entry.detail, fontSize = 12.sp, lineHeight = 18.sp, color = ListMuted)
}

private enum class CollectionGlyph { Bookmark, Play, Pause, Check, Arrow, Wave, Plus }

@Composable
private fun CollectionIcon(glyph: CollectionGlyph, color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * .065f, cap = StrokeCap.Round)
        val path = Path()
        when (glyph) {
            CollectionGlyph.Bookmark -> {
                path.moveTo(w * .29f, h * .2f); path.lineTo(w * .71f, h * .2f)
                path.lineTo(w * .71f, h * .82f); path.lineTo(w * .5f, h * .66f)
                path.lineTo(w * .29f, h * .82f); path.close()
            }
            CollectionGlyph.Play -> {
                path.moveTo(w * .34f, h * .23f); path.lineTo(w * .77f, h * .5f)
                path.lineTo(w * .34f, h * .77f); path.close()
                drawPath(path, color); return@Canvas
            }
            CollectionGlyph.Pause -> {
                drawLine(color, Offset(w * .36f, h * .25f), Offset(w * .36f, h * .75f), w * .12f, StrokeCap.Round)
                drawLine(color, Offset(w * .64f, h * .25f), Offset(w * .64f, h * .75f), w * .12f, StrokeCap.Round)
                return@Canvas
            }
            CollectionGlyph.Check -> {
                path.moveTo(w * .23f, h * .5f); path.lineTo(w * .43f, h * .7f); path.lineTo(w * .78f, h * .3f)
            }
            CollectionGlyph.Arrow -> {
                path.moveTo(w * .2f, h * .5f); path.lineTo(w * .8f, h * .5f)
                path.moveTo(w * .56f, h * .25f); path.lineTo(w * .81f, h * .5f); path.lineTo(w * .56f, h * .75f)
            }
            CollectionGlyph.Wave -> repeat(2) { line ->
                val y = h * (.38f + line * .25f)
                path.moveTo(w * .16f, y)
                path.cubicTo(w * .35f, y - h * .24f, w * .65f, y + h * .24f, w * .84f, y)
            }
            CollectionGlyph.Plus -> {
                path.moveTo(w * .23f, h * .5f); path.lineTo(w * .77f, h * .5f)
                path.moveTo(w * .5f, h * .23f); path.lineTo(w * .5f, h * .77f)
            }
        }
        drawPath(path, color, style = stroke)
    }
}
