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
    val base = List(samples) { i ->
        val t = i.toDouble() / samples * TAU
        if (shape == RenderShape.Disc) {
            val angle = t - PI / 2.0
            ContourPoint(w / 2.0 + cos(angle) * w / 2.0, h / 2.0 + sin(angle) * h / 2.0)
        } else {
            var distance = i.toDouble() / samples * perimeter
            var segment = 0
            while (segment < 7 && distance >= lengths[segment]) {
                distance -= lengths[segment]
                segment++
            }
            val unit = if (lengths[segment] != 0.0) distance / lengths[segment] else 0.0
            when (segment) {
                0 -> ContourPoint(radius + distance, 0.0)
                2 -> ContourPoint(w, radius + distance)
                4 -> ContourPoint(w - radius - distance, h)
                6 -> ContourPoint(0.0, h - radius - distance)
                else -> {
                    val corner = (segment - 1) / 2
                    val angle = -PI / 2.0 + corner * PI / 2.0 + unit * PI / 2.0
                    val center = when (corner) {
                        0 -> ContourPoint(w - radius, radius)
                        1 -> ContourPoint(w - radius, h - radius)
                        2 -> ContourPoint(radius, h - radius)
                        else -> ContourPoint(radius, radius)
                    }
                    ContourPoint(center.x + cos(angle) * radius, center.y + sin(angle) * radius)
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
    val smooth = List(samples) { i ->
        var x = 0.0
        var y = 0.0
        for (k in weights.indices) {
            val neighbor = base[(i + k - reach + samples) % samples]
            x += neighbor.x * weights[k] / weightSum
            y += neighbor.y * weights[k] / weightSum
        }
        ContourPoint(x, y)
    }
    val points = List(samples) { i ->
        val point = smooth[i]
        val prev = smooth[(i - 1 + samples) % samples]
        val next = smooth[(i + 1) % samples]
        val dx = next.x - prev.x
        val dy = next.y - prev.y
        val length = hypot(dx, dy)
        val t = i.toDouble() / samples * TAU
        val ripple = sin(t * 2.0 + phase[0]) * strength[0] + sin(t * 3.0 + phase[1]) * strength[1]
        val displacement = short * amount * ripple
        ContourPoint(point.x + dy / length * displacement, point.y - dx / length * displacement)
    }
    val minX = points.minOf { it.x }
    val maxX = points.maxOf { it.x }
    val minY = points.minOf { it.y }
    val maxY = points.maxOf { it.y }
    val inset = min(0.45, short * 0.005)
    return points.map { point ->
        ContourPoint(
            inset + (point.x - minX) / (maxX - minX) * (w - inset * 2.0),
            inset + (point.y - minY) / (maxY - minY) * (h - inset * 2.0),
        )
    }
}

/** Periodic uniform cubic B-spline; exact C2 joins without interpolation overshoot. */
internal fun contourSegments(points: List<ContourPoint>): List<ContourSegment> {
    require(points.size >= 4)
    val n = points.size
    return List(n) { i ->
        val a = points[(i - 1 + n) % n]
        val b = points[i]
        val c = points[(i + 1) % n]
        val d = points[(i + 2) % n]
        ContourSegment(
            ContourPoint((a.x + 4.0 * b.x + c.x) / 6.0, (a.y + 4.0 * b.y + c.y) / 6.0),
            ContourPoint((2.0 * b.x + c.x) / 3.0, (2.0 * b.y + c.y) / 3.0),
            ContourPoint((b.x + 2.0 * c.x) / 3.0, (b.y + 2.0 * c.y) / 3.0),
            ContourPoint((b.x + 4.0 * c.x + d.x) / 6.0, (b.y + 4.0 * c.y + d.y) / 6.0),
        )
    }
}

/** JavaScript Math.round for nonnegative output channels and dimensions. */
internal fun roundedChannel(value: Double): Int = floor(value + 0.5).toInt().coerceIn(0, 255)
