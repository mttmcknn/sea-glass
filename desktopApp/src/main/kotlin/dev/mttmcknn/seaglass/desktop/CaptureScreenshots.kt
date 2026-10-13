package dev.mttmcknn.seaglass.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.mttmcknn.seaglass.sample.SeaGlassPlayground
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat

/**
 * Real headless Compose/Skia screenshots, including text, layout, and asynchronous material jobs.
 * No Android emulator or desktop window is needed. Captures use two physical pixels per dp.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) = runBlocking {
    val output = File(args.firstOrNull() ?: "docs/screenshots").apply { mkdirs() }
    val captures = listOf(
        Capture("playground-desktop", 1180, 1280) { SeaGlassPlayground() },
        Capture("playground-narrow", 420, 1840) { SeaGlassPlayground() },
        Capture("playground-tablet", 900, 900) { SeaGlassPlayground() },
        Capture("playground-foldable", 700, 840) { SeaGlassPlayground() },
        Capture("playground-split", 600, 840) { SeaGlassPlayground() },
        Capture("playground-compact", 420, 840) { SeaGlassPlayground() },
        Capture("composable-specimens", 1400, 1000) { SpecimenGallery() },
        Capture("tumble-bulge", 1400, 1000) { TumbleBulgeGallery() },
        Capture("material-recipes", 1400, 1000) { MaterialRecipesGallery() },
        Capture("theme-styles", 1400, 1000) { ThemeStylesGallery() },
    )
    val selected = args.getOrNull(1)?.split(',')?.let { names -> captures.filter { it.name in names } } ?: captures
    require(selected.isNotEmpty()) { "Unknown screenshot: ${args.getOrNull(1)}" }
    for (capture in selected) {
        val scene = ImageComposeScene(
            width = capture.width * 2,
            height = capture.height * 2,
            density = Density(2f),
            coroutineContext = coroutineContext,
            content = capture.content,
        )
        try {
            val started = System.nanoTime()
            var previousBytes: ByteArray? = null
            var unchangedSince = started
            var frames = 0
            while (true) {
                val now = System.nanoTime()
                val bytes = scene.render(now - started).use { image ->
                    checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { it.bytes }
                }
                frames++
                if (previousBytes?.contentEquals(bytes) != true) unchangedSince = now
                previousBytes = bytes
                // A scene can be briefly idle while produceState renders on Dispatchers.Default.
                // Require an initial warm-up plus a sustained unchanged image, and fail on timeout.
                if (now - started >= 3_000_000_000L && now - unchangedSince >= 1_500_000_000L && !scene.hasInvalidations()) {
                    val file = File(output, "${capture.name}.png")
                    file.writeBytes(bytes)
                    println("Captured ${file.absolutePath} (${capture.width * 2}x${capture.height * 2} pixels at 2x, $frames frames)")
                    break
                }
                check(now - started < 30_000_000_000L) { "${capture.name} did not settle within 30 seconds" }
                delay(50)
            }
        } finally {
            scene.close()
        }
    }
}

private data class Capture(
    val name: String,
    val width: Int,
    val height: Int,
    val content: @Composable () -> Unit,
)
