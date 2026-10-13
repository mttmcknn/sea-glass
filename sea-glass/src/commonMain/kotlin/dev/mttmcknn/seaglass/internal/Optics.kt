package dev.mttmcknn.seaglass.internal

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal data class OpticalField(
    val normals: FloatArray,
    val colors: FloatArray,
    val dome: FloatArray,
    val shapeY: FloatArray,
    val shapeZ: FloatArray,
)

private fun hypot3(x: Double, y: Double, z: Double) = sqrt(x * x + y * y + z * z)

/** Optical depth and material normals are independent of the adjustable surface light. */
internal fun prepareOptics(field: GeometryField, settings: RenderSettings, palette: RenderPalette, cancellationProbe: () -> Unit = {}): OpticalField {
    val n = field.alpha.size
    val normals = FloatArray(n * 3)
    val colors = FloatArray(n * 3)
    val shapeY = FloatArray(n)
    val shapeZ = FloatArray(n)
    val dome = FloatArray(n)
    val texture = field.texture
    val body = rgbComponents(palette.body)
    val edge = rgbComponents(palette.edge)
    val short = min(texture.width, texture.height).toDouble() / texture.scale
    val rise = 0.9 + min(settings.thickness * 1.55, short * 0.22) + settings.bulge * short * 0.34
    for (i in 0 until n) {
        if (i % (texture.width * 32) == 0) cancellationProbe()
        val cloud = texture.clouds[i].toDouble()
        val rough = settings.grain * (0.025 + settings.frost * 0.04)
        val profile = field.profile[i].toDouble()
        val gradX = field.gradX[i].toDouble()
        val gradY = field.gradY[i].toDouble()
        dome[i] = (profile + (field.roundProfile[i].toDouble() - profile) * settings.bulge).toFloat()
        val slopeX = gradX + (field.roundGradX[i].toDouble() - gradX) * settings.bulge
        val slopeY = gradY + (field.roundGradY[i].toDouble() - gradY) * settings.bulge
        val shapeInv = 1.0 / hypot3(slopeX * rise, slopeY * rise, 1.0)
        shapeY[i] = (-slopeY * rise * shapeInv).toFloat()
        shapeZ[i] = shapeInv.toFloat()
        val nx = -slopeX * rise + texture.roughX[i].toDouble() * rough
        val ny = -slopeY * rise + texture.roughY[i].toDouble() * rough
        val inv = 1.0 / hypot3(nx, ny, 1.0)
        normals[i * 3] = (nx * inv).toFloat()
        normals[i * 3 + 1] = (ny * inv).toFloat()
        normals[i * 3 + 2] = inv.toFloat()
        val travel = (i / texture.width).toDouble() / max(1, texture.height - 1)
        val flow = travel * travel * (3.0 - 2.0 * travel)
        val depth = (1.05 - flow * settings.transmission * 0.13) *
            (0.70 + dome[i].toDouble() * (0.52 + settings.thickness * 0.012)) *
            (1.0 + (1.0 - shapeInv) * max(0.0, -shapeY[i].toDouble()) * 0.28) *
            (1.0 + cloud * 0.035)
        val weathering = settings.frost * (0.06 + (cloud + 1.0) * 0.025)
        for (c in 0..2) {
            val tint = body[c] * 0.96 + edge[c] * 0.04
            val absorbed = 255.0 - (255.0 - tint) * depth
            colors[i * 3 + c] = (absorbed + (255.0 - absorbed) * weathering).toFloat()
        }
    }
    return OpticalField(normals, colors, dome, shapeY, shapeZ)
}

/** The material body, before compositing with the downward ground layers. */
internal fun shadeBody(
    field: GeometryField,
    settings: RenderSettings,
    palette: RenderPalette,
    optics: OpticalField = prepareOptics(field, settings, palette),
    cancellationProbe: () -> Unit = {},
): PixelBuffer {
    val texture = field.texture
    val pixels = IntArray(field.alpha.size)
    val theta = settings.light * PI / 180.0
    val lx = sin(theta)
    val ly = -cos(theta)
    val lightZ = 0.52
    val lightSide = sqrt(1.0 - lightZ * lightZ)
    val halfLength = hypot3(lx * lightSide, ly * lightSide, lightZ + 1.0)
    val hx = lx * lightSide / halfLength
    val hy = ly * lightSide / halfLength
    val hz = (lightZ + 1.0) / halfLength
    val exponent = 12.0 + (1.0 - settings.frost) * 18.0
    val reflectionWidth = 0.78 + settings.frost * 0.24
    val reflectionHeight = 0.46 + settings.frost * 0.24
    val edge = rgbComponents(palette.edge)
    for (i in field.alpha.indices) {
        if (i % (texture.width * 32) == 0) cancellationProbe()
        val alpha = field.alpha[i].toInt() and 255
        if (alpha == 0) continue
        val n = i * 3
        val nx = optics.normals[n].toDouble()
        val ny = optics.normals[n + 1].toDouble()
        val nz = optics.normals[n + 2].toDouble()
        val diffuse = max(0.0, nx * lx * lightSide + ny * ly * lightSide + nz * lightZ)
        val roughness = texture.noise[i].toDouble()
        val rx = 2.0 * nx * nz
        val ry = 2.0 * ny * nz
        val rz = 2.0 * nz * nz - 1.0
        val reflectedLight = max(0.0, rx * lx * lightSide + ry * ly * lightSide + rz * lightZ)
        val across = -ly * rx + lx * ry
        val along = -lightZ * (lx * rx + ly * ry) + lightSide * rz
        val acrossScale = across / reflectionWidth
        val alongScale = along / reflectionHeight
        val reflection = exp(-(acrossScale * acrossScale + alongScale * alongScale)) *
            clamp(reflectedLight / 0.30, 0.0, 1.0) * (0.15 + settings.bulge * 0.12) * (0.82 + settings.frost * 0.18)
        val scatter = max(0.0, nx * hx + ny * hy + nz * hz).pow(exponent) * (0.008 + settings.frost * 0.012)
        val upperScale = (ny + 0.40) / 0.38
        val upperTurn = exp(-(upperScale * upperScale)) * nz.pow(0.55) *
            (0.10 + settings.bulge * 0.14) * (0.85 + settings.frost * 0.15)
        val palePits = max(0.0, roughness + 0.20).pow(2.0) * settings.grain *
            (5.0 + settings.frost * 10.0) * (0.65 + diffuse * 0.35)
        val matte = roughness * settings.grain * (0.012 + settings.frost * 0.016)
        val travel = (i / texture.width).toDouble() / max(1, texture.height - 1)
        val flow = travel * travel * (3.0 - 2.0 * travel)
        val shapeZ = optics.shapeZ[i].toDouble()
        val grazing = sqrt(max(0.0, 1.0 - shapeZ * shapeZ))
        val lowerFacing = clamp((optics.shapeY[i].toDouble() + 0.16) / 0.80, 0.0, 1.0)
        val shoulder = grazing.pow(2.3) * lowerFacing * lowerFacing * (3.0 - 2.0 * lowerFacing)
        val exitAmount = clamp(settings.transmission * (0.10 * flow + 1.65 * shoulder) *
            (0.96 - settings.thickness * 0.005), 0.0, 0.96)
        val exitCatch = (1.0 - shapeZ).pow(2.2) * lowerFacing * 0.85
        var pixel = alpha shl 24
        for (c in 0..2) {
            var value = optics.colors[n + c].toDouble()
            value += (255.0 - value) * (reflection + scatter + upperTurn)
            val exitColor = edge[c] + (255 - edge[c]) * exitCatch
            value += (exitColor - value) * exitAmount
            value += (palePits + matte * 255.0) * (1.0 - exitAmount * 0.45)
            pixel = pixel or (roundedChannel(value) shl (16 - c * 8))
        }
        pixels[i] = pixel
    }
    return PixelBuffer(texture.width, texture.height, pixels)
}
