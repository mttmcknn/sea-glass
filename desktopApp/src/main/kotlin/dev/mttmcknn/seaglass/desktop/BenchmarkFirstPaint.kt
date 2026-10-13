package dev.mttmcknn.seaglass.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.mttmcknn.seaglass.SeaGlassShape
import dev.mttmcknn.seaglass.SeaGlassStyle
import dev.mttmcknn.seaglass.SeaGlassSurface
import dev.mttmcknn.seaglass.SeaGlassTheme
import dev.mttmcknn.seaglass.sample.SeaGlassScene
import dev.mttmcknn.seaglass.sample.SeaGlassShowcase
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap

/**
 * Two-process first-paint check. Run `reference <directory> [rounds]` once, then
 * `measure <directory> [rounds]` in a fresh JVM. Never capture the reference in the
 * measuring process: doing that would prewarm a shared final-image cache.
 *
 * Every fixture/repetition uses a different seed, so its first insertion is cold
 * even when a shared image cache is enabled. The immediate second insertion is
 * reported separately as a warm revisit. The timer starts immediately before the
 * selector's semantics action, and its first observation is the first render call.
 *
 * This is a desktop Compose proxy, including CPU composition, drawing and readback.
 * It cannot measure Android GPU presentation, touch dispatch or device frame time.
 * Reference quiet-window waits and empty-screen preparation are never timed.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) = runBlocking {
    val mode = args.getOrNull(0) ?: error("Expected reference or measure")
    require(mode == "reference" || mode == "measure")
    val directory = Path.of(args.getOrNull(1) ?: error("Expected reference directory"))
    val rounds = args.getOrNull(2)?.toIntOrNull() ?: 3
    require(rounds > 0)
    Files.createDirectories(directory)
    val fixtures = listOf("capsule", "disc", "toolbar", "music", "phone", "email", "chat")
    if (mode == "measure") {
        println("Desktop first-paint proxy: 520x1000, density=1, poll=4ms, distinct seeds, semantic selection")
        println("fixture,round,cache,first_frame_ms,first_frame_exact,first_frame_affected_region_channel_mae,first_frame_affected_region_changed_pixels_pct,final_frame_ms")
    }
    for ((index, fixture) in fixtures.withIndex()) repeat(rounds) { round ->
        val seed = 120_000 + index * 1_000 + round
        val path = directory.resolve("$fixture-$round.rgba")
        val selected = mutableStateOf(mode == "reference")
        val scene = firstPaintScene(coroutineContext, fixture, seed, selected)
        try {
            FirstPaintPixels().use { pixels ->
                if (mode == "reference") {
                    Files.write(path, scene.firstPaintReference(pixels))
                    println("Reference $fixture ${round + 1}: $path")
                } else {
                    val target = Files.readAllBytes(path)
                    require(target.size == 520 * 1000 * 4) { "Reference has wrong dimensions: $path" }
                    scene.firstPaintEmpty(pixels)
                    scene.measureFirstPaint(pixels, target, fixture, round + 1, "cold")
                    selected.value = false
                    scene.firstPaintEmpty(pixels)
                    scene.measureFirstPaint(pixels, target, fixture, round + 1, "revisit")
                }
            }
        } finally {
            scene.close()
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun firstPaintScene(context: CoroutineContext, fixture: String, seed: Int, selected: MutableState<Boolean>): ImageComposeScene =
    ImageComposeScene(width = 520, height = 1000, density = Density(1f), coroutineContext = context) {
        MaterialTheme {
            SeaGlassTheme(style = SeaGlassStyle.Reference.copy(seed = seed)) {
                Column(Modifier.fillMaxSize().background(Color.White), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                        Button(onClick = { selected.value = true }, modifier = Modifier.testTag("first-paint-select")) {
                            Text("Open $fixture")
                        }
                    }
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                        if (selected.value) FirstPaintFixture(fixture)
                    }
                }
            }
        }
    }

@Composable
private fun FirstPaintFixture(fixture: String) {
    when (fixture) {
        "capsule", "disc", "toolbar" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val size = when (fixture) {
                "disc" -> Modifier.size(76.dp)
                "toolbar" -> Modifier.size(330.dp, 64.dp)
                else -> Modifier.size(200.dp, 64.dp)
            }
            SeaGlassSurface(size, shape = if (fixture == "disc") SeaGlassShape.Disc else SeaGlassShape.Capsule) {
                Text(if (fixture == "toolbar") "Share       Comment       Save" else "Glass")
            }
        }
        else -> {
            val scene = when (fixture) {
                "music" -> SeaGlassScene.Music
                "phone" -> SeaGlassScene.Phone
                "email" -> SeaGlassScene.Email
                else -> SeaGlassScene.Chat
            }
            SeaGlassShowcase(scene, Modifier.width(440.dp))
        }
    }
}

private class FirstPaintPixels : AutoCloseable {
    private val bitmap = Bitmap().apply { check(allocN32Pixels(520, 1000)) }
    @OptIn(ExperimentalComposeUiApi::class)
    fun read(scene: ImageComposeScene): ByteArray = scene.render(System.nanoTime()).use { image ->
        check(image.readPixels(bitmap))
        checkNotNull(bitmap.readPixels())
    }
    override fun close() = bitmap.close()
}

@OptIn(ExperimentalComposeUiApi::class)
private suspend fun ImageComposeScene.firstPaintReference(pixels: FirstPaintPixels): ByteArray {
    val started = System.nanoTime()
    var changed = started
    var previous: ByteArray? = null
    while (true) {
        val frame = pixels.read(this)
        val now = System.nanoTime()
        if (previous?.contentEquals(frame) != true) changed = now
        previous = frame
        if (now - started > 3_000_000_000L && now - changed > 1_000_000_000L && !hasInvalidations()) return frame
        check(now - started < 30_000_000_000L) { "Reference did not settle" }
        delay(16)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private suspend fun ImageComposeScene.firstPaintEmpty(pixels: FirstPaintPixels) {
    // Process removal and allow old worker cancellation without rendering the next fixture.
    repeat(8) { pixels.read(this); delay(16) }
    check(firstPaintNodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "first-paint-select" })
}

@OptIn(ExperimentalComposeUiApi::class)
private suspend fun ImageComposeScene.measureFirstPaint(
    pixels: FirstPaintPixels, target: ByteArray, fixture: String, round: Int, cache: String,
) {
    val select = firstPaintNodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == "first-paint-select" }
    val action = generateSequence(select) { it.parent }.first { it.config.contains(SemanticsActions.OnClick) }
        .config[SemanticsActions.OnClick].action
    val started = System.nanoTime()
    check(action?.invoke() == true)
    val first = pixels.read(this)
    val firstElapsed = (System.nanoTime() - started) / 1_000_000.0
    val firstExact = first.contentEquals(target)
    var frame = first
    while (!frame.contentEquals(target)) {
        check(System.nanoTime() - started < 30_000_000_000L) { "$fixture/$cache did not match reference" }
        delay(4)
        frame = pixels.read(this)
    }
    val finalElapsed = (System.nanoTime() - started) / 1_000_000.0
    // Calculate quality metrics after timing. Exclude the selector and pixels
    // that are white in both images so empty space cannot dilute the error.
    var error = 0L
    var changed = 0
    var count = 0
    for (i in 64 * 520 * 4 until first.size step 4) {
        if ((0..2).all { first[i + it] == (-1).toByte() && target[i + it] == (-1).toByte() }) continue
        var differs = false
        for (channel in 0..2) {
            val delta = abs((first[i + channel].toInt() and 255) - (target[i + channel].toInt() and 255))
            error += delta
            if (delta != 0) differs = true
        }
        if (differs) changed++
        count++
    }
    val mae = error.toDouble() / (count.coerceAtLeast(1) * 3)
    val changedPercent = changed * 100.0 / count.coerceAtLeast(1)
    fun Double.csv() = String.format(Locale.US, "%.3f", this)
    println("$fixture,$round,$cache,${firstElapsed.csv()},$firstExact,${mae.csv()},${changedPercent.csv()},${finalElapsed.csv()}")
}

@OptIn(ExperimentalComposeUiApi::class)
private fun ImageComposeScene.firstPaintNodes(): List<SemanticsNode> {
    fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
    return semanticsOwners.flatMap { descendants(it.unmergedRootSemanticsNode) }
}
