package dev.mttmcknn.seaglass.internal

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAU = PI * 2.0

/** Exact port of the web demo's seeded, fitted tumbled outline. */
internal fun buildContour(
    width: Double,
    height: Double,
    shape: RenderShape = RenderShape.Rounded,
    tumble: Double = 0.025,
    seed: Int = 42,
    count: Int = 160,
): List<ContourPoint> {
    val w = max(1.0, width)
    val h = max(1.0, height)
    val short = min(w, h)
    val amount = clamp(tumble, 0.0, 0.065)
    val random = SeededRandom(seed)
    val phase = DoubleArray(3) { random.nextDouble() * TAU }
    val strength = doubleArrayOf(0.70 + random.nextDouble() * 0.10, 0.18 + random.nextDouble() * 0.04)
    val samples = max(64, count)
    val radius = if (shape == RenderShape.Capsule) short / 2.0 else short * 0.46
    val arc = PI * radius / 2.0
    val horizontal = max(0.0, w - radius * 2.0)
    val vertical = max(0.0, h - radius * 2.0)
    val lengths = doubleArrayOf(horizontal, arc, vertical, arc, horizontal, arc, vertical, arc)
    val perimeter = lengths.sum()
    // Keep intermediate coordinates primitive. Only the returned fitted points
    // need objects; base storage can be reused once smoothing has consumed it.
    val base = DoubleArray(samples * 2)
    for (i in 0 until samples) {
        val t = i.toDouble() / samples * TAU
        val offset = i * 2
        if (shape == RenderShape.Disc) {
            val angle = t - PI / 2.0
            base[offset] = w / 2.0 + cos(angle) * w / 2.0
            base[offset + 1] = h / 2.0 + sin(angle) * h / 2.0
        } else {
            var distance = i.toDouble() / samples * perimeter
            var segment = 0
            while (segment < 7 && distance >= lengths[segment]) {
                distance -= lengths[segment]
                segment++
            }
            val unit = if (lengths[segment] != 0.0) distance / lengths[segment] else 0.0
            when (segment) {
                0 -> {
                    base[offset] = radius + distance
                    base[offset + 1] = 0.0
                }
                2 -> {
                    base[offset] = w
                    base[offset + 1] = radius + distance
                }
                4 -> {
                    base[offset] = w - radius - distance
                    base[offset + 1] = h
                }
                6 -> {
                    base[offset] = 0.0
                    base[offset + 1] = h - radius - distance
                }
                else -> {
                    val corner = (segment - 1) / 2
                    val angle = -PI / 2.0 + corner * PI / 2.0 + unit * PI / 2.0
                    val centerX = if (corner < 2) w - radius else radius
                    val centerY = if (corner == 0 || corner == 3) radius else h - radius
                    base[offset] = centerX + cos(angle) * radius
                    base[offset + 1] = centerY + sin(angle) * radius
                }
            }
        }
    }
    val ellipseA = w / 2.0
    val ellipseB = h / 2.0
    val curveLength = if (shape == RenderShape.Disc) {
        PI * (3.0 * (ellipseA + ellipseB) - sqrt((3.0 * ellipseA + ellipseB) * (ellipseA + 3.0 * ellipseB)))
    } else perimeter
    val sigma = max(0.7, short * 0.065 / (curveLength / samples))
    val reach = ceil(sigma * 3.0).toInt()
    val weights = DoubleArray(reach * 2 + 1) { i ->
        val distance = (i - reach).toDouble()
        exp(-(distance * distance) / (2.0 * sigma * sigma))
    }
    val weightSum = weights.sum()
    val smooth = DoubleArray(samples * 2)
    for (i in 0 until samples) {
        var x = 0.0
        var y = 0.0
        for (k in weights.indices) {
            val neighbor = ((i + k - reach + samples) % samples) * 2
            x += base[neighbor] * weights[k] / weightSum
            y += base[neighbor + 1] * weights[k] / weightSum
        }
        smooth[i * 2] = x
        smooth[i * 2 + 1] = y
    }
    val points = base
    for (i in 0 until samples) {
        val offset = i * 2
        val prev = ((i - 1 + samples) % samples) * 2
        val next = ((i + 1) % samples) * 2
        val dx = smooth[next] - smooth[prev]
        val dy = smooth[next + 1] - smooth[prev + 1]
        val length = hypot(dx, dy)
        val t = i.toDouble() / samples * TAU
        val ripple = sin(t * 2.0 + phase[0]) * strength[0] + sin(t * 3.0 + phase[1]) * strength[1]
        val displacement = short * amount * ripple
        points[offset] = smooth[offset] + dy / length * displacement
        points[offset + 1] = smooth[offset + 1] - dx / length * displacement
    }
    var minX = points[0]
    var maxX = points[0]
    var minY = points[1]
    var maxY = points[1]
    for (i in 1 until samples) {
        minX = minOf(minX, points[i * 2])
        maxX = maxOf(maxX, points[i * 2])
        minY = minOf(minY, points[i * 2 + 1])
        maxY = maxOf(maxY, points[i * 2 + 1])
    }
    val inset = min(0.45, short * 0.005)
    return List(samples) { i ->
        ContourPoint(
            inset + (points[i * 2] - minX) / (maxX - minX) * (w - inset * 2.0),
            inset + (points[i * 2 + 1] - minY) / (maxY - minY) * (h - inset * 2.0),
        )
    }
}

/** Periodic uniform cubic B-spline; exact C2 joins without interpolation overshoot. */
internal fun contourSegments(points: List<ContourPoint>): List<ContourSegment> {
    require(points.size >= 4)
    val n = points.size
    val first = ContourPoint(
        (points[n - 1].x + 4.0 * points[0].x + points[1].x) / 6.0,
        (points[n - 1].y + 4.0 * points[0].y + points[1].y) / 6.0,
    )
    var start = first
    return List(n) { i ->
        val b = points[i]
        val c = points[(i + 1) % n]
        val d = points[(i + 2) % n]
        // An end and the following start use exactly the same expression. The
        // point is immutable, including the closing join, so compute it once.
        val end = if (i == n - 1) first else ContourPoint(
            (b.x + 4.0 * c.x + d.x) / 6.0,
            (b.y + 4.0 * c.y + d.y) / 6.0,
        )
        ContourSegment(
            start,
            ContourPoint((2.0 * b.x + c.x) / 3.0, (2.0 * b.y + c.y) / 3.0),
            ContourPoint((b.x + 2.0 * c.x) / 3.0, (b.y + 2.0 * c.y) / 3.0),
            end,
        ).also { start = end }
    }
}

/** JavaScript Math.round for nonnegative output channels and dimensions. */
internal fun roundedChannel(value: Double): Int = floor(value + 0.5).toInt().coerceIn(0, 255)
