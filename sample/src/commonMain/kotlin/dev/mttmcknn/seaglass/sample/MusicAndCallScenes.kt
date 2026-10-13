package dev.mttmcknn.seaglass.sample

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mttmcknn.seaglass.SeaGlassButton
import dev.mttmcknn.seaglass.SeaGlassPalette
import dev.mttmcknn.seaglass.SeaGlassShape
import dev.mttmcknn.seaglass.SeaGlassSurface
import dev.mttmcknn.seaglass.SeaGlassTheme

private val MediaInk = Color(0xff233c37)
private val MediaMuted = Color(0xff7b857c)
private val MediaPaper = Color(0xfffaf8f1)

/** A small, interactive music player. Only the transport and utility tray are glass. */
@Composable
internal fun MusicPlayerScene() {
    var playing by rememberSaveable { mutableStateOf(true) }
    var favorite by rememberSaveable { mutableStateOf(false) }
    var progress by rememberSaveable { mutableFloatStateOf(.34f) }
    var track by rememberSaveable { mutableIntStateOf(0) }
    var showQueue by rememberSaveable { mutableStateOf(false) }
    var repeating by rememberSaveable { mutableStateOf(false) }
    var headphones by rememberSaveable { mutableStateOf(true) }
    val tracks = listOf("Evening tide", "Soft horizon", "Where we drift")
    Column(
        Modifier.fillMaxWidth().background(MediaPaper).padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("SONDER", fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 3.sp,
                fontWeight = FontWeight.Bold, color = MediaInk)
            Spacer(Modifier.weight(1f))
            Text("NOW PLAYING", fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = 1.6.sp, color = MediaMuted)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(5.dp).background(Color(0xff78977d), CircleShape))
        }
        Spacer(Modifier.height(24.dp))
        SlowCurrentArtwork(Modifier.widthIn(max = 270.dp).fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tracks[track], color = MediaInk, fontFamily = FontFamily.Serif,
                    fontSize = 28.sp, lineHeight = 32.sp)
                Spacer(Modifier.height(5.dp))
                Text("Sora Vale  ·  Slow Current", color = MediaMuted, fontSize = 12.sp, lineHeight = 16.sp)
            }
            IconButton(onClick = { favorite = !favorite }) {
                MediaIcon(if (favorite) MediaGlyph.HeartFilled else MediaGlyph.Heart,
                    if (favorite) "Remove from favorites" else "Add to favorites",
                    tint = if (favorite) Color(0xffae736b) else MediaInk)
            }
        }
        Spacer(Modifier.height(15.dp))
        Slider(value = progress, onValueChange = { progress = it },
            modifier = Modifier.fillMaxWidth().height(28.dp)
                .semantics { contentDescription = "Track progress" },
            colors = SliderDefaults.colors(thumbColor = MediaInk, activeTrackColor = MediaInk,
                inactiveTrackColor = Color(0xffdddeda)))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val seconds = (progress * 228).toInt()
            Text("${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}",
                fontSize = 10.sp, lineHeight = 14.sp, color = MediaMuted)
            Text("3:48", fontSize = 10.sp, lineHeight = 14.sp, color = MediaMuted)
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            IconButton(onClick = { track = (track + tracks.size - 1) % tracks.size; progress = 0f }) {
                MediaIcon(MediaGlyph.Previous, "Previous track", tint = MediaInk, modifier = Modifier.size(25.dp))
            }
            SeaGlassButton(
                onClick = { playing = !playing }, modifier = Modifier.size(76.dp),
                style = SeaGlassTheme.componentStyle.withSeed(SeaGlassTheme.style.seed + 310),
                shape = SeaGlassShape.Disc, contentPadding = PaddingValues(0.dp),
            ) {
                MediaIcon(if (playing) MediaGlyph.Pause else MediaGlyph.Play,
                    if (playing) "Pause" else "Play", modifier = Modifier.size(30.dp))
            }
            IconButton(onClick = { track = (track + 1) % tracks.size; progress = 0f }) {
                MediaIcon(MediaGlyph.Next, "Next track", tint = MediaInk, modifier = Modifier.size(25.dp))
            }
        }
        Spacer(Modifier.height(28.dp))
        SeaGlassSurface(
            modifier = Modifier.width(200.dp).height(48.dp),
            style = SeaGlassTheme.componentStyle.withSeed(SeaGlassTheme.style.seed + 313),
            shape = SeaGlassShape.Capsule,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                IconButton(onClick = { headphones = !headphones }) {
                    MediaIcon(if (headphones) MediaGlyph.Headphones else MediaGlyph.Speaker,
                        if (headphones) "Switch to speaker" else "Switch to headphones")
                }
                IconButton(onClick = { repeating = !repeating }) {
                    Box(contentAlignment = Alignment.Center) {
                        MediaIcon(MediaGlyph.Repeat, if (repeating) "Turn repeat off" else "Repeat track")
                        if (repeating) Box(Modifier.align(Alignment.BottomCenter).offset(y = 5.dp)
                            .size(3.dp).background(LocalContentColor.current, CircleShape))
                    }
                }
                IconButton(onClick = { showQueue = !showQueue }) {
                    MediaIcon(MediaGlyph.Queue, if (showQueue) "Hide queue" else "Show queue")
                }
            }
        }
        Spacer(Modifier.height(15.dp))
        Text(if (headphones) "Listening on headphones" else "Listening on speaker",
            fontSize = 10.sp, lineHeight = 14.sp, color = MediaMuted)
        if (showQueue) {
            Spacer(Modifier.height(20.dp))
            Column(Modifier.fillMaxWidth()) {
                Text("Up next", color = MediaInk, fontSize = 16.sp, lineHeight = 20.sp, fontFamily = FontFamily.Serif)
                tracks.forEachIndexed { index, name ->
                    Row(Modifier.fillMaxWidth().clickable { track = index; progress = 0f; playing = true }
                        .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("0${index + 1}", color = MediaMuted, fontSize = 10.sp, lineHeight = 14.sp)
                        Spacer(Modifier.width(14.dp))
                        Text(name, color = MediaInk, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
                        if (index == track) MediaIcon(MediaGlyph.Speaker, "Current track", tint = MediaInk,
                            modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

/** Code-native album art, kept separate from the glass material. */
@Composable
private fun SlowCurrentArtwork(modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xffb9cfc0))) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawRect(Brush.verticalGradient(listOf(Color(0xffd1d7bb), Color(0xffa8c8be))))
            drawCircle(Color(0xffe4a983), radius = w * .21f, center = Offset(w * .65f, h * .36f))
            val distant = Path().apply {
                moveTo(0f, h * .57f)
                cubicTo(w * .3f, h * .71f, w * .43f, h * .4f, w * .78f, h * .52f)
                cubicTo(w * .91f, h * .58f, w, h * .52f, w, h * .48f)
                lineTo(w, h); lineTo(0f, h); close()
            }
            drawPath(distant, Color(0xff5b9489))
            val near = Path().apply {
                moveTo(0f, h * .74f)
                cubicTo(w * .35f, h * .48f, w * .55f, h * .91f, w, h * .64f)
                lineTo(w, h); lineTo(0f, h); close()
            }
            drawPath(near, Color(0xff285e59))
            for (i in 0..7) {
                val y = h * (.65f + i * .05f)
                val line = Path().apply {
                    moveTo(-w * .1f, y)
                    cubicTo(w * .25f, y - h * .23f, w * .64f, y + h * .23f, w * 1.1f, y - h * .13f)
                }
                drawPath(line, Color(0xffdfdbc0).copy(alpha = .22f), style = Stroke(width = w * .0025f))
            }
        }
        Text("SORA VALE", Modifier.align(Alignment.TopStart).padding(20.dp),
            color = MediaInk, fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = 2.8.sp, fontWeight = FontWeight.Medium)
        Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
            Text("Slow\nCurrent", color = Color(0xfff4eed7), fontFamily = FontFamily.Serif,
                fontSize = 39.sp, lineHeight = 36.sp, letterSpacing = (-1).sp)
            Spacer(Modifier.height(12.dp))
            Text("VOLUME 02   /   2026", color = Color(0xffcedcd0), fontSize = 7.sp, lineHeight = 10.sp, letterSpacing = 1.5.sp)
        }
    }
}

/** A calm call screen with tactile audio controls and a warm hang-up accent. */
@Composable
internal fun PhoneCallScene() {
    var muted by rememberSaveable { mutableStateOf(false) }
    var speaker by rememberSaveable { mutableStateOf(false) }
    var active by rememberSaveable { mutableStateOf(true) }
    var showKeypad by rememberSaveable { mutableStateOf(false) }
    var digits by rememberSaveable { mutableStateOf("") }
    var video by rememberSaveable { mutableStateOf(false) }
    var contactDialog by rememberSaveable { mutableStateOf(false) }
    var addDialog by rememberSaveable { mutableStateOf(false) }
    val coral = SeaGlassPalette(Color(0xffe3aaa0), Color(0xffffe0d0), Color(0xff8a423a))
    Column(Modifier.fillMaxWidth().background(MediaPaper).padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("PHONE", fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 2.sp,
                color = MediaInk, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(5.dp).background(if (active) Color(0xff6c9775) else MediaMuted, CircleShape))
            Spacer(Modifier.width(7.dp))
            Text(if (active) "CONNECTED" else "CALL ENDED", fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = 1.sp, color = MediaMuted)
        }
        Spacer(Modifier.height(26.dp))
        Box(Modifier.size(112.dp).background(
            Brush.linearGradient(listOf(Color(0xffdce3d7), Color(0xffb5c6b7))), CircleShape),
            contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Color.White.copy(alpha = .2f), size.width * .40f,
                    Offset(size.width * .34f, size.height * .28f))
            }
            Text("JL", fontFamily = FontFamily.Serif, fontSize = 47.sp, lineHeight = 54.sp,
                letterSpacing = (-3).sp, color = Color(0xff496859))
        }
        Spacer(Modifier.height(18.dp))
        Text("Jamie Lee", fontFamily = FontFamily.Serif, fontSize = 30.sp, lineHeight = 36.sp, color = MediaInk)
        Spacer(Modifier.height(6.dp))
        Text(if (active) "mobile  ·  07:42" else "Call ended  ·  07:42", fontSize = 12.sp, lineHeight = 16.sp, color = MediaMuted)
        Spacer(Modifier.height(9.dp))
        Text(when { !active -> ""; video -> "Camera on"; muted -> "Microphone muted";
            speaker -> "Speaker on"; else -> "HD audio" },
            fontSize = 10.sp, lineHeight = 14.sp, color = MediaMuted, modifier = Modifier.height(16.dp))
        Spacer(Modifier.height(20.dp))
        if (showKeypad && active) {
            Text(digits.ifEmpty { "Enter a number" }, color = MediaInk,
                fontSize = if (digits.isEmpty()) 15.sp else 24.sp,
                modifier = Modifier.height(36.dp), maxLines = 1)
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("*", "0", "#")).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    row.forEach { number ->
                        TextButton(onClick = { digits = (digits + number).takeLast(18) }, modifier = Modifier.size(58.dp)) {
                            Text(number, fontSize = 23.sp, lineHeight = 28.sp, color = MediaInk)
                        }
                    }
                }
            }
            TextButton(onClick = { showKeypad = false }) { Text("Hide keypad", color = MediaInk) }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                CallControl("Mute", if (muted) MediaGlyph.MicOff else MediaGlyph.Mic,
                    selected = muted, enabled = active, glass = true, seed = 410) { muted = !muted }
                CallControl("Keypad", MediaGlyph.Keypad, enabled = active) { showKeypad = true }
                CallControl("Speaker", MediaGlyph.Speaker,
                    selected = speaker, enabled = active, glass = true, seed = 412) { speaker = !speaker }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                CallControl("Add call", MediaGlyph.Add, enabled = active) { addDialog = true }
                CallControl("Video", MediaGlyph.Video, selected = video, enabled = active) { video = !video }
                CallControl("Contact", MediaGlyph.Person) { contactDialog = true }
            }
        }
        Spacer(Modifier.height(24.dp))
        SeaGlassButton(
            onClick = { active = !active; muted = false; speaker = false; showKeypad = false; video = false },
            modifier = Modifier.width(88.dp).height(62.dp),
            style = SeaGlassTheme.componentStyle.withSeed(SeaGlassTheme.style.seed + 418),
            palette = if (active) coral else SeaGlassTheme.palette,
            shape = SeaGlassShape.Capsule, contentPadding = PaddingValues(0.dp),
        ) {
            MediaIcon(MediaGlyph.Phone, if (active) "End call" else "Call Jamie again",
                modifier = Modifier.size(27.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(if (active) "End call" else "Call again", color = MediaMuted, fontSize = 10.sp, lineHeight = 14.sp)
        Spacer(Modifier.height(8.dp))
    }
    if (contactDialog) AlertDialog(onDismissRequest = { contactDialog = false },
        title = { Text("Jamie Lee", fontFamily = FontFamily.Serif) },
        text = { Text("Mobile\n(202) 555-0142") },
        confirmButton = { TextButton(onClick = { contactDialog = false }) { Text("Done") } })
    if (addDialog) AlertDialog(onDismissRequest = { addDialog = false },
        title = { Text("Add to call", fontFamily = FontFamily.Serif) },
        text = { Text("No other contacts are available.") },
        confirmButton = { TextButton(onClick = { addDialog = false }) { Text("Done") } })
}

@Composable
private fun CallControl(
    label: String, glyph: MediaGlyph, selected: Boolean = false, enabled: Boolean = true,
    glass: Boolean = false, seed: Int = 0, onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (glass) SeaGlassButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(64.dp),
            style = SeaGlassTheme.componentStyle.withSeed(SeaGlassTheme.style.seed + seed),
            shape = SeaGlassShape.Disc, contentPadding = PaddingValues(0.dp)) {
            MediaIcon(glyph, "$label ${if (selected) "on" else "off"}")
        } else {
            IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(64.dp)
                .background(if (selected) Color(0xffdce6dc) else Color(0xffeeeee6), CircleShape)) {
                MediaIcon(glyph, label, tint = if (enabled) MediaInk else MediaMuted.copy(alpha = .5f))
            }
        }
        Spacer(Modifier.height(13.dp))
        Text(label, color = MediaMuted, fontSize = 11.sp, lineHeight = 15.sp)
        Box(Modifier.height(8.dp), contentAlignment = Alignment.BottomCenter) {
            if (selected) Box(Modifier.size(3.dp).background(MediaInk, CircleShape))
        }
    }
}

private enum class MediaGlyph {
    Play, Pause, Previous, Next, Heart, HeartFilled, Queue, Repeat, Headphones, Speaker,
    Mic, MicOff, Keypad, Add, Video, Person, Phone,
}

@Composable
private fun MediaIcon(glyph: MediaGlyph, label: String, modifier: Modifier = Modifier.size(22.dp),
    tint: Color = LocalContentColor.current) {
    Canvas(modifier.semantics { contentDescription = label }) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
                drawLine(tint, Offset(x1, y1), Offset(x2, y2), strokeWidth = 1.7f, cap = StrokeCap.Round)
            fun path(vararg points: Pair<Float, Float>, fill: Boolean = false) {
                val p = Path().apply {
                    moveTo(points[0].first, points[0].second)
                    points.drop(1).forEach { lineTo(it.first, it.second) }
                    if (fill) close()
                }
                if (fill) drawPath(p, tint) else drawPath(p, tint, style = Stroke(1.7f, cap = StrokeCap.Round))
            }
            when (glyph) {
                MediaGlyph.Play -> path(8f to 4f, 20f to 12f, 8f to 20f, fill = true)
                MediaGlyph.Pause -> {
                    drawRoundRect(tint, Offset(6f, 4f), Size(4f, 16f), androidx.compose.ui.geometry.CornerRadius(1f))
                    drawRoundRect(tint, Offset(14f, 4f), Size(4f, 16f), androidx.compose.ui.geometry.CornerRadius(1f))
                }
                MediaGlyph.Next -> { path(5f to 5f, 16f to 12f, 5f to 19f, fill = true); line(18f, 5f, 18f, 19f) }
                MediaGlyph.Previous -> { path(19f to 5f, 8f to 12f, 19f to 19f, fill = true); line(6f, 5f, 6f, 19f) }
                MediaGlyph.Heart, MediaGlyph.HeartFilled -> {
                    val p = Path().apply {
                        moveTo(12f, 20f); cubicTo(8f, 17f, 3f, 13f, 3f, 8f)
                        cubicTo(3f, 3f, 9f, 2f, 12f, 7f); cubicTo(15f, 2f, 21f, 3f, 21f, 8f)
                        cubicTo(21f, 13f, 16f, 17f, 12f, 20f); close()
                    }
                    if (glyph == MediaGlyph.HeartFilled) drawPath(p, tint) else drawPath(p, tint, style = Stroke(1.6f))
                }
                MediaGlyph.Queue -> {
                    line(4f, 6f, 20f, 6f); line(4f, 12f, 15f, 12f); line(4f, 18f, 12f, 18f)
                    path(17f to 15f, 22f to 18f, 17f to 21f, fill = true)
                }
                MediaGlyph.Repeat -> {
                    path(5f to 10f, 5f to 6f, 19f to 6f, 16f to 3f)
                    line(19f, 6f, 16f, 9f)
                    path(19f to 14f, 19f to 18f, 5f to 18f, 8f to 21f)
                    line(5f, 18f, 8f, 15f)
                }
                MediaGlyph.Headphones -> {
                    drawArc(tint, 180f, 180f, false, Offset(4f, 3f), Size(16f, 18f), style = Stroke(1.7f))
                    drawRoundRect(tint, Offset(3f, 12f), Size(4f, 8f), androidx.compose.ui.geometry.CornerRadius(1.5f))
                    drawRoundRect(tint, Offset(17f, 12f), Size(4f, 8f), androidx.compose.ui.geometry.CornerRadius(1.5f))
                }
                MediaGlyph.Speaker -> {
                    path(3f to 9f, 7f to 9f, 12f to 5f, 12f to 19f, 7f to 15f, 3f to 15f, 3f to 9f)
                    drawArc(tint, -60f, 120f, false, Offset(8f, 7f), Size(10f, 10f), style = Stroke(1.7f))
                    drawArc(tint, -55f, 110f, false, Offset(4f, 3f), Size(18f, 18f), style = Stroke(1.7f))
                }
                MediaGlyph.Mic, MediaGlyph.MicOff -> {
                    drawRoundRect(tint, Offset(9f, 2f), Size(6f, 13f), androidx.compose.ui.geometry.CornerRadius(3f), style = Stroke(1.7f))
                    drawArc(tint, 0f, 180f, false, Offset(5f, 6f), Size(14f, 13f), style = Stroke(1.7f))
                    line(12f, 19f, 12f, 22f); line(8f, 22f, 16f, 22f)
                    if (glyph == MediaGlyph.MicOff) line(3f, 3f, 21f, 21f)
                }
                MediaGlyph.Keypad -> for (row in 0..2) for (column in 0..2) {
                    drawCircle(tint, 1.55f, Offset(6f + column * 6f, 5f + row * 6f))
                }
                MediaGlyph.Add -> { line(12f, 4f, 12f, 20f); line(4f, 12f, 20f, 12f) }
                MediaGlyph.Video -> {
                    drawRoundRect(tint, Offset(2f, 6f), Size(14f, 12f), androidx.compose.ui.geometry.CornerRadius(2f), style = Stroke(1.7f))
                    path(16f to 10f, 22f to 7f, 22f to 17f, 16f to 14f)
                }
                MediaGlyph.Person -> {
                    drawCircle(tint, 4f, Offset(12f, 7f), style = Stroke(1.7f))
                    val p = Path().apply { moveTo(4f, 21f); cubicTo(4f, 11f, 20f, 11f, 20f, 21f) }
                    drawPath(p, tint, style = Stroke(1.7f, cap = StrokeCap.Round))
                }
                MediaGlyph.Phone -> {
                    val p = Path().apply {
                        moveTo(2f, 14f); cubicTo(5f, 7f, 19f, 7f, 22f, 14f)
                        lineTo(20f, 17f); lineTo(16f, 15f); lineTo(16f, 12f)
                        cubicTo(14f, 11f, 10f, 11f, 8f, 12f)
                        lineTo(8f, 15f); lineTo(4f, 17f); close()
                    }
                    drawPath(p, tint)
                }
            }
        }
    }
}
