package dev.mttmcknn.seaglass.internal

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
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
    val width = texture.width
    val height = texture.height
    val lastRow = max(1, height - 1)
    val short = min(width, height).toDouble() / texture.scale
    val rise = 0.9 + min(settings.thickness * 1.55, short * 0.22) + settings.bulge * short * 0.34
    val rough = settings.grain * (0.025 + settings.frost * 0.04)
    val depthFactor = 0.52 + settings.thickness * 0.012
    val redAbsorption = 255.0 - (body[0] * 0.96 + edge[0] * 0.04)
    val greenAbsorption = 255.0 - (body[1] * 0.96 + edge[1] * 0.04)
    val blueAbsorption = 255.0 - (body[2] * 0.96 + edge[2] * 0.04)
    for (y in 0 until height) {
        if (y % 32 == 0) cancellationProbe()
        val travel = y.toDouble() / lastRow
        val flow = travel * travel * (3.0 - 2.0 * travel)
        val transmittedDepth = 1.05 - flow * settings.transmission * 0.13
        for (x in 0 until width) {
            val i = y * width + x
            val cloud = texture.clouds[i].toDouble()
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
            val depth = transmittedDepth *
                (0.70 + dome[i].toDouble() * depthFactor) *
                (1.0 + (1.0 - shapeInv) * max(0.0, -shapeY[i].toDouble()) * 0.28) *
                (1.0 + cloud * 0.035)
            val weathering = settings.frost * (0.06 + (cloud + 1.0) * 0.025)
            val red = 255.0 - redAbsorption * depth
            val green = 255.0 - greenAbsorption * depth
            val blue = 255.0 - blueAbsorption * depth
            colors[i * 3] = (red + (255.0 - red) * weathering).toFloat()
            colors[i * 3 + 1] = (green + (255.0 - green) * weathering).toFloat()
            colors[i * 3 + 2] = (blue + (255.0 - blue) * weathering).toFloat()
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
    val width = texture.width
    val height = texture.height
    val lastRow = max(1, height - 1)
    val reflectionBulge = 0.15 + settings.bulge * 0.12
    val reflectionFrost = 0.82 + settings.frost * 0.18
    val scatterFrost = 0.008 + settings.frost * 0.012
    val upperBulge = 0.10 + settings.bulge * 0.14
    val upperFrost = 0.85 + settings.frost * 0.15
    val pitFrost = 5.0 + settings.frost * 10.0
    val matteFrost = 0.012 + settings.frost * 0.016
    val exitThickness = 0.96 - settings.thickness * 0.005
    for (y in 0 until height) {
        if (y % 32 == 0) cancellationProbe()
        val travel = y.toDouble() / lastRow
        val flow = travel * travel * (3.0 - 2.0 * travel)
        for (x in 0 until width) {
            val i = y * width + x
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
                clamp(reflectedLight / 0.30, 0.0, 1.0) * reflectionBulge * reflectionFrost
            val scatter = max(0.0, nx * hx + ny * hy + nz * hz).pow(exponent) * scatterFrost
            val upperScale = (ny + 0.40) / 0.38
            val upperTurn = exp(-(upperScale * upperScale)) * nz.pow(0.55) *
                upperBulge * upperFrost
            val palePits = max(0.0, roughness + 0.20).pow(2.0) * settings.grain *
                pitFrost * (0.65 + diffuse * 0.35)
            val matte = roughness * settings.grain * matteFrost
            val shapeZ = optics.shapeZ[i].toDouble()
            val grazing = sqrt(max(0.0, 1.0 - shapeZ * shapeZ))
            val lowerFacing = clamp((optics.shapeY[i].toDouble() + 0.16) / 0.80, 0.0, 1.0)
            val shoulder = grazing.pow(2.3) * lowerFacing * lowerFacing * (3.0 - 2.0 * lowerFacing)
            val exitAmount = clamp(settings.transmission * (0.10 * flow + 1.65 * shoulder) *
                exitThickness, 0.0, 0.96)
            val exitCatch = (1.0 - shapeZ).pow(2.2) * lowerFacing * 0.85
            val surfaceLight = reflection + scatter + upperTurn
            val surfaceTexture = (palePits + matte * 255.0) * (1.0 - exitAmount * 0.45)
            var pixel = alpha shl 24
            for (c in 0..2) {
                var value = optics.colors[n + c].toDouble()
                value += (255.0 - value) * surfaceLight
                val exitColor = edge[c] + (255 - edge[c]) * exitCatch
                value += (exitColor - value) * exitAmount
                value += surfaceTexture
                pixel = pixel or (roundedChannel(value) shl (16 - c * 8))
            }
            pixels[i] = pixel
        }
    }
    return PixelBuffer(texture.width, texture.height, pixels)
}
