package dev.mttmcknn.seaglass.internal

import java.io.File
import java.lang.management.ManagementFactory
import java.util.Locale
import kotlin.math.ceil

/**
 * A deterministic CPU microbenchmark, deliberately separate from Android frame timing.
 * Run the identical command and sample count in a quiet environment before/after changes.
 * Cold means a fresh renderer cache, not a cold JVM. Warm cases keep one renderer per
 * component and change exactly the named setting each sample. Bitmap conversion is
 * timed separately; readback/checksums are outside both timers. No GPU draw is measured.
 */
fun main(args: Array<String>) {
    val output = File(args.firstOrNull() ?: error("Pass an output JSON path"))
    val samples = args.option("samples", 12).also { require(it >= 3) }
    val warmups = args.option("warmups", 4).also { require(it >= 0) }
    val fastGround = "--fast-ground" in args
    val cases = benchmarkCases()
    // Exercise every invalidation path before collecting any case. This also loads
    // Skia and keeps native initialization/JIT compilation out of the first result.
    val warmupDeadline = System.nanoTime() + 3_000_000_000L
    do {
        for (case in cases) {
            val operation = case.operation(fastGround)
            repeat(2) { index -> consume(operation(index)) }
        }
    } while (System.nanoTime() < warmupDeadline)

    val results = cases.map { case ->
        val operation = case.operation(fastGround)
        repeat(warmups) { index -> consume(operation(index)) }
        val timings = List(samples) { index -> operation(index + warmups).also(::consume) }
        println("${case.name.padEnd(27)} render ${timings.map { it.renderNs }.medianMs().format()} ms / p95 ${timings.map { it.renderNs }.p95Ms().format()} ms; upload ${timings.map { it.uploadNs }.medianMs().format()} ms")
        Result(case, timings)
    }
    output.absoluteFile.parentFile.mkdirs()
    output.writeText(buildReport(samples, warmups, fastGround, results))
    println("Renderer benchmark: ${output.absolutePath}; checksum sink=$checksumSink")
}

private data class Fixture(val width: Double, val height: Double, val shape: RenderShape, val seed: Int = 42) {
    fun render(renderer: SeaGlassRenderer, state: MaterialState): PixelBuffer = renderer.render(
        width, height, 2.0, shape, state.settings.copy(seed = seed), state.palette,
    )
}

private data class MaterialState(val settings: RenderSettings = RenderSettings(), val palette: RenderPalette = lagoon)

private data class BenchmarkCase(
    val name: String,
    val fixtures: List<Fixture>,
    val cold: Boolean = false,
    val stateAt: (Int) -> MaterialState = { MaterialState() },
) {
    fun operation(fastGround: Boolean): (Int) -> Timing {
        val renderers = fixtures.map { SeaGlassRenderer(composeMaskRasterizer, fastGround = fastGround) }
        if (!cold) fixtures.zip(renderers).forEach { (fixture, renderer) -> fixture.render(renderer, MaterialState()) }
        return { index ->
            val state = stateAt(index)
            val renderStart = System.nanoTime()
            val buffers = fixtures.mapIndexed { fixtureIndex, fixture ->
                fixture.render(if (cold) SeaGlassRenderer(composeMaskRasterizer, fastGround = fastGround) else renderers[fixtureIndex], state)
            }
            val renderEnd = System.nanoTime()
            val bitmaps = buffers.map { it.toImageBitmap() }
            val uploadEnd = System.nanoTime()
            // Consume all calculated pixels and read one native pixel per image.
            // Hashing outside the measured interval avoids charging an O(n) testing
            // tax to the render pipeline or concealing costs behind dead-code removal.
            var checksum = -3750763034362895579L
            for (buffer in buffers) {
                for (pixel in buffer.pixels) checksum = (checksum xor pixel.toLong()) * 1099511628211L
            }
            val pixel = IntArray(1)
            for (bitmap in bitmaps) {
                bitmap.readPixels(pixel, bitmap.width / 2, bitmap.height / 2, 1, 1)
                checksum = (checksum xor pixel[0].toLong()) * 1099511628211L
            }
            Timing(renderEnd - renderStart, uploadEnd - renderEnd, checksum)
        }
    }
}

private val lagoon = RenderPalette(0xB5D0EB, 0xD6E9FF, 0x2F647C)
private val mint = RenderPalette(0xC0DED0, 0xE6FFF0, 0x396C5A)
private val amber = RenderPalette(0xF1D4BB, 0xFFEFCC, 0x795334)

private fun benchmarkCases(): List<BenchmarkCase> {
    val button = Fixture(280.0, 86.0, RenderShape.Rounded)
    val toolbar = Fixture(500.0, 66.0, RenderShape.Capsule)
    val circle = Fixture(64.0, 64.0, RenderShape.Disc)
    val scene = listOf(
        toolbar,
        button,
        Fixture(160.0, 56.0, RenderShape.Capsule, 17),
        circle,
        circle.copy(seed = 73),
        circle.copy(seed = 19),
        Fixture(48.0, 48.0, RenderShape.Rounded, 101),
    )
    // Ten distinct, repeated positions exercise slider updates without accidentally
    // measuring a final-image cache hit at either endpoint or between sweeps.
    fun position(index: Int) = 0.15 + (index % 10) * (0.70 / 9)
    fun warm(name: String, settings: (Double) -> RenderSettings) = BenchmarkCase(
        "warm.button.$name", listOf(button), stateAt = { MaterialState(settings(position(it))) },
    )
    return listOf(
        BenchmarkCase("cold.button", listOf(button), cold = true),
        BenchmarkCase("cold.toolbar", listOf(toolbar), cold = true),
        BenchmarkCase("cold.disc", listOf(circle), cold = true),
        warm("frost") { RenderSettings(frost = it) },
        warm("grain") { RenderSettings(grain = it) },
        warm("tumble") { RenderSettings(tumble = it * 0.065) },
        warm("bulge") { RenderSettings(bulge = it) },
        warm("thickness") { RenderSettings(thickness = it * 14.0) },
        warm("elevation") { RenderSettings(elevation = it * 18.0) },
        warm("transmission") { RenderSettings(transmission = it) },
        warm("light") { RenderSettings(light = it * 360.0) },
        BenchmarkCase("warm.button.palette", listOf(button), stateAt = {
            MaterialState(palette = listOf(lagoon, mint, amber)[it % 3])
        }),
        BenchmarkCase("warm.scene.light", scene, stateAt = {
            MaterialState(RenderSettings(light = position(it) * 360.0))
        }),
        BenchmarkCase("warm.scene.tumble", scene, stateAt = {
            MaterialState(RenderSettings(tumble = position(it) * 0.065))
        }),
    )
}

private data class Timing(val renderNs: Long, val uploadNs: Long, val checksum: Long)
private data class Result(val case: BenchmarkCase, val timings: List<Timing>)
@Volatile private var checksumSink = 0L
private fun consume(timing: Timing) { checksumSink = checksumSink xor timing.checksum }
private fun Array<String>.option(name: String, default: Int) = firstOrNull { it.startsWith("--$name=") }?.substringAfter('=')?.toInt() ?: default
private fun List<Long>.medianMs(): Double {
    val sorted = sorted()
    val middle = size / 2
    return (if (size % 2 == 0) (sorted[middle - 1].toDouble() + sorted[middle]) / 2.0 else sorted[middle].toDouble()) / 1_000_000
}
private fun List<Long>.p95Ms() = sorted()[(ceil(size * 0.95).toInt() - 1).coerceIn(indices)] / 1_000_000.0
private fun Double.format() = String.format(Locale.ROOT, "%.3f", this)
private fun String.json() = "\"" + replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

private fun buildReport(samples: Int, warmups: Int, fastGround: Boolean, results: List<Result>) = buildString {
    appendLine("{")
    appendLine("  \"schemaVersion\": 1,")
    // Approximate ground changes the pixel oracle; the exact comparator must not
    // mistake its results for an equivalent implementation of workload version 1.
    appendLine("  \"workloadVersion\": ${if (fastGround) 2 else 1},")
    appendLine("  \"groundMethod\": ${(if (fastGround) "four-box-approximation" else "exact-gaussian").json()},")
    appendLine("  \"description\": \"CPU material rendering and native bitmap conversion; not Android frame timing or GPU upload\",")
    appendLine("  \"javaVersion\": ${System.getProperty("java.version").json()},")
    appendLine("  \"vmName\": ${System.getProperty("java.vm.name").json()},")
    appendLine("  \"vmArguments\": ${ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(prefix = "[", postfix = "]") { it.json() }},")
    appendLine("  \"os\": ${(System.getProperty("os.name") + " " + System.getProperty("os.arch")).json()},")
    appendLine("  \"availableProcessors\": ${Runtime.getRuntime().availableProcessors()},")
    appendLine("  \"scale\": 2.0,")
    appendLine("  \"samples\": $samples,")
    appendLine("  \"warmupsPerCase\": $warmups,")
    appendLine("  \"cases\": [")
    results.forEachIndexed { index, result ->
        val renders = result.timings.map { it.renderNs }
        val uploads = result.timings.map { it.uploadNs }
        val totals = result.timings.map { it.renderNs + it.uploadNs }
        appendLine("    {")
        appendLine("      \"name\": ${result.case.name.json()},")
        appendLine("      \"componentCount\": ${result.case.fixtures.size},")
        appendLine("      \"fixtures\": [${result.case.fixtures.joinToString { "{\"widthDp\": ${it.width}, \"heightDp\": ${it.height}, \"shape\": ${it.shape.name.json()}, \"seed\": ${it.seed}}" }}],")
        appendLine("      \"renderMedianMs\": ${renders.medianMs()}, \"renderP95Ms\": ${renders.p95Ms()},")
        appendLine("      \"uploadMedianMs\": ${uploads.medianMs()}, \"uploadP95Ms\": ${uploads.p95Ms()},")
        appendLine("      \"totalMedianMs\": ${totals.medianMs()}, \"totalP95Ms\": ${totals.p95Ms()},")
        appendLine("      \"renderNs\": ${renders.joinToString(prefix = "[", postfix = "]")},")
        appendLine("      \"uploadNs\": ${uploads.joinToString(prefix = "[", postfix = "]")},")
        appendLine("      \"checksums\": ${result.timings.joinToString(prefix = "[", postfix = "]") { it.checksum.toString().json() }}")
        append("    }")
        if (index < results.lastIndex) append(',')
        appendLine()
    }
    appendLine("  ]")
    appendLine("}")
}
