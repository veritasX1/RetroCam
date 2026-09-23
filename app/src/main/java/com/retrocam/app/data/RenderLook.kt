package com.retrocam.app.data

import kotlin.math.max
import kotlin.math.min

/**
 * The normalized parameter set the GL shader pipeline actually consumes.
 * Both [Recipe] (photo) and [FilmStockPreset] (video) map down to this, so
 * the renderer only has to know one shape of "look" regardless of which
 * picker produced it.
 */
data class RenderLook(
    val name: String,
    val warmth: Float,
    val tintGreenMagenta: Float,
    val saturation: Float,
    val contrast: Float,
    val highlightRolloff: Float,
    val shadowLift: Float,
    val grainIntensity: Float,
    val grainSize: Float,
    val softness: Float,
    val vignette: Float,
    /** Which real scanned-grain plate to sample, e.g. "16mm_standard" or
     * "8mm_heavy" - see GrainTexture. Empty when grainIntensity is 0, in
     * which case the renderer just leaves whatever was already bound. */
    val grainSetKey: String = "",
    val grainBlendMode: GrainBlendMode = GrainBlendMode.FILMKORN,
) {
    companion object {
        val NEUTRAL = RenderLook(
            name = "Neutral",
            warmth = 0f,
            tintGreenMagenta = 0f,
            saturation = 1f,
            contrast = 1f,
            highlightRolloff = 0.15f,
            shadowLift = 0.05f,
            grainIntensity = 0f,
            grainSize = 1f,
            softness = 0f,
            vignette = 0f,
            grainSetKey = "",
        )
    }
}

private fun clamp01(v: Float) = max(0f, min(1f, v))
private fun clamp(v: Float, lo: Float, hi: Float) = max(lo, min(hi, v))

// Converts the old "relative" grain size units (~0.8..1.6, tuned when grain
// was measured in normalized 0..1 UV space) into source-image pixels per
// grain texel, which is what the shader now expects - see ShaderSource.
private const val GRAIN_PIXELS_PER_UNIT = 2f

// Real grain sits directly on the film base, so coarser/faster stocks don't
// just show bigger grain - the grain structure itself also eats into edge
// sharpness a little. This adds a small amount of that coupling on top of
// each look's own explicit softness value, rather than replacing it.
private fun grainSoftnessCoupling(grainIntensity: Float) = grainIntensity * 0.12f

/** Maps a FilmStockPreset's free-text gauge to the asset key used in
 * `assets/grain/` (see GrainTexture). */
private fun gaugeAssetKey(gauge: String): String {
    val g = gauge.lowercase()
    return when {
        g.contains("super 16") || g.contains("super16") -> "super16"
        g.contains("super 35") || g.contains("super35") -> "super35"
        g.contains("8") -> "8mm"
        g.contains("16") -> "16mm"
        g.contains("35") -> "35mm"
        else -> "35mm"
    }
}

/**
 * Approximate mapping from Fuji-style recipe fields to render parameters.
 * This is deliberately a "gets you in the right neighborhood" mapping, not
 * a color-science reproduction - see the app README for why an exact match
 * isn't possible on non-Fuji sensors, and tune the constants here against
 * a real device if the look needs to shift.
 */
fun Recipe.toRenderLook(): RenderLook {
    val base = FilmSimulationBase.forName(filmSimulation)

    // Fuji's WB shift scale is roughly -9..+9 per channel; normalize to -1..1.
    val redShift = clamp(wbShiftRed / 9f, -1f, 1f)
    val blueShift = clamp(wbShiftBlue / 9f, -1f, 1f)
    // More red / less blue reads as warmer, and vice versa.
    val wbWarmth = clamp((redShift - blueShift) / 2f, -1f, 1f)

    // Dynamic Range widens highlight/shadow headroom by underexposing and
    // pulling shadows back up in-camera - DR400 reads visibly flatter and
    // less contrasty than DR100, not just a label in the EXIF dump.
    val drStrength = when {
        dynamicRange.contains("400") -> 1f
        dynamicRange.contains("200") -> 0.55f
        dynamicRange.contains("auto", ignoreCase = true) -> 0.4f
        else -> 0f // DR100
    }

    // Fuji highlight/shadow: roughly -2 (soft) .. +4 (harsh) per channel.
    // Higher = more contrast = less rolloff / less lift.
    val highlightRolloff = clamp01(0.5f - highlight / 8f + drStrength * 0.25f)
    val shadowLift = clamp01(0.35f - shadow / 10f + drStrength * 0.12f)

    val colorChromeStrength = when (colorChromeEffect) {
        EffectStrength.OFF -> 0f
        EffectStrength.WEAK -> 0.4f
        EffectStrength.STRONG -> 0.8f
    }
    // Color Chrome FX Blue specifically deepens blues/cyans (skies, water);
    // the single-pass shader has no per-hue path, so the closest genuine
    // analogue available is a small, strength-scaled cool push.
    val fxBlueStrength = when (colorChromeFxBlue) {
        EffectStrength.OFF -> 0f
        EffectStrength.WEAK -> 0.4f
        EffectStrength.STRONG -> 0.8f
    }

    // Color Chrome Effect deepens tonal gradation in saturated colors
    // instead of letting them clip flat - approximated as extra saturation
    // and contrast, since there's no per-luma color path to hook into here.
    val saturation = clamp(base.baseSaturation * (1f + color / 8f) * (1f + colorChromeStrength * 0.1f), 0f, 2f)
    val contrast = clamp(
        base.baseContrast * (1f + (highlight + shadow) / 16f) * (1f - drStrength * 0.1f) * (1f + colorChromeStrength * 0.06f),
        0.5f,
        1.6f,
    )

    val grainIntensityBase = when (grainStrength) {
        GrainStrength.OFF -> 0f
        GrainStrength.WEAK -> 0.25f
        GrainStrength.STRONG -> 0.55f
    }
    // High ISO NR: Fuji's in-camera noise reduction, -4 (weak NR, more of
    // the sensor's own grain survives, crisper) .. +4 (strong NR, smoother
    // and softer, grain smoothed away with the fine detail).
    val nrFactor = clamp(-highIsoNr / 4f, -1f, 1f)
    val grainIntensity = clamp01(grainIntensityBase * (1f + nrFactor * 0.5f))
    val grainSizePx = (if (grainSize == GrainSize.LARGE) 1.6f else 1f) * GRAIN_PIXELS_PER_UNIT
    // No gauge concept for photo recipes, so grain size stands in for it:
    // LARGE reads as a coarser 16mm-like plate, SMALL as a finer 35mm-like
    // one; grain strength picks the standard/heavy tier of that plate.
    val grainSetKey = if (grainStrength == GrainStrength.OFF) {
        ""
    } else {
        val gaugeKey = if (grainSize == GrainSize.LARGE) "16mm" else "35mm"
        val tier = if (grainStrength == GrainStrength.STRONG) "heavy" else "standard"
        "${gaugeKey}_$tier"
    }

    // Sharpness and Clarity both eat into the same optical-softness budget,
    // but differently: Sharpness is global edge/acutance, Clarity is Fuji's
    // local/midtone contrast-texture control.
    val softness = clamp01(
        -sharpness / 8f - clarity / 10f - nrFactor * 0.15f + grainSoftnessCoupling(grainIntensity),
    )

    return RenderLook(
        name = name,
        warmth = clamp(base.baseWarmth + wbWarmth * 0.6f - fxBlueStrength * 0.05f, -1f, 1f),
        tintGreenMagenta = 0f,
        saturation = saturation,
        contrast = contrast,
        highlightRolloff = highlightRolloff,
        shadowLift = shadowLift,
        grainIntensity = grainIntensity,
        grainSize = grainSizePx,
        softness = softness,
        vignette = 0.08f,
        grainSetKey = grainSetKey,
    )
}

/** Applies the user's global Aus/Standard/Heavy grain switch on top of a
 * recipe- or film-stock-derived look. OFF wins outright, regardless of what
 * the recipe/stock itself wanted. STANDARD/HEAVY keep whichever gauge the
 * look already picked (falling back to 35mm for looks that had no grain
 * concept at all, e.g. grainStrength OFF) and swap in that gauge's
 * standard/heavy plate, using the look's own grainIntensity when it had
 * one so per-recipe/per-stock intensity tuning still comes through, or a
 * sensible default when the recipe/stock had grain off entirely (since the
 * global switch is now what decides on/off, not the recipe). */
fun RenderLook.withGrainOverride(override: GrainOverride): RenderLook {
    if (override == GrainOverride.OFF) {
        return copy(grainIntensity = 0f, grainSetKey = "")
    }
    val gauge = grainSetKey.substringBeforeLast('_', "").ifEmpty { "35mm" }
    val tier = if (override == GrainOverride.HEAVY) "heavy" else "standard"
    val intensity = if (grainIntensity > 0f) grainIntensity else 0.35f
    return copy(grainIntensity = intensity, grainSetKey = "${gauge}_$tier")
}

fun FilmStockPreset.toRenderLook(): RenderLook {
    // Video stocks already carry a real gauge (8mm/16mm/35mm/...), so they
    // get the matching real grain plate directly; grainIntensity itself
    // (already tuned per stock in the seed data) picks the standard/heavy
    // tier of that same gauge's plate.
    val grainSetKey = if (grainIntensity <= 0f) {
        ""
    } else {
        val gaugeKey = gaugeAssetKey(gauge)
        val tier = if (grainIntensity >= 0.5f) "heavy" else "standard"
        "${gaugeKey}_$tier"
    }
    return RenderLook(
        name = name,
        warmth = warmth,
        tintGreenMagenta = 0f,
        saturation = saturation,
        contrast = 1f,
        highlightRolloff = highlightRolloff,
        shadowLift = shadowLift,
        grainIntensity = grainIntensity,
        grainSize = grainSize * GRAIN_PIXELS_PER_UNIT,
        softness = clamp01(softness + grainSoftnessCoupling(grainIntensity)),
        vignette = vignette,
        grainSetKey = grainSetKey,
    )
}
