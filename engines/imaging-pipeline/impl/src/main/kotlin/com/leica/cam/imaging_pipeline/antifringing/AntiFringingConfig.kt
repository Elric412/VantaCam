package com.leica.cam.imaging_pipeline.antifringing

/**
 * Tunable knobs for [AntiFringingEngine].
 *
 * Defaults are calibrated for natural-looking output: aggressive enough
 * to clear common purple-fringe halos around backlit branches and
 * specular highlights on chrome / eyeglasses, conservative enough to
 * preserve genuinely violet flowers and saturated sky.
 *
 * #### When to tune
 *
 * | Symptom                                | Knob to change                    |
 * |----------------------------------------|-----------------------------------|
 * | Residual purple halos on backlit edges | `purpleStrength` ↑ (toward 1.0)   |
 * | Lavender flowers losing saturation     | `protectMemoryColors = true`      |
 * | Engine missing thin fringes            | `edgeRadius` ↑ (try 3)            |
 * | Defringe cast on midtone gradients     | `chromaThreshold` ↑ (try 0.06)    |
 * | Green fringing visible on Sony glass   | `greenStrength` ↑ (toward 0.8)    |
 *
 * @property purpleStrength Suppression strength for the violet/purple halo
 * class. Range 0.0 (off) – 1.0 (full neutralisation). Default 0.85.
 * @property greenStrength Suppression strength for green halos. Range 0.0 –
 * 1.0. Default 0.55. (Green fringing is rarer; conservative default.)
 * @property edgeThreshold Minimum Sobel-luma gradient required for a
 * pixel to be eligible for defringing. Range 0.0 – 0.2. Default 0.06.
 * @property chromaThreshold Minimum opponent-space chroma magnitude for a
 * pixel to be considered fringe. Range 0.0 – 0.2. Default 0.04.
 * @property edgeRadius Dilation radius of the edge mask in pixels. Fringes
 * appear up to a few pixels away from the actual edge. Default 2.
 * @property subpixelRealign When true, performs a ≤ 1 px sub-pixel R/B
 * alignment in fringe-flagged pixels. Mitigates residual lateral-CA when
 * a lens profile is unavailable. Default true.
 * @property subpixelMaxOffsetPx Maximum allowed sub-pixel realignment.
 * Range 0.0 – 1.5. Default 1.0.
 * @property protectFaces If a face mask is supplied, defringing is
 * skipped on those pixels. Default true.
 * @property protectMemoryColors When true, applies a memory-colour gate
 * (skin, sky, vibrant flowers) so legitimate saturated colours are never
 * desaturated by the fringe operator. Default true.
 */
data class AntiFringingConfig(
    val purpleStrength: Float = 0.85f,
    val greenStrength: Float = 0.55f,
    val edgeThreshold: Float = 0.06f,
    val chromaThreshold: Float = 0.04f,
    val edgeRadius: Int = 2,
    val subpixelRealign: Boolean = true,
    val subpixelMaxOffsetPx: Float = 1.0f,
    val protectFaces: Boolean = true,
    val protectMemoryColors: Boolean = true,
) {
    init {
        require(purpleStrength in 0f..1f) { "purpleStrength out of range: $purpleStrength" }
        require(greenStrength in 0f..1f) { "greenStrength out of range: $greenStrength" }
        require(edgeThreshold >= 0f) { "edgeThreshold must be >= 0: $edgeThreshold" }
        require(chromaThreshold >= 0f) { "chromaThreshold must be >= 0: $chromaThreshold" }
        require(edgeRadius >= 0) { "edgeRadius must be >= 0: $edgeRadius" }
        require(subpixelMaxOffsetPx in 0f..2f) {
            "subpixelMaxOffsetPx out of range: $subpixelMaxOffsetPx"
        }
    }

    companion object {
        /** Conservative profile — defringe only the most obvious halos. */
        val GENTLE = AntiFringingConfig(
            purpleStrength = 0.55f,
            greenStrength = 0.30f,
            edgeThreshold = 0.10f,
            chromaThreshold = 0.07f,
        )

        /** Aggressive profile — for heavy backlit scenes, fast prime lenses. */
        val AGGRESSIVE = AntiFringingConfig(
            purpleStrength = 1.0f,
            greenStrength = 0.85f,
            edgeThreshold = 0.04f,
            chromaThreshold = 0.025f,
            edgeRadius = 3,
        )

        /** Disabled — used when downstream stages handle defringing. */
        val OFF = AntiFringingConfig(
            purpleStrength = 0f,
            greenStrength = 0f,
        )
    }
}
