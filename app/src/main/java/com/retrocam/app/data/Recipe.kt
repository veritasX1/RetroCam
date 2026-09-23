package com.retrocam.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One photo "film simulation recipe", using the same field names Fuji X
 * Weekly and similar sites use - so a user can transcribe a recipe from a
 * web page directly into the in-app editor field by field, without having
 * to translate terminology.
 *
 * Several fields (whiteBalance, isoNote, exposureCompensation) are kept as
 * free text on purpose: real recipes write these inconsistently
 * ("6600K, -1 Red & -3 Blue", "Auto, up to ISO 6400", "+1/3 to +1 1/3") and
 * forcing a strict numeric type would make copying a recipe harder, not
 * easier.
 */
@Entity(tableName = "recipes")
data class Recipe(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val filmSimulation: String = "Provia/Standard",
    val dynamicRange: String = "DR100",
    val grainStrength: GrainStrength = GrainStrength.OFF,
    val grainSize: GrainSize = GrainSize.SMALL,
    /** Fuji Color Chrome Effect / FX Blue - per the X100VI manual,
     * "increase the range of tones available for rendering colors that
     * tend to be highly saturated, such as reds, yellows, and greens"
     * (Effect) / "...for rendering blues" (FX Blue). A chroma-and-hue-
     * gated local contrast boost, not a flat saturation/warmth nudge -
     * see ShaderSource.applyColorChrome. */
    val colorChromeEffect: EffectStrength = EffectStrength.OFF,
    val colorChromeFxBlue: EffectStrength = EffectStrength.OFF,
    val whiteBalance: String = "Auto",
    /** Fuji WB Shift: -9..+9 per channel (per-camera fine-tune grid). */
    val wbShiftRed: Int = 0,
    val wbShiftBlue: Int = 0,
    /** Fuji Tone Curve: -2 (soft/lifted) .. +4 (harsh/clipped), per the
     * X100VI manual - "makes shadows and highlights harsher" at higher
     * values, "softer" at lower ones. Two independent axes (Fuji's own
     * HIGHLIGHTS/SHADOWS), not a single highlight-vs-shadow slider. */
    val highlight: Float = 0f,
    val shadow: Float = 0f,
    /** Fuji Color: -4..+4, adjusts color density (saturation). */
    val color: Int = 0,
    /** Fuji Sharpness: -4..+4, edge/acutance sharpening - not simulated
     * here (no edge detection in the render pipeline), kept only as a
     * cheap softness-budget proxy, see RenderLook. */
    val sharpness: Int = 0,
    /** Fuji High ISO NR: -4..+4 - "reduce noise... smooth outlines" at
     * higher values, "leave outlines visible" at lower ones. */
    val highIsoNr: Int = 0,
    /** Fuji Clarity: -5..+5 - per the X100VI manual, "increase definition
     * while altering tones in highlights and shadows as little as
     * possible": a local/midtone contrast boost, NOT the same mechanism
     * as Sharpness - see ShaderSource.applyClarity. */
    val clarity: Int = 0,
    val isoNote: String = "Auto",
    val exposureCompensation: String = "0",
    val notes: String = "",
    val isBuiltIn: Boolean = false,
)

enum class GrainStrength { OFF, WEAK, STRONG }
enum class GrainSize { SMALL, LARGE }
enum class EffectStrength { OFF, WEAK, STRONG }

/** Approximate starting character of each Fuji film simulation base look,
 * before recipe-specific highlight/shadow/color/WB adjustments are
 * layered on top. Anything not listed falls back to a neutral Provia-like
 * baseline - this is a deliberate approximation, not a color-science
 * match, see LookMapper. Base values cross-checked against Fujifilm's own
 * X100VI manual (film simulation list/descriptions) and independent
 * characterizations of each simulation's color/contrast personality. */
enum class FilmSimulationBase(
    val displayName: String,
    val matchNames: List<String>,
    val baseWarmth: Float,
    val baseSaturation: Float,
    val baseContrast: Float,
) {
    PROVIA("Provia/Standard", listOf("provia", "standard"), 0f, 1.0f, 1.0f),
    // "Extremely saturated and contrasty" - the most aggressive simulation
    // Fuji makes, contrast bumped to reflect that (was on par with Classic
    // Neg./Reala Ace before, which undersold how far Velvia actually goes).
    VELVIA("Velvia/Vivid", listOf("velvia", "vivid"), 0.05f, 1.35f, 1.2f),
    ASTIA("Astia/Soft", listOf("astia", "soft"), 0.05f, 1.05f, 0.9f),
    CLASSIC_CHROME("Classic Chrome", listOf("classic chrome"), -0.05f, 0.82f, 1.05f),
    CLASSIC_NEG("Classic Neg.", listOf("classic negative", "classic neg"), 0.08f, 0.9f, 1.1f),
    // Slightly warm (warmer than Classic Chrome's -0.05), not neutral -
    // "warmer than Classic Chrome, slightly more colorful".
    PRO_NEG_HI("Pro Neg. Hi", listOf("pro neg hi", "pro neg. hi"), 0.04f, 0.88f, 1.05f),
    PRO_NEG_STD("Pro Neg. Std", listOf("pro neg std", "pro neg. std"), 0.04f, 0.85f, 0.92f),
    ETERNA("Eterna/Cinema", listOf("eterna"), -0.05f, 0.75f, 0.8f),
    ETERNA_BLEACH_BYPASS("Eterna Bleach Bypass", listOf("eterna bleach bypass"), -0.1f, 0.4f, 1.25f),
    // "acros" only - see forName's longest-match resolution, which is what
    // actually lets the +Ye/+R/+G variants below win over this generic
    // entry despite all four sharing the "acros" substring.
    ACROS("Acros", listOf("acros"), 0f, 0.0f, 1.1f),
    ACROS_YE("Acros+Ye Filter", listOf("acros+ye", "acros +ye", "acros yellow"), 0f, 0.0f, 1.12f),
    ACROS_R("Acros+R Filter", listOf("acros+r", "acros +r", "acros red"), 0f, 0.0f, 1.18f),
    ACROS_G("Acros+G Filter", listOf("acros+g", "acros +g", "acros green"), 0f, 0.0f, 1.08f),
    // A real, separate simulation from Acros (was previously folded into
    // it) - "flatter, balanced, less moody than Acros... lighter/softer
    // tones", i.e. plain Monochrome skips Acros' extra fine-detail/
    // contrast processing.
    MONOCHROME("Monochrome", listOf("monochrome", "mono", "black and white", "b&w"), 0f, 0.0f, 1.0f),
    MONOCHROME_YE("Monochrome+Ye Filter", listOf("monochrome+ye", "mono+ye", "monochrome yellow"), 0f, 0.0f, 1.02f),
    MONOCHROME_R("Monochrome+R Filter", listOf("monochrome+r", "mono+r", "monochrome red"), 0f, 0.0f, 1.08f),
    MONOCHROME_G("Monochrome+G Filter", listOf("monochrome+g", "mono+g", "monochrome green"), 0f, 0.0f, 0.98f),
    SEPIA("Sepia", listOf("sepia"), 0.4f, 0.15f, 1.0f),
    NOSTALGIC_NEG("Nostalgic Neg.", listOf("nostalgic negative", "nostalgic neg"), 0.15f, 0.85f, 0.95f),
    // "Neutral color base with STRONG contrast... hard tonality" - similar
    // to Provia but with noticeably more punch, not just a hair more.
    REALA_ACE("Reala Ace", listOf("reala ace"), 0.02f, 1.0f, 1.12f),
    ;

    companion object {
        // Picks the LONGEST matching term across all entries rather than
        // the first-declared entry with any match - substring containment
        // alone is order-dependent and was silently swallowing the more
        // specific "acros+ye"/"mono+r"/etc. filter variants into their
        // generic base (e.g. "Acros+R Filter".contains("acros") is true,
        // so plain ACROS - declared first - always won before this fix,
        // regardless of the more specific ACROS_R entry existing).
        fun forName(name: String): FilmSimulationBase {
            val lower = name.lowercase()
            return entries
                .flatMap { base -> base.matchNames.map { term -> base to term } }
                .filter { (_, term) -> lower.contains(term) }
                .maxByOrNull { (_, term) -> term.length }
                ?.first ?: PROVIA
        }
    }
}

/** Fuji X100V IQ Menu > Dynamic Range options (Auto or a fixed DR100/200/400,
 * the latter two requiring at least ISO 400/800 respectively on the body -
 * not enforced here since this only drives the render look, not a real
 * sensor's ISO floor). */
val DYNAMIC_RANGE_OPTIONS = listOf("DR-Auto", "DR100", "DR200", "DR400")

/** Full parameter dump for EXIF (UserComment) - "the filter and its
 * settings" the user can look up later, not just a name that stops
 * meaning anything once the recipe is edited or deleted. */
fun Recipe.toDescription(): String = "$name (Film Simulation: $filmSimulation, Dynamic Range: $dynamicRange, " +
    "Grain: $grainStrength/$grainSize, Color Chrome: $colorChromeEffect, Color Chrome FX Blue: $colorChromeFxBlue, " +
    "White Balance: $whiteBalance ${wbShiftRed}R/${wbShiftBlue}B, Highlight: $highlight, Shadow: $shadow, " +
    "Color: $color, Sharpness: $sharpness, High ISO NR: $highIsoNr, Clarity: $clarity)"
