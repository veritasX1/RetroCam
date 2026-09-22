package com.retrocam.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A video "film stock" look, modeled after real cinema film gauges (8mm,
 * 16mm, 35mm) rather than Fuji's naming - see the app's README for the
 * research behind the three built-in presets. All fields are already
 * normalized (0f..1f, or -1f..1f) rather than free text, since - unlike
 * photo recipes - there's no equivalent "copy these exact field values
 * from a website" source to match the format of.
 */
@Entity(tableName = "film_stocks")
data class FilmStockPreset(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val gauge: String,
    /** 0f = no visible grain, 1f = heavy/dominant grain (Super 8 territory). */
    val grainIntensity: Float = 0.3f,
    /** Relative grain particle size; larger gauges have finer (smaller) grain. */
    val grainSize: Float = 1.0f,
    /** -1f (cool/blue) .. +1f (warm/amber) base color cast. */
    val warmth: Float = 0f,
    /** 1f = neutral, <1f desaturated, >1f punchier. */
    val saturation: Float = 1.0f,
    /** 0f = no vignette, 1f = strong corner darkening. */
    val vignette: Float = 0.15f,
    /** 0f = hard digital highlight clipping, 1f = soft filmic highlight rolloff. */
    val highlightRolloff: Float = 0.4f,
    /** 0f = true digital blacks, 1f = strongly lifted/"milky" film blacks. */
    val shadowLift: Float = 0.2f,
    /** Extra blur beyond the user's global softness setting, standing in for
     * the gauge's own optical/resolution limit (large for 8mm, minimal for 35mm). */
    val softness: Float = 0.2f,
    val notes: String = "",
    val isBuiltIn: Boolean = false,
)

fun FilmStockPreset.toDescription(): String = "$name (Gauge: $gauge, Grain: $grainIntensity/$grainSize, " +
    "Warmth: $warmth, Saturation: $saturation, Vignette: $vignette, Highlight Rolloff: $highlightRolloff, " +
    "Shadow Lift: $shadowLift, Softness: $softness)"

const val NO_FILTER_DESCRIPTION = "No filter"
