package dev.mttmcknn.seaglass.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.mttmcknn.seaglass.sample.LongListSnapshot
import dev.mttmcknn.seaglass.sample.SeaGlassLongListDiagnostics
import dev.mttmcknn.seaglass.sample.SeaGlassPlayground
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat

private const val LongListWidth = 520
private const val LongListHeight = 1000
private const val LongListDataset = "coastal-288-v1"

/**
 * Behavioral and exact-image verification, not a frame-rate or memory benchmark.
 *
 * Run once with the baseline renderer, then in another process with the candidate
 * and the baseline output directory as longListReferences. The same sample, fixed
 * viewport and semantic scroll sequence produce matching PNG checkpoints. Normal
 * scroll actions exercise lazy disposal/revisit; direct accessibility jumps also
 * cover the final item and all six action families without rendering 288 rows.
 *
 * Pixel readback occurs only at checkpoints, using one reusable bitmap. Quiet
 * waits are deliberately outside any performance measurement. Bounded semantics
 * verify lazy mounting, not retained native resources or heap size.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) = runBlocking {
    val output = Path.of(args.getOrNull(0) ?: "build/long-list-verification")
    val references = args.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(Path::of)
    require(references == null || output.toAbsolutePath().normalize() != references.toAbsolutePath().normalize()) {
        "Candidate output must not overwrite the baseline reference directory"
    }
    Files.createDirectories(output)
    val checkpoints = mutableListOf<String>()
    val comparisonFailures = mutableListOf<String>()
    val scene = ImageComposeScene(LongListWidth, LongListHeight, Density(1f), coroutineContext) {
        SeaGlassPlayground(initialScene = "long-list", enableScrollDiagnostics = true)
    }
    try {
        LongListPixels().use { pixels ->
            suspend fun checkpoint(name: String): ByteArray {
                val settled = scene.longListSettle(pixels)
                val snapshot = scene.assertLazyRows()
                val path = output.resolve("$name.png")
                pixels.writePng(scene, settled, path)
                val mounted = scene.mountedRows()
                val exact = references?.let { reference ->
                    compareLongListImages(reference.resolve("$name.png"), path).also { failure ->
                        if (failure != null) comparisonFailures += "$name: $failure"
                    } == null
                }
                checkpoints += """{"name":${name.jsonQuoted()},"png":${path.fileName.toString().jsonQuoted()},"rgbaSha256":${settled.sha256().jsonQuoted()},"datasetId":${snapshot.datasetId.jsonQuoted()},"firstVisibleItemIndex":${snapshot.firstVisibleItemIndex},"firstVisibleItemScrollOffset":${snapshot.firstVisibleItemScrollOffset},"visibleKeys":[${snapshot.visibleKeys.joinToString(",") { it.jsonQuoted() }}],"mountedRowIndices":[${mounted.joinToString(",")}],"referenceExact":${exact ?: "null"}}"""
                println("CHECKPOINT $name: item=${snapshot.firstVisibleItemIndex}, offset=${snapshot.firstVisibleItemScrollOffset}, visible=${snapshot.visibleKeys}, mounted=${mounted.size}, referenceExact=${exact ?: "not compared"}")
                return settled
            }

            val initial = checkpoint("initial")
            check(SeaGlassLongListDiagnostics.snapshot.visibleKeys.first() == "collection-intro")
            check(scene.longListNodes().any { it.config.contains(SemanticsProperties.Heading) }) {
                "The collection introduction must expose heading semantics"
            }
            scene.assertListButton("long-list-save-0", "Save ", "Not saved").longListClick()
            scene.longListPump()
            scene.assertListButton("long-list-save-0", "Unsave ", "Saved")
            scene.verifyRowDialog("long-list-open-0", 1)
            // Navigate to controls rather than assuming how many variable-height
            // cards fit below the introduction at this viewport and font scale.
            scene.jumpToListItem(2)
            scene.assertListButton("long-list-play-1", "Play ", "Paused").longListClick()
            scene.longListPump()
            scene.assertListButton("long-list-play-1", "Pause ", "Playing")
            scene.jumpToListItem(3)
            scene.verifyRowDialog("long-list-action-2-0", 3)
            println("PASS: Note, listening and action rows expose working controls and accessible state.")

            // Item zero is the introduction, so item four is collection row three.
            scene.jumpToListItem(4)
            checkpoint("mixed-rows")
            scene.verifyRowDialog("long-list-open-3", 4)
            scene.verifyRowDialog("long-list-pebble-4-0", 5)
            scene.assertListButton("long-list-reminder-5", "Complete ", "Not completed").longListClick()
            scene.longListPump()
            scene.assertListButton("long-list-reminder-5", "Mark ", "Completed")
            println("PASS: Walk, pebble and reminder controls remain accessible and functional.")

            repeat(6) { scene.scrollListBy(720f) }
            val forwardPosition = SeaGlassLongListDiagnostics.snapshot.position()
            checkpoint("forward")
            check(scene.mountedRows().none { it in setOf(0, 1, 5) }) {
                "Initial stateful rows must actually leave the mounted semantics tree"
            }
            repeat(2) { scene.scrollListBy(-720f) }
            check(SeaGlassLongListDiagnostics.snapshot.position() < forwardPosition) {
                "Reverse scrolling must move toward earlier rows"
            }
            checkpoint("reverse")
            println("PASS: Forward and reverse scrolling move through a bounded set of mounted rows.")

            scene.jumpToListItem(288)
            checkpoint("end")
            check("collection-287" in SeaGlassLongListDiagnostics.snapshot.visibleKeys) {
                "The final collection row must be reachable"
            }
            check(scene.hasListText("A good place to begin again.")) { "The list footer must be reachable" }
            scene.jumpToListItem(4)
            scene.assertListButton("long-list-reminder-5", "Mark ", "Completed").longListClick()
            scene.longListPump()
            scene.assertListButton("long-list-reminder-5", "Complete ", "Not completed")

            scene.assertListButton("long-list-top", "Back to top").longListClick()
            scene.awaitListPosition { it.firstVisibleItemIndex == 0 && it.firstVisibleItemScrollOffset == 0 }
            scene.assertListButton("long-list-save-0", "Unsave ", "Saved").longListClick()
            scene.assertListButton("long-list-play-1", "Pause ", "Playing").longListClick()
            scene.longListPump()
            scene.assertListButton("long-list-save-0", "Save ", "Not saved")
            scene.assertListButton("long-list-play-1", "Play ", "Paused")
            check(initial.contentEquals(checkpoint("restored-top"))) {
                "Restoring controls and returning to Top must reproduce the initial pixels exactly"
            }
            println("PASS: Stable item state survives disposal/revisit; Top restores the exact initial view.")

            scene.assertListButton("long-list-back").longListClick()
            scene.awaitListCondition { !SeaGlassLongListDiagnostics.snapshot.active }
            scene.longListTagged("playground-long-list").longListClick()
            scene.awaitListPosition { it.firstVisibleItemIndex == 0 && it.firstVisibleItemScrollOffset == 0 }
            check(scene.longListNodes().none {
                it.config.getOrNull(SemanticsProperties.TestTag) == "playground-controls"
            }) { "The standalone list must not be nested inside the playground controls" }
            scene.assertLazyRows()
            println("PASS: Back returns to the playground; its list entry reopens the standalone list.")
        }
        Files.writeString(output.resolve("checkpoints.json"), """
            {
              "datasetId": "$LongListDataset",
              "width": $LongListWidth,
              "height": $LongListHeight,
              "density": 1.0,
              "purpose": "behavior and exact-pixel verification; not performance timing",
              "checkpoints": [${checkpoints.joinToString(",\n")}],
              "comparisonFailures": [${comparisonFailures.joinToString(",") { it.jsonQuoted() }}]
            }
        """.trimIndent() + "\n")
        check(comparisonFailures.isEmpty()) { comparisonFailures.joinToString("\n") }
        println("PASS: Long-list verification complete; ${checkpoints.size} PNG checkpoints written to $output")
    } finally {
        scene.close()
    }
}

private class LongListPixels : AutoCloseable {
    private val bitmap = Bitmap().apply { check(allocN32Pixels(LongListWidth, LongListHeight)) }

    @OptIn(ExperimentalComposeUiApi::class)
    fun read(scene: ImageComposeScene): ByteArray = scene.render(System.nanoTime()).use { image ->
        check(image.readPixels(bitmap))
        checkNotNull(bitmap.readPixels())
    }

    @OptIn(ExperimentalComposeUiApi::class)
    fun writePng(scene: ImageComposeScene, expected: ByteArray, path: Path) {
        scene.render(System.nanoTime()).use { image ->
            check(image.readPixels(bitmap))
            check(expected.contentEquals(checkNotNull(bitmap.readPixels()))) { "Checkpoint changed while saving $path" }
            checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { data -> Files.write(path, data.bytes) }
        }
    }

    override fun close() = bitmap.close()
}

@OptIn(ExperimentalComposeUiApi::class)
private suspend fun ImageComposeScene.longListSettle(pixels: LongListPixels): ByteArray {
    val start = System.nanoTime()
    var changed = start
    var previous: ByteArray? = null
    while (true) {
        val frame = pixels.read(this)
        val now = System.nanoTime()
        if (previous?.contentEquals(frame) != true) changed = now
        previous = frame
        if (now - start >= 2_000_000_000L && now - changed >= 1_000_000_000L && !hasInvalidations() &&
            !SeaGlassLongListDiagnostics.snapshot.isScrollInProgress) return frame
        check(now - start < 30_000_000_000L) { "Long-list checkpoint did not settle within 30 seconds" }
        delay(75)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private suspend fun ImageComposeScene.longListPump() {
    render(System.nanoTime()).close()
    yield()
    render(System.nanoTime()).close()
    yield()
}

@OptIn(ExperimentalComposeUiApi::class)
private suspend fun ImageComposeScene.awaitListCondition(condition: () -> Boolean) {
    val start = System.nanoTime()
    var matchingFrames = 0
    while (matchingFrames < 3) {
        longListPump()
        matchingFrames = if (condition()) matchingFrames + 1 else 0
        check(System.nanoTime() - start < 10_000_000_000L) { "Long-list interaction did not complete within 10 seconds" }
        delay(16)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private suspend fun ImageComposeScene.awaitListPosition(condition: (LongListSnapshot) -> Boolean) =
    awaitListCondition {
        val state = SeaGlassLongListDiagnostics.snapshot
        state.active && !state.isScrollInProgress && condition(state)
    }

private fun LongListSnapshot.position(): Long = firstVisibleItemIndex * 1_000_000L + firstVisibleItemScrollOffset

@OptIn(ExperimentalComposeUiApi::class)
private suspend fun ImageComposeScene.scrollListBy(pixels: Float) {
    val before = SeaGlassLongListDiagnostics.snapshot.position()
    check(longListTagged("long-list-scroll").config[SemanticsActions.ScrollBy].action?.invoke(0f, pixels) == true)
    awaitListPosition { if (pixels > 0) it.position() > before else it.position() < before }
    assertLazyRows()
}

@OptIn(ExperimentalComposeUiApi::class)
private suspend fun ImageComposeScene.jumpToListItem(index: Int) {
    check(longListTagged("long-list-scroll").config[SemanticsActions.ScrollToIndex].action?.invoke(index) == true)
    awaitListPosition { "collection-${index - 1}" in it.visibleKeys }
    assertLazyRows()
}

@OptIn(ExperimentalComposeUiApi::class)
private suspend fun ImageComposeScene.verifyRowDialog(tag: String, moment: Int) {
    assertListButton(tag).longListClick()
    awaitListCondition { longListNodes().any { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { "coastal collection, moment $moment." in it.text } == true
    } }
    longListNodes().single {
        it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Done" } == true
    }.longListClick()
    awaitListCondition { !hasListText("Done") }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun ImageComposeScene.assertListButton(tag: String, descriptionPrefix: String? = null, state: String? = null): SemanticsNode {
    val node = longListTagged(tag)
    check(node.longListClickTarget().config.getOrNull(SemanticsProperties.Role) == Role.Button) {
        "$tag must expose a button role"
    }
    if (descriptionPrefix != null) check(node.config.getOrNull(SemanticsProperties.ContentDescription)
        ?.any { it.startsWith(descriptionPrefix) } == true) { "$tag must expose the current accessible action" }
    if (state != null) check(node.config.getOrNull(SemanticsProperties.StateDescription) == state) {
        "$tag must expose state $state"
    }
    return node
}

@OptIn(ExperimentalComposeUiApi::class)
private fun ImageComposeScene.assertLazyRows(): LongListSnapshot {
    val snapshot = SeaGlassLongListDiagnostics.snapshot
    check(snapshot.active && snapshot.datasetId == LongListDataset) {
        "Expected active $LongListDataset diagnostics; ensure enableScrollDiagnostics is true (got $snapshot)"
    }
    check(snapshot.visibleKeys.isNotEmpty() && snapshot.visibleKeys.distinct().size == snapshot.visibleKeys.size)
    val rows = mountedRows()
    // At this fixed viewport, 24 leaves ample room for prefetch and partially
    // visible rows but catches replacing the bounded LazyColumn with all rows.
    check(rows.size in 1..24 && rows.distinct().size == rows.size) {
        "Expected a bounded set of unique mounted rows, got ${rows.size}: $rows"
    }
    check(rows.all { it in 0..287 }) { "Unexpected row identities: $rows" }
    snapshot.visibleKeys.filter { it != "collection-intro" && it != "collection-end" }.forEach { key ->
        val row = key.removePrefix("collection-").toIntOrNull()
        check(row != null && row in rows) { "Visible lazy key $key must match its mounted row identity" }
    }
    return snapshot
}

@OptIn(ExperimentalComposeUiApi::class)
private fun ImageComposeScene.mountedRows(): List<Int> = longListNodes().mapNotNull { node ->
    node.config.getOrNull(SemanticsProperties.TestTag)?.takeIf { it.startsWith("long-list-row-") }
        ?.removePrefix("long-list-row-")?.toInt()
}.sorted()

@OptIn(ExperimentalComposeUiApi::class)
private fun ImageComposeScene.longListNodes(): List<SemanticsNode> {
    fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
    return semanticsOwners.flatMap { descendants(it.unmergedRootSemanticsNode) }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun ImageComposeScene.longListTagged(tag: String): SemanticsNode = longListNodes().single {
    it.config.getOrNull(SemanticsProperties.TestTag) == tag
}

@OptIn(ExperimentalComposeUiApi::class)
private fun ImageComposeScene.hasListText(value: String): Boolean = longListNodes().any {
    it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true
}

private fun SemanticsNode.longListClickTarget(): SemanticsNode =
    generateSequence(this) { it.parent }.first { it.config.contains(SemanticsActions.OnClick) }

private fun SemanticsNode.longListClick() {
    check(longListClickTarget().config[SemanticsActions.OnClick].action?.invoke() == true)
}

private fun compareLongListImages(reference: Path, candidate: Path): String? {
    if (!Files.isRegularFile(reference)) return "Missing baseline PNG: $reference"
    val before = ImageIO.read(reference.toFile()) ?: return "Unreadable baseline PNG: $reference"
    val after = checkNotNull(ImageIO.read(candidate.toFile()))
    if (before.width != after.width || before.height != after.height) return "PNG dimensions differ"
    val original = before.getRGB(0, 0, before.width, before.height, null, 0, before.width)
    val current = after.getRGB(0, 0, after.width, after.height, null, 0, after.width)
    val changed = original.indices.count { original[it] != current[it] }
    return if (changed == 0) null else "$changed / ${original.size} pixels changed"
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this)
    .joinToString("") { "%02x".format(it) }

private fun String.jsonQuoted(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"")
    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
