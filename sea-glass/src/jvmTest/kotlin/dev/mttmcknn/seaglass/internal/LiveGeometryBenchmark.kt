package dev.mttmcknn.seaglass.internal

import java.io.File
import java.lang.management.ManagementFactory
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.ceil

/**
 * Live geometry preparation and Skia raster texture creation, not GPU upload/draw
 * or first displayed frame latency. Fresh means no cloud cache, not a cold JVM.
 * One shader instance per component is retained during each case. Replacement
 * texture disposal is included in upload time; shader/program disposal is not.
 * Run separately from builds, tests and all other timed workloads.
 */
internal object LiveGeometryBenchmark {
    @JvmStatic
    fun main(args: Array<String>) {
        val output = File(args.firstOrNull() ?: error("Pass an output JSON path"))
        val samples = args.intOption("samples", 24).also { require(it >= 3) }
        val warmups = args.intOption("warmups", 8).also { require(it >= 0) }
        val counters = ThreadCounters()
        val program = NativeMaterialProgram(SeaGlassBodyShader)
        val cases = fixtures().flatMap { (name, fixtures) ->
            listOf(LiveCase("freshClouds.$name", fixtures, false), LiveCase("reusedClouds.$name", fixtures, true))
        }
        try {
            // Initialize Skia and exercise every workload before any measured case.
            // This pass is identical regardless of requested per-case warmups.
            for (case in cases) LiveOperation(case, program, counters).use { operation ->
                repeat(2) { sink = operation.run(it).inputChecksum }
            }
            val results = cases.map { case ->
                LiveOperation(case, program, counters).use { operation ->
                    repeat(warmups) { sink = operation.run(it).inputChecksum }
                    val timings = List(samples) { index ->
                        // LiveSample states do not depend on the number of warmups.
                        operation.run(index).also { sink = it.inputChecksum }
                    }
                    println("${case.name.padEnd(23)} geometry ${timings.map { it.geometry.wallNs }.medianMs().format()} ms; native textures ${timings.map { it.upload.wallNs }.medianMs().format()} ms; retained texture payload ${timings.first().texturePayloadBytes} B")
                    LiveResult(case, timings)
                }
            }
            output.absoluteFile.parentFile.mkdirs()
            output.writeText(report(samples, warmups, counters, results))
            println("Live geometry benchmark: ${output.absolutePath}; checksum sink=$sink")
        } finally {
            program.close()
        }
    }
}

private data class LiveFixture(val width: Double, val height: Double, val shape: RenderShape, val seed: Int = 42)
private data class LiveCase(val name: String, val fixtures: List<LiveFixture>, val reuseClouds: Boolean)
private data class LiveStage(val wallNs: Long, val cpuNs: Long?, val allocatedBytes: Long?)
private data class LiveSample(
    val tumble: Double,
    val geometry: LiveStage,
    val upload: LiveStage,
    val texturePayloadBytes: Long,
    val geometryCacheEstimateBytes: Long,
    val cloudCachePayloadBytes: Long,
    val nativeRasterPayloadBytes: Long,
    val inputChecksum: String,
    val geometryChecksum: String,
    val storage: List<String>,
)
private data class LiveResult(val case: LiveCase, val samples: List<LiveSample>)
@Volatile private var sink = ""

private class LiveOperation(private val case: LiveCase, program: NativeMaterialProgram, private val counters: ThreadCounters) : AutoCloseable {
    private val shaders = case.fixtures.map { checkNotNull(program.createInstance()) }
    private val clouds = case.fixtures.map { if (case.reuseClouds) LiveMaterialCloudCache() else null }

    init {
        // Prewarm only cloud samples; no geometry or global geometry cache is used.
        case.fixtures.forEachIndexed { index, fixture ->
            clouds[index]?.textureFor(fixture.width, fixture.height, 1.0, fixture.seed)
        }
    }

    fun run(index: Int): LiveSample {
        val tumble = 0.065 * (0.15 + (index % 10) * (0.70 / 9.0))
        val (geometries, geometryStage) = counters.measure {
            case.fixtures.mapIndexed { i, fixture ->
                makeLiveMaterialGeometry(
                    fixture.width, fixture.height, fixture.shape, tumble, fixture.seed,
                    composeMaskRasterizer, fieldScale = 1.0, cloudCache = clouds[i],
                )
            }
        }
        val (_, uploadStage) = counters.measure {
            geometries.forEachIndexed { i, geometry ->
                shaders[i].setInputTexture("geometryA", geometry.geometryA)
                shaders[i].setInputTexture("geometryB", geometry.geometryB)
            }
        }
        // Reflection, canonicalization and hashing stay outside both measurements.
        // The oracle hashes the exact little-endian RGBA_F16 bytes passed to Skia,
        // allowing FloatArray and already-packed ByteArray implementations to agree.
        val inputs = MessageDigest.getInstance("SHA-256")
        val geometryHash = MessageDigest.getInstance("SHA-256")
        var texturePayload = 0L
        var nativePayload = 0L
        val storage = mutableListOf<String>()
        for (geometry in geometries) {
            for (texture in listOf(geometry.geometryA, geometry.geometryB)) {
                val inspected = inspectTexture(texture)
                texturePayload += inspected.retainedPrimitiveArrayBytes
                nativePayload += texture.width.toLong() * texture.height * 8L
                storage += inspected.storage
                inputs.addLong(texture.width.toLong())
                inputs.addLong(texture.height.toLong())
                inputs.update(inspected.f16Bytes)
            }
            geometryHash.addLong(geometry.fieldScale.toRawBits().toLong())
            geometry.gradientScales.forEach { geometryHash.addLong(it.toRawBits().toLong()) }
            geometry.segments.forEach { segment ->
                for (point in listOf(segment.start, segment.control1, segment.control2, segment.end)) {
                    geometryHash.addLong(point.x.toRawBits())
                    geometryHash.addLong(point.y.toRawBits())
                }
            }
        }
        return LiveSample(
            tumble, geometryStage, uploadStage, texturePayload,
            geometries.sumOf(::estimatedGeometryBytes),
            clouds.sumOf { (it?.retainedSampleCount ?: 0).toLong() * 4L },
            nativePayload, inputs.digest().hex(), geometryHash.digest().hex(), storage.distinct(),
        )
    }

    override fun close() {
        shaders.forEach { it.close() }
        clouds.forEach { it?.clear() }
    }
}

private data class TextureInspection(val f16Bytes: ByteArray, val retainedPrimitiveArrayBytes: Long, val storage: String)

/** Inspect storage without prescribing an internal production representation. */
private fun inspectTexture(texture: NativeFloatTexture): TextureInspection {
    val arrays = texture.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.mapNotNull { field ->
        field.isAccessible = true
        val value = field.get(texture)
        when (value) {
            is FloatArray, is ByteArray, is ShortArray -> field.name to value
            else -> null
        }
    }
    val channels = texture.width * texture.height * 4
    val source = arrays.singleOrNull { (_, value) ->
        when (value) {
            is FloatArray -> value.size == channels
            is ByteArray -> value.size == channels * 2
            is ShortArray -> value.size == channels
            else -> false
        }
    } ?: error("Expected exactly one RGBA float/F16 storage array; found ${arrays.map { it.first }}")
    // Allocate one equally sized oracle buffer for every representation. Reusing
    // packed storage here would lower candidate-only GC pressure between samples.
    val bytes = when (val data = source.second) {
        is ByteArray -> data.copyOf()
        is ShortArray -> ByteArray(channels * 2).also { out ->
            data.forEachIndexed { i, value -> out[i * 2] = value.toByte(); out[i * 2 + 1] = (value.toInt() ushr 8).toByte() }
        }
        is FloatArray -> ByteArray(channels * 2).also { out ->
            data.forEachIndexed { i, value ->
                val half = canonicalFloat16(value)
                out[i * 2] = half.toByte()
                out[i * 2 + 1] = (half ushr 8).toByte()
            }
        }
        else -> error("Unsupported texture storage")
    }
    // Count identity-distinct arrays only; dimensions and JVM object overhead are
    // excluded. Native texture backing is reported separately, not inferred RSS.
    val distinct = java.util.IdentityHashMap<Any, Boolean>()
    val retained = arrays.sumOf { (_, value) ->
        if (distinct.put(value, true) != null) 0L else when (value) {
            is FloatArray -> value.size.toLong() * 4
            is ShortArray -> value.size.toLong() * 2
            is ByteArray -> value.size.toLong()
            else -> 0L
        }
    }
    return TextureInspection(bytes, retained, "${source.first}:${source.second.javaClass.simpleName}")
}

// Independent oracle: IEEE binary16 round-to-nearest-even, matching baseline's
// native upload contract. Do not call candidate conversion code from this oracle.
private fun canonicalFloat16(value: Float): Int {
    val bits = value.toRawBits()
    val sign = (bits ushr 16) and 0x8000
    val exponent = (bits ushr 23) and 255
    val mantissa = bits and 0x7FFFFF
    if (exponent == 255) return sign or if (mantissa == 0) 0x7C00 else 0x7E00
    val halfExponent = exponent - 127 + 15
    if (halfExponent >= 31) return sign or 0x7C00
    if (halfExponent <= 0) {
        if (halfExponent < -10) return sign
        val significant = mantissa or 0x800000
        val shift = 14 - halfExponent
        var rounded = significant ushr shift
        val remainder = significant and ((1 shl shift) - 1)
        val halfway = 1 shl (shift - 1)
        if (remainder > halfway || (remainder == halfway && (rounded and 1) != 0)) rounded++
        return sign or rounded
    }
    val rounded = mantissa + 0xFFF + ((mantissa ushr 13) and 1)
    return sign or ((halfExponent shl 10) + (rounded ushr 13))
}

private class ThreadCounters {
    private val bean = ManagementFactory.getThreadMXBean()
    private val allocationBean = bean as? com.sun.management.ThreadMXBean
    val cpuAvailable = runCatching {
        if (bean.isCurrentThreadCpuTimeSupported && !bean.isThreadCpuTimeEnabled) bean.isThreadCpuTimeEnabled = true
        bean.isCurrentThreadCpuTimeSupported && bean.isThreadCpuTimeEnabled
    }.getOrDefault(false)
    val allocationsAvailable = runCatching {
        if (allocationBean?.isThreadAllocatedMemorySupported == true && !allocationBean.isThreadAllocatedMemoryEnabled) {
            allocationBean.isThreadAllocatedMemoryEnabled = true
        }
        allocationBean?.isThreadAllocatedMemoryEnabled == true
    }.getOrDefault(false)

    fun <T> measure(block: () -> T): Pair<T, LiveStage> {
        val thread = Thread.currentThread().id
        val cpuStart = if (cpuAvailable) bean.currentThreadCpuTime else null
        val allocationStart = if (allocationsAvailable) allocationBean!!.getThreadAllocatedBytes(thread) else null
        val start = System.nanoTime()
        val value = block()
        val elapsed = System.nanoTime() - start
        val allocated = allocationStart?.let { allocationBean!!.getThreadAllocatedBytes(thread) - it }
        val cpu = cpuStart?.let { bean.currentThreadCpuTime - it }
        return value to LiveStage(elapsed, cpu, allocated)
    }
}

private fun fixtures(): List<Pair<String, List<LiveFixture>>> {
    val button = LiveFixture(280.0, 86.0, RenderShape.Rounded)
    val toolbar = LiveFixture(500.0, 66.0, RenderShape.Capsule)
    val disc = LiveFixture(64.0, 64.0, RenderShape.Disc)
    return listOf(
        "button" to listOf(button), "toolbar" to listOf(toolbar), "disc" to listOf(disc),
        "scene" to listOf(toolbar, button, LiveFixture(160.0, 56.0, RenderShape.Capsule, 17), disc,
            disc.copy(seed = 73), disc.copy(seed = 19), LiveFixture(48.0, 48.0, RenderShape.Rounded, 101)),
    )
}

private fun report(samples: Int, warmups: Int, counters: ThreadCounters, results: List<LiveResult>) = buildString {
    appendLine("{")
    appendLine("  \"schemaVersion\": 1, \"workloadVersion\": 1,")
    appendLine("  \"description\": \"Live geometry CPU preparation and native Skia raster texture creation; not GPU execution or displayed frame latency\",")
    appendLine("  \"allocationScope\": \"Current JVM thread heap bytes only; excludes native allocations and other threads\",")
    appendLine("  \"retainedMemoryScope\": \"Texture primitive arrays are exact payload counts; geometry cache accounting includes conservative object estimates; native raster payload excludes backend overhead and GPU copies\",")
    appendLine("  \"oracle\": \"SHA-256 of width/height as little-endian int64 and canonical little-endian RGBA_F16 texture inputs, plus separate exact contour/uniform SHA-256\",")
    appendLine("  \"javaVersion\": ${System.getProperty("java.version").json()},")
    appendLine("  \"vmName\": ${System.getProperty("java.vm.name").json()},")
    appendLine("  \"vmArguments\": ${ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(prefix = "[", postfix = "]") { it.json() }},")
    appendLine("  \"os\": ${(System.getProperty("os.name") + " " + System.getProperty("os.arch")).json()},")
    appendLine("  \"availableProcessors\": ${Runtime.getRuntime().availableProcessors()},")
    appendLine("  \"threadCpuTimeAvailable\": ${counters.cpuAvailable}, \"threadAllocatedBytesAvailable\": ${counters.allocationsAvailable},")
    appendLine("  \"fieldScale\": 1.0, \"samples\": $samples, \"warmupsPerCase\": $warmups, \"initialWarmupsPerCase\": 2,")
    appendLine("  \"cases\": [")
    results.forEachIndexed { caseIndex, result ->
        appendLine("    {")
        appendLine("      \"name\": ${result.case.name.json()}, \"componentCount\": ${result.case.fixtures.size}, \"reuseClouds\": ${result.case.reuseClouds},")
        appendLine("      \"fixtures\": [${result.case.fixtures.joinToString { "{\"widthDp\":${it.width},\"heightDp\":${it.height},\"shape\":${it.shape.name.json()},\"seed\":${it.seed}}" }}],")
        appendLine("      \"geometryMedianMs\": ${result.samples.map { it.geometry.wallNs }.medianMs()}, \"geometryP95Ms\": ${result.samples.map { it.geometry.wallNs }.p95Ms()},")
        appendLine("      \"uploadMedianMs\": ${result.samples.map { it.upload.wallNs }.medianMs()}, \"uploadP95Ms\": ${result.samples.map { it.upload.wallNs }.p95Ms()},")
        appendLine("      \"samples\": [")
        result.samples.forEachIndexed { i, sample ->
            append("        {\"tumble\":${sample.tumble},\"geometry\":${sample.geometry.json()},\"upload\":${sample.upload.json()},")
            append("\"texturePayloadBytes\":${sample.texturePayloadBytes},\"geometryCacheEstimateBytes\":${sample.geometryCacheEstimateBytes},\"cloudCachePayloadBytes\":${sample.cloudCachePayloadBytes},\"nativeRasterPayloadBytes\":${sample.nativeRasterPayloadBytes},")
            append("\"inputChecksum\":${sample.inputChecksum.json()},\"geometryChecksum\":${sample.geometryChecksum.json()},\"storage\":${sample.storage.joinToString(prefix = "[", postfix = "]") { it.json() }}}")
            appendLine(if (i == result.samples.lastIndex) "" else ",")
        }
        appendLine("      ]")
        appendLine(if (caseIndex == results.lastIndex) "    }" else "    },")
    }
    appendLine("  ]")
    appendLine("}")
}

private fun LiveStage.json() = "{\"wallNs\":$wallNs,\"cpuNs\":$cpuNs,\"allocatedBytes\":$allocatedBytes}"
private fun MessageDigest.addLong(value: Long) { repeat(8) { update((value ushr (it * 8)).toByte()) } }
private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
private fun Array<String>.intOption(name: String, default: Int) = firstOrNull { it.startsWith("--$name=") }?.substringAfter('=')?.toInt() ?: default
private fun List<Long>.medianMs(): Double {
    val sorted = sorted()
    val middle = size / 2
    return (if (size % 2 == 0) (sorted[middle - 1].toDouble() + sorted[middle]) / 2.0 else sorted[middle].toDouble()) / 1_000_000
}
private fun List<Long>.p95Ms() = sorted()[(ceil(size * 0.95).toInt() - 1).coerceIn(indices)] / 1_000_000.0
private fun Double.format() = String.format(Locale.ROOT, "%.3f", this)
private fun String.json() = "\"" + replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
