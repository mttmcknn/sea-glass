package dev.mttmcknn.seaglass.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import dev.mttmcknn.seaglass.sample.SeaGlassPlayground
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat

/** Verifies user-visible behavior with the same native composition and renderer as the app. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() = runBlocking {
    val scene = ImageComposeScene(
        width = 700,
        height = 840,
        density = Density(1f),
        coroutineContext = coroutineContext,
        content = { SeaGlassPlayground() },
    )
    try {
        val before = scene.settle()
        val preview = scene.tagged("playground-preview")
        val controls = scene.tagged("playground-controls")
        check(preview.boundsInRoot.right <= controls.boundsInRoot.left) {
            "The component preview must appear to the left of the controls"
        }
        val previewScroll = preview.config[SemanticsProperties.VerticalScrollAxisRange].value()
        val controlsScroll = controls.config[SemanticsProperties.VerticalScrollAxisRange].value()
        check(controls.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0) {
            "Controls should have enough content to exercise independent scrolling"
        }
        check(controls.config[SemanticsActions.ScrollBy].action?.invoke(0f, 220f) == true)
        val scrolled = scene.settle()
        check(scene.tagged("playground-controls").config[SemanticsProperties.VerticalScrollAxisRange].value() > controlsScroll)
        check(scene.tagged("playground-preview").config[SemanticsProperties.VerticalScrollAxisRange].value() == previewScroll) {
            "Scrolling controls must not scroll the components"
        }
        val pane = preview.boundsInRoot
        val left = pane.left.roundToInt().coerceAtLeast(0)
        val top = pane.top.roundToInt().coerceAtLeast(0)
        val width = pane.width.roundToInt().coerceAtMost(before.width - left)
        val height = pane.height.roundToInt().coerceAtMost(before.height - top)
        fun previewPixels(image: BufferedImage) = image.getRGB(left, top, width, height, null, 0, width)
        check(previewPixels(before).contentEquals(previewPixels(scrolled))) {
            "Scrolling controls must leave the visible preview unchanged"
        }

        val tumble = .055f
        check(scene.tumble().config[SemanticsActions.SetProgress].action?.invoke(tumble) == true)
        val changed = scene.settle()
        scene.assertTumble(tumble)
        check(!previewPixels(scrolled).contentEquals(previewPixels(changed))) {
            "Changing Tumble must visibly redraw the material in the component pane"
        }
        println("PASS: Controls scroll independently and Tumble updates the visible components.")

        for (widthDp in listOf(600, 599, 700)) {
            scene.constraints = Constraints.fixed(widthDp, 840)
            scene.settle()
            scene.assertTumble(tumble)
            val hasTwoPanes = scene.nodes().any {
                it.config.getOrNull(SemanticsProperties.TestTag) == "playground-controls"
            }
            check(hasTwoPanes == (widthDp >= 600)) {
                "Expected ${if (widthDp >= 600) "two panes" else "a compact layout"} at $widthDp dp"
            }
        }
        println("PASS: 600 → 599 → 700 dp switches layout without losing the selected recipe.")

        scene.tagged("playground-scene-selector").click()
        scene.settle()
        val musicItem = scene.nodes().single {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Music player" } == true
        }
        musicItem.click()
        scene.settle()
        check(scene.hasText("Evening tide")) { "Selecting Music player must show the music scene" }
        scene.assertTumble(tumble)
        scene.described("Pause").click()
        scene.settle()
        scene.described("Play")

        scene.tagged("playground-preview-toggle").click()
        scene.settle()
        check(scene.hasText("Evening tide")) { "Preview only must keep the selected scene" }
        scene.described("Play")
        check(scene.nodes().none {
            it.config.getOrNull(SemanticsProperties.TestTag) in setOf("playground-controls", "playground-scene-selector")
        }) { "Preview only must hide the editing controls and scene selector" }
        val exitPreview = scene.tagged("playground-showcase").config[SemanticsActions.CustomActions]
            .single { it.label == "Show controls" }
        check(exitPreview.action())
        scene.settle()
        scene.tagged("playground-controls")
        check(scene.hasText("Evening tide")) { "Leaving Preview only must keep the selected scene" }
        scene.described("Play")
        scene.assertTumble(tumble)
        println("PASS: Scene selection and Preview only keep the selected scene and recipe while toggling editing controls.")
    } finally {
        scene.close()
    }
}

private fun SemanticsNode.click() {
    val clickable = generateSequence(this) { it.parent }.first { it.config.contains(SemanticsActions.OnClick) }
    check(clickable.config[SemanticsActions.OnClick].action?.invoke() == true)
}

@OptIn(ExperimentalComposeUiApi::class)
private fun ImageComposeScene.hasText(value: String): Boolean = nodes().any {
    it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true
}

@OptIn(ExperimentalComposeUiApi::class)
private fun ImageComposeScene.nodes(): List<SemanticsNode> {
    fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
    return semanticsOwners.flatMap { descendants(it.unmergedRootSemanticsNode) }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun ImageComposeScene.tagged(tag: String): SemanticsNode = nodes().single {
    it.config.getOrNull(SemanticsProperties.TestTag) == tag
}

@OptIn(ExperimentalComposeUiApi::class)
private fun ImageComposeScene.described(description: String): SemanticsNode = nodes().single {
    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true
}

@OptIn(ExperimentalComposeUiApi::class)
private fun ImageComposeScene.tumble(): SemanticsNode = nodes().single {
    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Tumble") == true &&
        it.config.contains(SemanticsActions.SetProgress)
}

@OptIn(ExperimentalComposeUiApi::class)
private fun ImageComposeScene.assertTumble(expected: Float) {
    val actual = tumble().config[SemanticsProperties.ProgressBarRangeInfo].current
    check(abs(actual - expected) < .0001f) { "Tumble should stay at $expected, got $actual" }
}

@OptIn(ExperimentalComposeUiApi::class)
private suspend fun ImageComposeScene.settle(): BufferedImage {
    val started = System.nanoTime()
    var unchangedSince = started
    var previous: ByteArray? = null
    while (true) {
        val now = System.nanoTime()
        val bytes = render(now).use { image ->
            checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { it.bytes }
        }
        if (previous?.contentEquals(bytes) != true) unchangedSince = now
        previous = bytes
        if (now - started >= 2_000_000_000L && now - unchangedSince >= 1_000_000_000L && !hasInvalidations()) {
            return ImageIO.read(ByteArrayInputStream(bytes))
        }
        check(now - started < 30_000_000_000L) { "Playground did not settle within 30 seconds" }
        delay(50)
    }
}
