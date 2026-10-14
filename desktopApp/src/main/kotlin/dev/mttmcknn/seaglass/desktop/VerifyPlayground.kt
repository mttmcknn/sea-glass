package dev.mttmcknn.seaglass.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.mttmcknn.seaglass.SeaGlassButton
import dev.mttmcknn.seaglass.sample.SeaGlassPlayground
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.jetbrains.skia.EncodedImageFormat

/** Verifies user-visible behavior with the same native composition and renderer as the app. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() = runBlocking {
    verifyButtonKeyboardInteractions()
    val scene = ImageComposeScene(
        width = 700,
        height = 840,
        density = Density(1f),
        coroutineContext = coroutineContext,
        content = {
            SeaGlassPlayground(
                materialColorScheme = lightColorScheme(
                    primaryContainer = Color(0xffffb4e6),
                    onPrimaryContainer = Color(0xff481137),
                ),
                materialColorSource = "Verification theme colors",
            )
        },
    )
    try {
        val before = scene.settle()
        scene.assertMaterialYou(false)
        scene.assertSelectedPalette("Mix")
        val initialSeed = scene.shapeSeed()
        val initialTumble = scene.tumble().config[SemanticsProperties.ProgressBarRangeInfo].current
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

        scene.textNode("Tumble again").click()
        scene.settle()
        val selectedSeed = scene.shapeSeed()
        check(selectedSeed != initialSeed) { "Tumble again must select a different shape seed" }
        scene.described("Choose Mint palette").click()
        val mint = scene.settle()
        scene.assertSelectedPalette("Mint")
        val materialBounds = scene.described("Choose Mint glass").boundsInRoot
        val materialLeft = materialBounds.left.roundToInt().coerceAtLeast(0)
        val materialTop = materialBounds.top.roundToInt().coerceAtLeast(0)
        val materialWidth = materialBounds.width.roundToInt().coerceAtMost(mint.width - materialLeft)
        val materialHeight = materialBounds.height.roundToInt().coerceAtMost(mint.height - materialTop)
        check(materialWidth > 0 && materialHeight > 0) { "A glass specimen must be visible for the color check" }
        fun materialPixels(image: BufferedImage) =
            image.getRGB(materialLeft, materialTop, materialWidth, materialHeight, null, 0, materialWidth)

        scene.tagged("playground-material-you").click()
        val themed = scene.settle()
        scene.assertMaterialYou(true)
        scene.assertSelectedPalette(null)
        check(scene.hasText("Verification theme colors")) { "Enabled Material You must identify its color source" }
        check(!materialPixels(mint).contentEquals(materialPixels(themed))) {
            "Material You must recolor the glass itself, beyond changing the switch"
        }
        scene.assertTumble(tumble)
        check(scene.shapeSeed() == selectedSeed) { "Material You must preserve the shape seed" }
        scene.tagged("playground-material-you").click()
        val restored = scene.settle()
        scene.assertMaterialYou(false)
        scene.assertSelectedPalette("Mint")
        check(previewPixels(mint).contentEquals(previewPixels(restored))) {
            "Turning Material You off must restore the selected Mint palette and material"
        }
        scene.tagged("playground-material-you").click()
        scene.settle()
        println("PASS: Material You recolors glass and restores the selected palette without changing the recipe or seed.")

        for (widthDp in listOf(600, 599, 700)) {
            scene.constraints = Constraints.fixed(widthDp, 840)
            scene.settle()
            scene.assertTumble(tumble)
            scene.assertMaterialYou(true)
            check(scene.shapeSeed() == selectedSeed) { "Resizing must preserve the shape seed" }
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
        scene.tagged("playground-material-you").click()
        scene.settle()
        scene.assertMaterialYou(false)
        scene.described("Play")
        check(scene.hasText("Evening tide")) { "Changing Material You must preserve the selected scene" }
        scene.tagged("playground-material-you").click()
        scene.settle()
        scene.assertMaterialYou(true)
        scene.described("Play")

        scene.focusByTab { scene.tagged("playground-preview-toggle") }
        scene.pressKey(Key.Enter)
        scene.settle()
        check(scene.hasText("Evening tide")) { "Preview only must keep the selected scene" }
        scene.described("Play")
        check(scene.nodes().none {
            it.config.getOrNull(SemanticsProperties.TestTag) in setOf("playground-controls", "playground-scene-selector")
        }) { "Preview only must hide the editing controls and scene selector" }
        check(scene.tagged("playground-showcase").config[SemanticsActions.CustomActions]
            .any { it.label == "Show controls" }) { "Preview must retain its accessibility exit action" }
        scene.focusByTab { scene.described("Play") }
        scene.pressKey(Key.Escape)
        scene.settle()
        scene.tagged("playground-controls")
        scene.tagged("playground-preview-toggle").assertFocused()
        check(scene.hasText("Evening tide")) { "Leaving Preview only must keep the selected scene" }
        scene.described("Play")
        scene.assertTumble(tumble)
        scene.assertMaterialYou(true)
        check(scene.shapeSeed() == selectedSeed) { "Preview only must preserve the shape seed" }
        println("PASS: Keyboard preview entry/exit restores focus and preserves the selected scene, playback state, and recipe.")

        scene.described("Choose Mint palette").click()
        scene.settle()
        scene.assertMaterialYou(false)
        scene.assertSelectedPalette("Mint")
        scene.assertTumble(tumble)
        check(scene.shapeSeed() == selectedSeed) { "Selecting a palette must preserve the shape seed" }
        scene.tagged("playground-material-you").click()
        scene.settle()
        scene.assertMaterialYou(true)
        scene.textNode("Reset").click()
        scene.settle()
        scene.assertMaterialYou(false)
        scene.assertSelectedPalette("Mix")
        scene.assertTumble(initialTumble)
        check(scene.shapeSeed() == initialSeed) { "Reset must restore the initial shape seed" }
        println("PASS: Choosing a palette leaves Material You; Reset restores the default material and disables Material You.")

        scene.verifyStudyPreviewState()
    } finally {
        scene.close()
    }
}

/** A small UI contract test: normal keyboard input, one disabled control, and callback counts. */
@OptIn(ExperimentalComposeUiApi::class)
private suspend fun verifyButtonKeyboardInteractions() {
    val enabled = mutableStateOf(true)
    var firstClicks = 0
    var disabledClicks = 0
    var lastClicks = 0
    val scene = ImageComposeScene(width = 360, height = 280, density = Density(1f), coroutineContext = coroutineContext) {
        MaterialTheme {
            Column(Modifier.padding(24.dp)) {
                SeaGlassButton(onClick = { firstClicks++ }, enabled = enabled.value,
                    modifier = Modifier.testTag("keyboard-first")) { Text("First") }
                SeaGlassButton(onClick = { disabledClicks++ }, enabled = false,
                    modifier = Modifier.testTag("keyboard-disabled")) { Text("Disabled") }
                SeaGlassButton(onClick = { lastClicks++ },
                    modifier = Modifier.testTag("keyboard-last")) { Text("Last") }
            }
        }
    }
    try {
        scene.advanceFrame()
        val first = scene.tagged("keyboard-first")
        check(first.config[SemanticsProperties.Role] == Role.Button)
        check(scene.tagged("keyboard-disabled").config.contains(SemanticsProperties.Disabled))
        scene.pressKey(Key.Tab)
        scene.advanceFrame()
        scene.tagged("keyboard-first").assertFocused()
        scene.pressKey(Key.Enter)
        scene.advanceFrame()
        check(firstClicks == 1) { "Enter must activate the focused SeaGlassButton exactly once" }
        scene.pressKey(Key.Tab)
        scene.advanceFrame()
        scene.tagged("keyboard-last").assertFocused()
        scene.pressKey(Key.Enter)
        scene.advanceFrame()
        check(lastClicks == 1 && disabledClicks == 0) { "Keyboard traversal must skip disabled buttons" }

        // Disabling the focused button must prevent a subsequent key activation.
        scene.focusByTab { scene.tagged("keyboard-first") }
        enabled.value = false
        scene.advanceFrame()
        check(scene.tagged("keyboard-first").config.contains(SemanticsProperties.Disabled))
        scene.pressKey(Key.Enter)
        scene.advanceFrame()
        check(firstClicks == 1) { "A disabled SeaGlassButton must not activate" }
        check(disabledClicks == 0)
        println("PASS: SeaGlassButton supports keyboard focus and activation, and disabled controls neither activate nor receive traversal focus.")
    } finally {
        scene.close()
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private suspend fun ImageComposeScene.verifyStudyPreviewState() {
    selectScene("Components")
    described("Choose Mint palette").click()
    settle()
    assertSelectedPalette("Mint")
    tagged("playground-preview").config[SemanticsActions.ScrollBy].action?.invoke(0f, 10_000f)
    val components = settle()
    val componentColor = components.centerColor(described("Choose Amber glass"))
    tagged("playground-preview-toggle").click()
    val componentPreview = settle()
    componentPreview.centerColor(described("Choose Amber glass")).assertColorMatches(componentColor, "Components preview")
    // The colored specimen remains an action while controls are hidden.
    described("Choose Amber glass").click()
    settle()
    pressKey(Key.Escape)
    settle()
    assertSelectedPalette("Amber")
    tagged("playground-preview-toggle").assertFocused()

    selectScene("Shape study")
    described("Choose Mint palette").click()
    settle()
    tagged("playground-preview").config[SemanticsActions.ScrollBy].action?.invoke(0f, -10_000f)
    val shapes = settle()
    val shapeColor = shapes.centerColor(tagged("shape-study-Rounded-0"))
    tagged("playground-preview-toggle").click()
    val shapePreview = settle()
    shapePreview.centerColor(tagged("shape-study-Rounded-0")).assertColorMatches(shapeColor, "Shape study preview")
    pressKey(Key.Escape)
    settle()
    assertSelectedPalette("Mint")
    println("PASS: Components and Shape study retain the selected material color in preview; preview palette actions update the controls.")

    selectScene("Fieldnotes")
    textNode("Save note").click()
    settle()
    tagged("playground-preview-toggle").click()
    settle()
    check(hasText("Saved")) { "Fieldnotes preview must preserve the saved note" }
    textNode("Saved").click()
    settle()
    check(hasText("Save note")) { "The save action must remain active inside preview" }
    textNode("New note").click()
    settle()
    check(hasText("Collect a small moment")) { "New note must open the composer from preview" }
    textNode("Cancel").click()
    settle()
    pressKey(Key.Escape)
    settle()
    check(hasText("Save note")) { "Changes made in preview must survive returning to controls" }
    tagged("playground-preview-toggle").assertFocused()
    println("PASS: Fieldnotes preserves saved state and working save/compose callbacks through preview transitions.")
}

@OptIn(ExperimentalComposeUiApi::class)
private suspend fun ImageComposeScene.selectScene(title: String) {
    tagged("playground-scene-selector").click()
    settle()
    textNode(title).click()
    settle()
}

private fun BufferedImage.centerColor(node: SemanticsNode): List<Double> {
    val bounds = node.boundsInRoot
    val centerX = bounds.center.x.roundToInt()
    val centerY = bounds.center.y.roundToInt()
    check(bounds.width >= 32f && bounds.height >= 32f && centerX - 8 >= 0 && centerX + 8 <= width &&
        centerY - 8 >= 0 && centerY + 8 <= height) { "The material center must be visible for color comparison: $bounds" }
    val color = listOf(16, 8, 0).map { shift ->
        var total = 0L
        for (y in centerY - 8 until centerY + 8) for (x in centerX - 8 until centerX + 8) {
            total += (getRGB(x, y) ushr shift) and 255
        }
        total / 256.0
    }
    check(color.min() < 245.0 && color.max() - color.min() > 5.0) {
        "The comparison must sample visible colored glass, not an empty background: $color"
    }
    return color
}

private fun List<Double>.assertColorMatches(expected: List<Double>, label: String) {
    // Compare identical material centers; average away subpixel placement/grain
    // differences between the two layouts without allowing a changed pigment.
    check(zip(expected).all { (actual, before) -> abs(actual - before) <= 2.0 }) {
        "$label must keep the selected pigment: before=$expected, preview=$this"
    }
}

private fun SemanticsNode.click() {
    check(clickTarget().config[SemanticsActions.OnClick].action?.invoke() == true)
}

private fun SemanticsNode.clickTarget(): SemanticsNode =
    generateSequence(this) { it.parent }.first { it.config.contains(SemanticsActions.OnClick) }

private fun SemanticsNode.assertFocused() {
    check(clickTarget().config.getOrNull(SemanticsProperties.Focused) == true) { "Expected the control to have keyboard focus" }
}

// Test-only event construction for the pinned Compose version; input still
// travels through the scene's real focus/key dispatch rather than callbacks.
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
private fun ImageComposeScene.pressKey(key: Key) {
    sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyDown))
    sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyUp))
}

@OptIn(ExperimentalComposeUiApi::class)
private suspend fun ImageComposeScene.advanceFrame() {
    render(System.nanoTime()).close()
    yield()
    render(System.nanoTime()).close()
    yield()
}

@OptIn(ExperimentalComposeUiApi::class)
private suspend fun ImageComposeScene.focusByTab(target: () -> SemanticsNode) {
    repeat(64) {
        advanceFrame()
        if (target().clickTarget().config.getOrNull(SemanticsProperties.Focused) == true) return
        pressKey(Key.Tab)
    }
    error("Keyboard traversal did not reach the requested control")
}

@OptIn(ExperimentalComposeUiApi::class)
private fun ImageComposeScene.assertSelectedPalette(name: String?) {
    val options = nodes().filter {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { label ->
            label.startsWith("Choose ") && label.endsWith(" palette")
        } == true
    }
    check(options.size == 6) { "All palette choices must be available to accessibility services" }
    for (option in options) {
        check(option.config[SemanticsProperties.Role] == Role.RadioButton)
        val selected = option.config[SemanticsProperties.ContentDescription].contains("Choose $name palette")
        check(option.config[SemanticsProperties.Selected] == selected) { "Palette selection must expose the current choice ($name)" }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun ImageComposeScene.hasText(value: String): Boolean = nodes().any {
    it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true
}

@OptIn(ExperimentalComposeUiApi::class)
private fun ImageComposeScene.textNode(value: String): SemanticsNode = nodes().single {
    it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true
}

@OptIn(ExperimentalComposeUiApi::class)
private fun ImageComposeScene.shapeSeed(): String = nodes().flatMap {
    it.config.getOrNull(SemanticsProperties.Text).orEmpty()
}.single { it.text.startsWith("Shape seed ") }.text

@OptIn(ExperimentalComposeUiApi::class)
private fun ImageComposeScene.assertMaterialYou(enabled: Boolean) {
    val toggle = tagged("playground-material-you")
    check(toggle.config[SemanticsProperties.Role] == Role.Switch) { "Material You must expose switch semantics" }
    val expected = if (enabled) ToggleableState.On else ToggleableState.Off
    check(toggle.config[SemanticsProperties.ToggleableState] == expected) {
        "Material You should be ${if (enabled) "enabled" else "disabled"}"
    }
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
