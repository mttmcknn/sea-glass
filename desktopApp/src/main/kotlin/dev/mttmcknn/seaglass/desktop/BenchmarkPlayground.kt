package dev.mttmcknn.seaglass.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.mttmcknn.seaglass.SeaGlassTheme
import dev.mttmcknn.seaglass.sample.SeaGlassPlayground
import dev.mttmcknn.seaglass.sample.SeaGlassScene
import dev.mttmcknn.seaglass.sample.SeaGlassShowcase
import java.util.Locale
import kotlin.coroutines.CoroutineContext
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap

/**
 * Desktop interaction proxy, not Android frame-time or device-FPS measurements.
 *
 * Each timed operation ends when the visible preview exactly matches an independently
 * settled target. The reference's quiet-window wait is outside the measured interval,
 * so an idle composition cannot hide an unfinished material worker. Times include
 * composition, raster drawing and bitmap readback; sampling adds up to 4 ms.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) = runBlocking {
    val repetitions = args.getOrNull(0)?.toIntOrNull() ?: 3
    require(repetitions > 0)
    val mode = args.getOrNull(1) ?: "all"
    require(mode in setOf("all", "sliders", "scenes"))
    println("Desktop Compose proxy; density=1; repetitions=$repetitions; exact-pixel completion; poll=4ms")
    println("case,round,kind,latency_ms,burst_ms,pump_p95_ms,distinct_preview_frames")
    if (mode != "scenes") benchmarkSliders(coroutineContext, repetitions)
    if (mode != "sliders") benchmarkScenes(coroutineContext, repetitions)
}

private data class SliderCase(val label: String, val from: Float, val to: Float)

@OptIn(ExperimentalComposeUiApi::class)
private suspend fun benchmarkSliders(context: CoroutineContext, repetitions: Int) {
    val scene = ImageComposeScene(1200, 1000, Density(1f), context) { SeaGlassPlayground() }
    try {
        scene.render(System.nanoTime()).close()
        delay(16)
        scene.render(System.nanoTime()).close()
        val pane = scene.nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == "playground-preview" }.boundsInRoot
        PixelReader(pane.left.roundToInt(), pane.top.roundToInt(), pane.width.roundToInt(), pane.height.roundToInt()).use { pixels ->
            for (case in listOf(
                SliderCase("Tumble", .01f, .055f),
                SliderCase("Frost", .2f, .65f),
                SliderCase("Surface light angle", 100f, 220f),
                SliderCase("Lift", 2f, 15f),
            )) {
                scene.setProgress(case.label, case.from)
                val fromImage = scene.reference(pixels)
                scene.setProgress(case.label, case.to)
                val toImage = scene.reference(pixels)
                check(!fromImage.contentEquals(toImage)) { "${case.label} must visibly change the preview" }
                repeat(repetitions) { round ->
                    scene.setProgress(case.label, case.from)
                    scene.awaitPixels(pixels, fromImage)
                    val pumpTimes = mutableListOf<Double>()
                    var previous: ByteArray? = null
                    var distinctFrames = 0
                    val started = System.nanoTime()
                    var lastEvent = started
                    // Drive real slider semantics, pumping Compose between values so this
                    // exercises cancellation and back-pressure instead of conflating one frame.
                    repeat(24) { step ->
                        val due = started + step * 16_000_000L
                        val remaining = due - System.nanoTime()
                        if (remaining > 0) delay((remaining / 1_000_000L).coerceAtLeast(1))
                        lastEvent = System.nanoTime()
                        scene.setProgress(case.label, if (step == 23) case.to else case.from + (case.to - case.from) * (step + 1) / 24f)
                        val pumpStarted = System.nanoTime()
                        val frame = pixels.read(scene)
                        pumpTimes += millisSince(pumpStarted)
                        if (previous?.contentEquals(frame) != true) distinctFrames++
                        previous = frame
                        delay(1)
                    }
                    scene.awaitPixels(pixels, toImage)
                    val latency = millisSince(lastEvent)
                    val burst = (lastEvent - started) / 1_000_000.0
                    println("${case.label},${round + 1},last-update-to-final,${latency.format()},${burst.format()},${pumpTimes.percentile(.95).format()},$distinctFrames")
                }
            }
        }
    } finally {
        scene.close()
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private suspend fun benchmarkScenes(context: CoroutineContext, repetitions: Int) {
    for (example in listOf(SeaGlassScene.Music, SeaGlassScene.Phone, SeaGlassScene.Email, SeaGlassScene.Chat)) {
        fun newScene() = ImageComposeScene(520, 1000, Density(1f), context) {
            MaterialTheme {
                SeaGlassTheme {
                    Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.TopCenter) {
                        SeaGlassShowcase(example, Modifier.width(440.dp))
                    }
                }
            }
        }
        PixelReader(0, 0, 520, 1000).use { pixels ->
            val referenceScene = newScene()
            val reference = try { referenceScene.reference(pixels) } finally { referenceScene.close() }
            repeat(repetitions) { round ->
                val started = System.nanoTime()
                val scene = newScene()
                try {
                    scene.awaitPixels(pixels, reference)
                    println("${example.title},${round + 1},cold-composition-to-final,${millisSince(started).format()},,,")
                } finally {
                    scene.close()
                }
            }
        }
    }
}

private class PixelReader(private val left: Int, private val top: Int, width: Int, height: Int) : AutoCloseable {
    private val bitmap = Bitmap().apply { check(allocN32Pixels(width, height)) }

    @OptIn(ExperimentalComposeUiApi::class)
    fun read(scene: ImageComposeScene): ByteArray = scene.render(System.nanoTime()).use { image ->
        check(image.readPixels(bitmap, left, top))
        checkNotNull(bitmap.readPixels())
    }

    override fun close() = bitmap.close()
}

@OptIn(ExperimentalComposeUiApi::class)
private suspend fun ImageComposeScene.reference(pixels: PixelReader): ByteArray {
    val started = System.nanoTime()
    var changed = started
    var previous: ByteArray? = null
    while (true) {
        val frame = pixels.read(this)
        val now = System.nanoTime()
        if (previous?.contentEquals(frame) != true) changed = now
        previous = frame
        if (now - started >= 2_000_000_000L && now - changed >= 1_000_000_000L && !hasInvalidations()) return frame
        check(now - started < 30_000_000_000L) { "Reference did not settle" }
        delay(16)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private suspend fun ImageComposeScene.awaitPixels(pixels: PixelReader, target: ByteArray) {
    val started = System.nanoTime()
    while (true) {
        if (pixels.read(this).contentEquals(target)) return
        check(System.nanoTime() - started < 30_000_000_000L) { "Material failed to reach its exact reference pixels within 30 seconds" }
        delay(4)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun ImageComposeScene.setProgress(label: String, value: Float) {
    val slider = nodes().single {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true &&
            it.config.contains(SemanticsActions.SetProgress)
    }
    check(slider.config[SemanticsActions.SetProgress].action?.invoke(value) == true)
}

@OptIn(ExperimentalComposeUiApi::class)
private fun ImageComposeScene.nodes(): List<SemanticsNode> {
    fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
    return semanticsOwners.flatMap { descendants(it.unmergedRootSemanticsNode) }
}

private fun millisSince(started: Long): Double = (System.nanoTime() - started) / 1_000_000.0
private fun Double.format(): String = String.format(Locale.US, "%.2f", this)
private fun List<Double>.percentile(fraction: Double): Double = sorted()[(ceil(size * fraction).toInt() - 1).coerceIn(indices)]
