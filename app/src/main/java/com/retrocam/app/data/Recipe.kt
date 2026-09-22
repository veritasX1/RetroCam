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
    val colorChromeEffect: EffectStrength = EffectStrength.OFF,
    val colorChromeFxBlue: EffectStrength = EffectStrength.OFF,
    val whiteBalance: String = "Auto",
    val wbShiftRed: Int = 0,
    val wbShiftBlue: Int = 0,
    val highlight: Float = 0f,
    val shadow: Float = 0f,
    val color: Int = 0,
    val sharpness: Int = 0,
    val highIsoNr: Int = 0,
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
 * match, see LookMapper. */
enum class FilmSimulationBase(
    val displayName: String,
    val matchNames: List<String>,
    val baseWarmth: Float,
    val baseSaturation: Float,
    val baseContrast: Float,
) {
    PROVIA("Provia/Standard", listOf("provia", "standard"), 0f, 1.0f, 1.0f),
    VELVIA("Velvia/Vivid", listOf("velvia", "vivid"), 0.05f, 1.35f, 1.15f),
    ASTIA("Astia/Soft", listOf("astia", "soft"), 0.05f, 1.05f, 0.9f),
    CLASSIC_CHROME("Classic Chrome", listOf("classic chrome"), -0.05f, 0.82f, 1.05f),
    CLASSIC_NEG("Classic Neg.", listOf("classic negative", "classic neg"), 0.08f, 0.9f, 1.1f),
    PRO_NEG_HI("Pro Neg. Hi", listOf("pro neg hi", "pro neg. hi"), 0f, 0.88f, 1.05f),
    PRO_NEG_STD("Pro Neg. Std", listOf("pro neg std", "pro neg. std"), 0f, 0.85f, 0.92f),
    ETERNA("Eterna/Cinema", listOf("eterna"), -0.05f, 0.75f, 0.8f),
    ETERNA_BLEACH_BYPASS("Eterna Bleach Bypass", listOf("eterna bleach bypass"), -0.1f, 0.4f, 1.25f),
    ACROS("Acros", listOf("acros", "mono", "monochrome", "black and white", "b&w"), 0f, 0.0f, 1.1f),
    ACROS_YE("Acros+Ye Filter", listOf("acros+ye", "acros +ye", "acros yellow"), 0f, 0.0f, 1.12f),
    ACROS_R("Acros+R Filter", listOf("acros+r", "acros +r", "acros red"), 0f, 0.0f, 1.18f),
    ACROS_G("Acros+G Filter", listOf("acros+g", "acros +g", "acros green"), 0f, 0.0f, 1.08f),
    SEPIA("Sepia", listOf("sepia"), 0.4f, 0.15f, 1.0f),
    NOSTALGIC_NEG("Nostalgic Neg.", listOf("nostalgic negative", "nostalgic neg"), 0.15f, 0.85f, 0.95f),
    REALA_ACE("Reala Ace", listOf("reala ace"), 0.02f, 1.0f, 1.05f),
    ;

    companion object {
        fun forName(name: String): FilmSimulationBase {
            val lower = name.lowercase()
            return entries.firstOrNull { base -> base.matchNames.any { lower.contains(it) } } ?: PROVIA
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
