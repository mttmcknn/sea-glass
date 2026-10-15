package dev.mttmcknn.seaglass.internal

/** Only invalidate native uniforms whose effective shader inputs changed. */
internal class LiveMaterialUniforms(private val shader: NativeMaterialShader) {
    private var light: Double? = null
    private var recipeA: RecipeA? = null
    private var recipeB: RecipeB? = null
    private var body: Int? = null
    private var edge: Int? = null

    fun update(settings: RenderSettings, palette: RenderPalette) {
        if (light != settings.light) {
            shader.setMaterialLight(settings.light)
            light = settings.light
        }
        val nextA = RecipeA(settings.frost.toFloat(), settings.grain.toFloat(), settings.thickness.toFloat(), settings.bulge.toFloat())
        if (nextA != recipeA) {
            shader.setFloatUniform("recipeA", floatArrayOf(nextA.frost, nextA.grain, nextA.thickness, nextA.bulge))
            recipeA = nextA
        }
        val nextB = RecipeB(settings.light.toFloat(), settings.transmission.toFloat(), settings.seed)
        if (nextB != recipeB) {
            shader.setFloatUniform("recipeB", floatArrayOf(nextB.light, nextB.transmission, SeededRandom(nextB.seed).nextDouble().toFloat(), 0f))
            recipeB = nextB
        }
        val nextBody = palette.body and 0xFFFFFF
        if (body != nextBody) {
            shader.setFloatUniform("bodyColor", channels(nextBody))
            body = nextBody
        }
        val nextEdge = palette.edge and 0xFFFFFF
        if (edge != nextEdge) {
            shader.setFloatUniform("edgeColor", channels(nextEdge))
            edge = nextEdge
        }
    }

    private fun channels(rgb: Int) = floatArrayOf(
        (rgb ushr 16 and 255) / 255f, (rgb ushr 8 and 255) / 255f, (rgb and 255) / 255f,
    )

    private data class RecipeA(val frost: Float, val grain: Float, val thickness: Float, val bulge: Float)
    private data class RecipeB(val light: Float, val transmission: Float, val seed: Int)
}
