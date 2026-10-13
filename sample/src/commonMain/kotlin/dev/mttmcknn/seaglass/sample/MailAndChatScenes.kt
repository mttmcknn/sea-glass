package dev.mttmcknn.seaglass.sample

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mttmcknn.seaglass.SeaGlassButton
import dev.mttmcknn.seaglass.SeaGlassShape
import dev.mttmcknn.seaglass.SeaGlassSurface
import dev.mttmcknn.seaglass.SeaGlassTheme

private val SceneInk = Color(0xff263830)
private val SceneMuted = Color(0xff7b857d)
private val ScenePaper = Color(0xfffcfcf8)
private val SceneLine = Color(0xffe8ebe3)

/** A deliberately ordinary inbox, with the material reserved for its primary actions. */
@Composable
internal fun EmailScene() {
    var archived by rememberSaveable { mutableStateOf(false) }
    var selectedMessage by remember { mutableStateOf<SceneMail?>(null) }
    var composing by rememberSaveable { mutableStateOf(false) }
    var recipient by rememberSaveable { mutableStateOf("") }
    var subject by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var draftSaved by rememberSaveable { mutableStateOf(false) }
    val material = SeaGlassTheme.componentStyle
    val messages = if (archived) ArchivedMail else InboxMail

    Column(Modifier.fillMaxWidth().background(ScenePaper).padding(horizontal = 22.dp, vertical = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Post", fontFamily = FontFamily.Serif, fontSize = 32.sp, color = SceneInk)
            Spacer(Modifier.weight(1f))
            Monogram("AL", Color(0xffe9ecdf), Color(0xff687154), 36)
        }
        Spacer(Modifier.height(26.dp))
        Text("Room to breathe.", fontFamily = FontFamily.Serif, fontSize = 24.sp,
            lineHeight = 29.sp, color = SceneInk)
        Spacer(Modifier.height(7.dp))
        Text("Your day, thoughtfully delivered.", fontSize = 12.sp, color = SceneMuted)
        Spacer(Modifier.height(26.dp))

        SeaGlassSurface(
            modifier = Modifier.fillMaxWidth().height(54.dp),
            style = material.withSeed(material.material.seed + 610), shape = SeaGlassShape.Capsule,
        ) {
            Row(Modifier.fillMaxSize().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                MailFolder("Inbox", "3", !archived, Modifier.weight(1f)) { archived = false }
                MailFolder("Archive", null, archived, Modifier.weight(1f)) { archived = true }
            }
        }
        Spacer(Modifier.height(30.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (archived) "FILED AWAY" else "TODAY", fontSize = 10.sp, letterSpacing = 1.6.sp,
                color = SceneMuted, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(if (archived) "All caught up" else "3 conversations", fontSize = 10.sp, color = SceneMuted)
        }
        Spacer(Modifier.height(9.dp))
        messages.forEachIndexed { index, message ->
            MailRow(message, unread = !archived) { selectedMessage = message }
            if (index < messages.lastIndex) HorizontalDivider(color = SceneLine)
        }
        Spacer(Modifier.height(28.dp))
        SeaGlassButton(
            onClick = { composing = true },
            modifier = Modifier.height(58.dp).align(Alignment.End),
            style = material.withSeed(material.material.seed + 611), shape = SeaGlassShape.Capsule,
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
        ) {
            SceneGlyph(SceneSymbol.Pen)
            Spacer(Modifier.width(10.dp))
            Text("Compose", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(21.dp))
        Text(if (draftSaved) "Your draft is saved." else "The rest can wait a little.",
            modifier = Modifier.align(Alignment.CenterHorizontally),
            fontSize = 11.sp, color = SceneMuted, fontFamily = FontFamily.Serif)
    }

    selectedMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { selectedMessage = null },
            title = { Text(message.subject, fontFamily = FontFamily.Serif) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("From ${message.sender}", fontWeight = FontWeight.SemiBold)
                    Text(message.message, lineHeight = 23.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedMessage = null; recipient = message.sender; subject = "Re: ${message.subject}"; composing = true }) {
                    Text("Reply")
                }
            },
            dismissButton = { TextButton(onClick = { selectedMessage = null }) { Text("Close") } },
        )
    }
    if (composing) {
        AlertDialog(
            onDismissRequest = { composing = false },
            title = { Text("A new conversation", fontFamily = FontFamily.Serif) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(recipient, { recipient = it }, label = { Text("To") }, singleLine = true)
                    OutlinedTextField(subject, { subject = it }, label = { Text("Subject") }, singleLine = true)
                    OutlinedTextField(body, { body = it }, label = { Text("Your message") }, minLines = 3)
                }
            },
            confirmButton = {
                TextButton(enabled = subject.isNotBlank() || body.isNotBlank(), onClick = { draftSaved = true; composing = false }) {
                    Text("Save draft")
                }
            },
            dismissButton = { TextButton(onClick = { composing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MailFolder(label: String, count: String?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val ink = LocalContentColor.current
    Row(
        modifier.fillMaxHeight().clip(CircleShape)
            .background(if (selected) Color.White.copy(alpha = .4f) else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Text(label, fontSize = 12.sp, color = ink.copy(alpha = if (selected) 1f else .7f),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        if (count != null) {
            Spacer(Modifier.width(7.dp))
            Box(Modifier.size(19.dp).background(ink.copy(alpha = .09f), CircleShape), contentAlignment = Alignment.Center) {
                Text(count, fontSize = 10.sp, color = ink)
            }
        }
    }
}

@Composable
private fun MailRow(message: SceneMail, unread: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 15.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box {
            Monogram(message.initials, message.color, SceneInk.copy(alpha = .65f), 35)
            if (unread) Box(Modifier.size(8.dp).align(Alignment.BottomEnd)
                .background(Color(0xff638b74), CircleShape).border(2.dp, ScenePaper, CircleShape))
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(message.sender, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = SceneInk)
                Spacer(Modifier.width(5.dp))
                Text(message.time, fontSize = 9.sp, color = SceneMuted)
            }
            Spacer(Modifier.height(5.dp))
            Text(message.subject, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp, color = SceneInk)
            Spacer(Modifier.height(4.dp))
            Text(message.preview, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontSize = 11.sp, color = SceneMuted)
        }
    }
}

private data class SceneMail(
    val sender: String, val initials: String, val subject: String, val preview: String,
    val time: String, val color: Color, val message: String,
)

private val InboxMail = listOf(
    SceneMail("Maya Bennett", "MB", "A table by the window", "Saturday at ten? Coffee is on me.", "9:41", Color(0xffeee4d4),
        "I found a little place with a table by the window and very good coffee. Saturday at ten? Bring the book you were telling me about. Coffee is on me.\n\nMaya"),
    SceneMail("Studio Notes", "S", "Things worth keeping", "A few good things from this week.", "8:25", Color(0xffe1e9dc),
        "This week: a beautifully worn notebook, a quiet corner of the city, and a reminder that good things take their time.\n\nHere is to making a little room for them."),
    SceneMail("Oliver Chen", "OC", "The first sketches", "I think we found our direction.", "8:02", Color(0xffe3e6ef),
        "The first sketches are ready. I kept the shapes soft and gave everything a bit more breathing room. I think we found our direction.\n\nWould love your thoughts when you have a moment.\n\nOliver"),
)

private val ArchivedMail = listOf(
    SceneMail("The Reading Room", "R", "Your next chapter", "Your book is ready to collect.", "Mon", Color(0xffede5da),
        "The book you ordered is ready to collect. We have tucked it behind the counter with your name on it. See you soon."),
    SceneMail("Maya Bennett", "MB", "Home again", "That was such a good afternoon.", "Sun", Color(0xffeee4d4),
        "Home again, with a camera full of photos and sand in my shoes. That was such a good afternoon. Let's do it again soon."),
    SceneMail("Oliver Chen", "OC", "A fresh start", "A few thoughts for the new project.", "Fri", Color(0xffe3e6ef),
        "A few thoughts for the new project: less noise, softer edges, and a little more personality. I'll send the first sketches next week."),
)

/** A calm conversation with glass used on suggestions and the send control. */
@Composable
internal fun AiChatScene() {
    var question by rememberSaveable { mutableStateOf("A quiet Sunday: coffee, a bookstore, and a walk by the water.") }
    var reply by rememberSaveable { mutableIntStateOf(0) }
    var draft by rememberSaveable { mutableStateOf("Make it a slower morning") }
    val material = SeaGlassTheme.componentStyle
    val followUp: (String, Int) -> Unit = { message, variant -> question = message; reply = variant; draft = "" }

    Column(Modifier.fillMaxWidth().background(ScenePaper).padding(horizontal = 22.dp, vertical = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).background(Color(0xffe9eddf), CircleShape), contentAlignment = Alignment.Center) {
                SceneGlyph(SceneSymbol.Spark, color = Color(0xff66755a), modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Nori", fontSize = 22.sp, fontFamily = FontFamily.Serif, color = SceneInk)
                Text("A little clarity, every day", fontSize = 10.sp, color = SceneMuted)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                question = "A quiet Sunday: coffee, a bookstore, and a walk by the water."
                reply = 0; draft = "Make it a slower morning"
            }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("New", fontSize = 11.sp, color = SceneMuted) }
        }
        Spacer(Modifier.height(24.dp))
        Text("A LITTLE SPACE FOR IDEAS", modifier = Modifier.align(Alignment.CenterHorizontally),
            color = SceneMuted, fontSize = 9.sp, letterSpacing = 1.4.sp)
        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth(.92f).align(Alignment.End)
            .background(Color(0xffeeefe8), RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)).padding(15.dp)) {
            Text(question, color = SceneInk, fontSize = 13.sp, lineHeight = 20.sp)
        }
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SceneGlyph(SceneSymbol.Spark, color = Color(0xff7b8b6f), modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(7.dp))
            Text("NORI", fontSize = 9.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold, color = SceneMuted)
        }
        Spacer(Modifier.height(11.dp))
        Text(when (reply) {
            1 -> "A rainy-day kind of Sunday."
            2 -> "Leave a little room to wander."
            else -> "A slower kind of Sunday."
        }, fontFamily = FontFamily.Serif, fontSize = 24.sp, lineHeight = 29.sp, color = SceneInk)
        Spacer(Modifier.height(11.dp))
        Text(when (reply) {
            1 -> "Keep the good parts. Bring them indoors."
            2 -> "One small plan. Plenty of open space."
            else -> "Three good stops. No need to rush between them."
        }, fontSize = 12.sp, lineHeight = 19.sp, color = SceneMuted)
        Spacer(Modifier.height(20.dp))
        val stops = when (reply) {
            1 -> listOf("10:00" to ("Stay for a second cup" to "A window seat. Something warm."),
                "11:30" to ("Browse the shelves" to "Choose a book by its first page."),
                "1:00" to ("Make an afternoon of it" to "Lunch, your new book, no clock."))
            2 -> listOf("10:30" to ("Start with good coffee" to "Take your time with the first cup."),
                "12:00" to ("Take the scenic route" to "Follow the water. Find a bench."),
                "Later" to ("See where you end up" to "A bookstore, if the mood takes you."))
            else -> listOf("10:00" to ("Coffee, slowly" to "Find a sunny table. Stay a while."),
                "11:30" to ("A book you didn't plan on" to "Let a small bookstore surprise you."),
                "1:00" to ("Follow the water" to "An easy walk, with no destination."))
        }
        stops.forEachIndexed { index, (time, stop) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(time, modifier = Modifier.width(43.dp).padding(top = 2.dp), color = SceneMuted, fontSize = 10.sp)
                Column(Modifier.weight(1f)) {
                    Text(stop.first, color = SceneInk, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(3.dp))
                    Text(stop.second, color = SceneMuted, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
            if (index < stops.lastIndex) Spacer(Modifier.height(16.dp))
        }
        Spacer(Modifier.height(26.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SeaGlassButton(
                onClick = { followUp("What if it rains?", 1) }, modifier = Modifier.weight(1f).height(48.dp),
                style = material.withSeed(material.material.seed + 710), shape = SeaGlassShape.Capsule,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
            ) { Text("If it rains?", fontSize = 11.sp, maxLines = 1) }
            SeaGlassButton(
                onClick = { followUp("A little more time outdoors.", 2) }, modifier = Modifier.weight(1f).height(48.dp),
                style = material.withSeed(material.material.seed + 711), shape = SeaGlassShape.Capsule,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
            ) { Text("More outdoors", fontSize = 11.sp, maxLines = 1) }
        }
        Spacer(Modifier.height(28.dp))
        Row(
            Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(24.dp))
                .border(1.dp, SceneLine, RoundedCornerShape(24.dp)).padding(start = 15.dp, end = 7.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = draft, onValueChange = { draft = it }, modifier = Modifier.weight(1f)
                    .semantics { contentDescription = "Message Nori" },
                textStyle = TextStyle(color = SceneInk, fontSize = 12.sp, lineHeight = 18.sp),
                cursorBrush = SolidColor(SceneInk), maxLines = 3,
                decorationBox = { input ->
                    if (draft.isEmpty()) Text("Ask a follow-up...", fontSize = 12.sp, color = SceneMuted)
                    input()
                },
            )
            Spacer(Modifier.width(8.dp))
            SeaGlassButton(
                onClick = { if (draft.isNotBlank()) followUp(draft, if (draft.contains("rain", ignoreCase = true)) 1 else 2) },
                modifier = Modifier.size(48.dp).semantics { contentDescription = "Send message" },
                style = material.withSeed(material.material.seed + 712), shape = SeaGlassShape.Disc,
                enabled = draft.isNotBlank(), contentPadding = PaddingValues(12.dp),
            ) { SceneGlyph(SceneSymbol.ArrowUp) }
        }
        Spacer(Modifier.height(16.dp))
        Text("A thought partner, at your pace.", modifier = Modifier.align(Alignment.CenterHorizontally),
            fontFamily = FontFamily.Serif, fontSize = 11.sp, color = SceneMuted)
    }
}

@Composable
private fun Monogram(initials: String, background: Color, foreground: Color, size: Int) {
    Box(Modifier.size(size.dp).background(background, CircleShape), contentAlignment = Alignment.Center) {
        Text(initials, color = foreground, fontSize = if (initials.length > 1) 10.sp else 14.sp,
            fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium)
    }
}

private enum class SceneSymbol { Pen, ArrowUp, Spark }

@Composable
private fun SceneGlyph(symbol: SceneSymbol, modifier: Modifier = Modifier.size(20.dp), color: Color = LocalContentColor.current) {
    Canvas(modifier) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            when (symbol) {
                SceneSymbol.Pen -> {
                    val pen = Path().apply {
                        moveTo(5f, 16f); lineTo(16f, 5f); lineTo(20f, 9f)
                        lineTo(9f, 20f); lineTo(4f, 21f); close()
                    }
                    drawPath(pen, color, style = Stroke(1.8f, cap = StrokeCap.Round))
                    drawLine(color, Offset(14f, 7f), Offset(18f, 11f), 1.8f, StrokeCap.Round)
                }
                SceneSymbol.ArrowUp -> {
                    drawLine(color, Offset(12f, 19f), Offset(12f, 5f), 2f, StrokeCap.Round)
                    drawLine(color, Offset(6f, 11f), Offset(12f, 5f), 2f, StrokeCap.Round)
                    drawLine(color, Offset(18f, 11f), Offset(12f, 5f), 2f, StrokeCap.Round)
                }
                SceneSymbol.Spark -> {
                    val spark = Path().apply {
                        moveTo(12f, 2f); cubicTo(13f, 8f, 16f, 11f, 22f, 12f)
                        cubicTo(16f, 13f, 13f, 16f, 12f, 22f)
                        cubicTo(11f, 16f, 8f, 13f, 2f, 12f)
                        cubicTo(8f, 11f, 11f, 8f, 12f, 2f); close()
                    }
                    drawPath(spark, color)
                }
            }
        }
    }
}
