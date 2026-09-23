package com.retrocam.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ShutterSound { OFF, CLICK, BEEP, CHUNK }

/** Discrete steps rather than a raw slider, to match the rest of the
 * settings UI - see [Softness.blurAmount] for what each step actually
 * does to the render pipeline. */
enum class Softness(val blurAmount: Float) {
    OFF(0f), LOW(0.12f), MEDIUM(0.28f), HIGH(0.5f)
}

enum class CaptureMode { PHOTO, VIDEO }

/** Photo-only (video keeps its own fixed 16:9) - applied as a center-crop
 * in post-processing (see PhotoPostProcessor.cropToAspectRatio), not via
 * CameraX's own ResolutionSelector: AspectRatioStrategy only offers 4:3/
 * 16:9 buckets, nothing for 1:1, and post-crop works uniformly for all
 * three regardless of whatever raw aspect ImageCapture actually lands on. */
enum class CaptureAspectRatio(val label: String, val ratio: Float) {
    RATIO_4_3("4:3", 4f / 3f),
    RATIO_16_9("16:9", 16f / 9f),
    RATIO_1_1("1:1", 1f),
}

/** Global override for the real-film-grain overlay (see RenderLook.grainSetKey /
 * GrainTexture) - applies uniformly to both photo and video, on top of
 * whatever gauge the active recipe/film stock would otherwise pick, so the
 * user gets one direct switch instead of the tier being implicitly derived
 * per-recipe from GrainStrength/grainIntensity. */
enum class GrainOverride { OFF, STANDARD, HEAVY }

/** How the grain texture composites against the image, evaluated per-pixel
 * in the shader (see ShaderSource.applyGrainBlend) - the same idea as the
 * date stamp's Photoshop-style blend modes, but shaped for grain
 * specifically since real film/sensor grain isn't equally visible
 * everywhere: it reads much more strongly in shadows than in highlights
 * (weak signal there, so the noise floor dominates - the same reason
 * digital high-ISO noise is worst in shadows). FILMKORN is the physically-
 * motivated default; the others are legacy/stylized alternatives. */
enum class GrainBlendMode(val shaderValue: Float) {
    NORMAL(0f), MULTIPLY(1f), FILMKORN(2f), LEUCHTEND(3f)
}

/** A creative color-grade LUT (see RenderLook.lutKey/LutTexture), selected
 * independently of whatever recipe/film-stock is active - orthogonal to
 * the Fuji-recipe-style parametric looks rather than a field on Recipe
 * itself, since these don't correspond to any real Fuji recipe field
 * (modeled after OldRoll/8mm Vintage Camera's own separate filter-list
 * concept, layered on top instead of baked into one fixed preset). NONE
 * has no lutKey, and the shader skips sampling sLut entirely in that case. */
enum class CinematicLook(val lutKey: String?) {
    NONE(null),
    DIGITAL_TO_FILM("digital_to_film"),
    MODERN_35MM("modern_35mm"),
    VINTAGE("vintage"),
    BLEACH_BYPASS("bleach_bypass"),
}

/** Sentinel id (Room autoIncrement rows never get 0) meaning "no filter" -
 * used for both recipes and film stocks, and doubles as the value an
 * unset preference resolves to, so a fresh install defaults to unfiltered
 * rather than to whichever recipe happens to be first. */
const val NO_FILTER_ID = 0L

private val Context.dataStore by preferencesDataStore(name = "retrocam_settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val SHUTTER_SOUND = stringPreferencesKey("shutter_sound")
        val SOFTNESS = stringPreferencesKey("softness")
        val CAPTURE_MODE = stringPreferencesKey("capture_mode")
        val SELECTED_RECIPE_ID = longPreferencesKey("selected_recipe_id")
        val SELECTED_FILM_STOCK_ID = longPreferencesKey("selected_film_stock_id")
        val SELECTED_CAMERA_KEY = stringPreferencesKey("selected_camera_key")
        val VIDEO_FPS = intPreferencesKey("video_fps") // 0 = auto/device default
        val LOCATION_ENABLED = booleanPreferencesKey("location_enabled")
        val DATE_STAMP_ENABLED = booleanPreferencesKey("date_stamp_enabled")
        val DATE_STAMP_ORDER = stringPreferencesKey("date_stamp_order")
        val DATE_STAMP_SEPARATOR = stringPreferencesKey("date_stamp_separator")
        val DATE_STAMP_YEAR_STYLE = stringPreferencesKey("date_stamp_year_style")
        val DATE_STAMP_COLOR = stringPreferencesKey("date_stamp_color")
        val DATE_STAMP_CORNER = stringPreferencesKey("date_stamp_corner")
        val DATE_STAMP_TYPEFACE = stringPreferencesKey("date_stamp_typeface")
        val DATE_STAMP_BLEND_MODE = stringPreferencesKey("date_stamp_blend_mode")
        val DATE_STAMP_ZERO_PAD = booleanPreferencesKey("date_stamp_zero_pad")
        val LOCATION_STAMP_MODE = stringPreferencesKey("location_stamp_mode")
        val GRAIN_OVERRIDE = stringPreferencesKey("grain_override")
        val CAPTURE_ASPECT_RATIO = stringPreferencesKey("capture_aspect_ratio")
        val GRAIN_BLEND_MODE = stringPreferencesKey("grain_blend_mode")
        val CINEMATIC_LOOK = stringPreferencesKey("cinematic_look")
        val CINEMATIC_LOOK_STRENGTH = floatPreferencesKey("cinematic_look_strength")
    }

    val shutterSound: Flow<ShutterSound> = context.dataStore.data.map { prefs ->
        prefs[Keys.SHUTTER_SOUND]?.let { runCatching { ShutterSound.valueOf(it) }.getOrNull() }
            ?: ShutterSound.CLICK
    }

    val softness: Flow<Softness> = context.dataStore.data.map { prefs ->
        prefs[Keys.SOFTNESS]?.let { runCatching { Softness.valueOf(it) }.getOrNull() }
            ?: Softness.OFF
    }

    val captureMode: Flow<CaptureMode> = context.dataStore.data.map { prefs ->
        prefs[Keys.CAPTURE_MODE]?.let { runCatching { CaptureMode.valueOf(it) }.getOrNull() }
            ?: CaptureMode.PHOTO
    }

    val selectedRecipeId: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[Keys.SELECTED_RECIPE_ID] ?: NO_FILTER_ID
    }

    val selectedFilmStockId: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[Keys.SELECTED_FILM_STOCK_ID] ?: NO_FILTER_ID
    }

    /** null = "use the default back camera" (never persisted yet, or the
     * previously-picked camera is no longer present on this device). */
    val selectedCameraKey: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[Keys.SELECTED_CAMERA_KEY]
    }

    suspend fun setShutterSound(value: ShutterSound) {
        context.dataStore.edit { it[Keys.SHUTTER_SOUND] = value.name }
    }

    suspend fun setSoftness(value: Softness) {
        context.dataStore.edit { it[Keys.SOFTNESS] = value.name }
    }

    suspend fun setCaptureMode(value: CaptureMode) {
        context.dataStore.edit { it[Keys.CAPTURE_MODE] = value.name }
    }

    suspend fun setSelectedRecipeId(id: Long) {
        context.dataStore.edit { it[Keys.SELECTED_RECIPE_ID] = id }
    }

    suspend fun setSelectedFilmStockId(id: Long) {
        context.dataStore.edit { it[Keys.SELECTED_FILM_STOCK_ID] = id }
    }

    suspend fun setSelectedCameraKey(key: String) {
        context.dataStore.edit { it[Keys.SELECTED_CAMERA_KEY] = key }
    }

    /** 0 = auto (device/quality default, no explicit AE target FPS range set). */
    val videoFps: Flow<Int> = context.dataStore.data.map { prefs -> prefs[Keys.VIDEO_FPS] ?: 0 }

    suspend fun setVideoFps(fps: Int) {
        context.dataStore.edit { it[Keys.VIDEO_FPS] = fps }
    }

    /** Off by default - nothing location-related runs until the user opts
     * in here, and turning it on is what triggers the runtime permission
     * request (see CameraViewModel), not app startup. */
    val locationEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.LOCATION_ENABLED] ?: false
    }

    suspend fun setLocationEnabled(value: Boolean) {
        context.dataStore.edit { it[Keys.LOCATION_ENABLED] = value }
    }

    val dateStampSettings: Flow<DateStampSettings> = context.dataStore.data.map { prefs ->
        DateStampSettings(
            enabled = prefs[Keys.DATE_STAMP_ENABLED] ?: false,
            order = prefs[Keys.DATE_STAMP_ORDER]?.let { runCatching { DateOrder.valueOf(it) }.getOrNull() } ?: DateOrder.DMY,
            separator = prefs[Keys.DATE_STAMP_SEPARATOR]?.let { runCatching { DateSeparator.valueOf(it) }.getOrNull() } ?: DateSeparator.DOT,
            yearStyle = prefs[Keys.DATE_STAMP_YEAR_STYLE]?.let { runCatching { YearStyle.valueOf(it) }.getOrNull() } ?: YearStyle.SHORT,
            color = prefs[Keys.DATE_STAMP_COLOR]?.let { runCatching { StampColor.valueOf(it) }.getOrNull() } ?: StampColor.ORANGE,
            corner = prefs[Keys.DATE_STAMP_CORNER]?.let { runCatching { StampCorner.valueOf(it) }.getOrNull() } ?: StampCorner.BOTTOM_RIGHT,
            typeface = prefs[Keys.DATE_STAMP_TYPEFACE]?.let { runCatching { StampTypeface.valueOf(it) }.getOrNull() } ?: StampTypeface.SEVEN_SEGMENT_ITALIC,
            blendMode = prefs[Keys.DATE_STAMP_BLEND_MODE]?.let { runCatching { StampBlendMode.valueOf(it) }.getOrNull() } ?: StampBlendMode.NORMAL,
            zeroPad = prefs[Keys.DATE_STAMP_ZERO_PAD] ?: true,
        )
    }

    suspend fun setDateStampSettings(value: DateStampSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DATE_STAMP_ENABLED] = value.enabled
            prefs[Keys.DATE_STAMP_ORDER] = value.order.name
            prefs[Keys.DATE_STAMP_SEPARATOR] = value.separator.name
            prefs[Keys.DATE_STAMP_YEAR_STYLE] = value.yearStyle.name
            prefs[Keys.DATE_STAMP_COLOR] = value.color.name
            prefs[Keys.DATE_STAMP_CORNER] = value.corner.name
            prefs[Keys.DATE_STAMP_TYPEFACE] = value.typeface.name
            prefs[Keys.DATE_STAMP_BLEND_MODE] = value.blendMode.name
            prefs[Keys.DATE_STAMP_ZERO_PAD] = value.zeroPad
        }
    }

    val locationStampMode: Flow<LocationStampMode> = context.dataStore.data.map { prefs ->
        prefs[Keys.LOCATION_STAMP_MODE]?.let { runCatching { LocationStampMode.valueOf(it) }.getOrNull() }
            ?: LocationStampMode.OFF
    }

    suspend fun setLocationStampMode(value: LocationStampMode) {
        context.dataStore.edit { it[Keys.LOCATION_STAMP_MODE] = value.name }
    }

    val captureAspectRatio: Flow<CaptureAspectRatio> = context.dataStore.data.map { prefs ->
        prefs[Keys.CAPTURE_ASPECT_RATIO]?.let { runCatching { CaptureAspectRatio.valueOf(it) }.getOrNull() }
            ?: CaptureAspectRatio.RATIO_4_3
    }

    suspend fun setCaptureAspectRatio(value: CaptureAspectRatio) {
        context.dataStore.edit { it[Keys.CAPTURE_ASPECT_RATIO] = value.name }
    }

    val grainOverride: Flow<GrainOverride> = context.dataStore.data.map { prefs ->
        prefs[Keys.GRAIN_OVERRIDE]?.let { runCatching { GrainOverride.valueOf(it) }.getOrNull() }
            ?: GrainOverride.STANDARD
    }

    suspend fun setGrainOverride(value: GrainOverride) {
        context.dataStore.edit { it[Keys.GRAIN_OVERRIDE] = value.name }
    }

    val grainBlendMode: Flow<GrainBlendMode> = context.dataStore.data.map { prefs ->
        prefs[Keys.GRAIN_BLEND_MODE]?.let { runCatching { GrainBlendMode.valueOf(it) }.getOrNull() }
            ?: GrainBlendMode.FILMKORN
    }

    suspend fun setGrainBlendMode(value: GrainBlendMode) {
        context.dataStore.edit { it[Keys.GRAIN_BLEND_MODE] = value.name }
    }

    val cinematicLook: Flow<CinematicLook> = context.dataStore.data.map { prefs ->
        prefs[Keys.CINEMATIC_LOOK]?.let { runCatching { CinematicLook.valueOf(it) }.getOrNull() }
            ?: CinematicLook.NONE
    }

    suspend fun setCinematicLook(value: CinematicLook) {
        context.dataStore.edit { it[Keys.CINEMATIC_LOOK] = value.name }
    }

    val cinematicLookStrength: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[Keys.CINEMATIC_LOOK_STRENGTH] ?: 0.65f
    }

    suspend fun setCinematicLookStrength(value: Float) {
        context.dataStore.edit { it[Keys.CINEMATIC_LOOK_STRENGTH] = value.coerceIn(0f, 1f) }
    }
}

/**
 * Android forces an audible, non-mutable shutter sound on devices
 * configured for Japan/South Korea (SIM/region detection) - no app, this
 * one included, can override that. The check for this
 * (`AudioManager.isCameraSoundForced()`) is a hidden/system API, not part
 * of the public SDK, so it can't be called here to grey out "Off" at
 * runtime - this is a static, informational note instead. A German-region
 * phone (this app's primary target) is never affected.
 */
const val SHUTTER_SOUND_REGION_NOTE =
    "In Japan und Südkorea erzwingt Android selbst einen hörbaren Auslöseton, unabhängig von dieser Einstellung."
