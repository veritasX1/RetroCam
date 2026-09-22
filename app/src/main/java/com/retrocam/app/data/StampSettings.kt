package com.retrocam.app.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Field order, mirroring the fact that US and European date-back cameras
 * disagreed about this exact thing. ISO is included because plenty of
 * older cameras (and most digital ones) default to it too. */
enum class DateOrder { DMY, MDY, YMD }

enum class DateSeparator(val text: String) {
    DOT("."), SLASH("/"), SPACE(" "), NONE("")
}

enum class YearStyle { FULL, SHORT, SHORT_APOSTROPHE }

/** Classic cameras used either an orange-red or a yellow-green LCD/LED -
 * both real, both easy to offer. */
enum class StampColor(val argb: Long) {
    ORANGE(0xFFFF7A1A), YELLOW(0xFFE6C229), RED(0xFFE0402A)
}

enum class StampCorner { BOTTOM_RIGHT, BOTTOM_LEFT, TOP_RIGHT, TOP_LEFT }

enum class LocationStampMode { OFF, COORDINATES, POSTAL_CODE }

/** Real date-backs were either 7-segment or dot-matrix LED arrays - two
 * 7-segment weights plus a real dot-matrix renderer cover both looks
 * (see DotMatrixFont.kt for why that one isn't a font file). */
enum class StampTypeface { SEVEN_SEGMENT_ITALIC, SEVEN_SEGMENT_UPRIGHT, DOT_MATRIX }

/** How the stamp's pixels combine with the photo underneath it, same idea
 * as a Photoshop layer blend mode - see PhotoPostProcessor for where this
 * is applied. NORMAL (plain alpha compositing, `xfermode = null`) is what
 * this app always did before this setting existed. The rest map onto
 * android.graphics.PorterDuff.Mode, which covers a real (if smaller) subset
 * of the Photoshop list without needing API 29+'s BlendMode enum - this
 * app's minSdk is 26. LINEAR_DODGE is the closest to how a real date-back's
 * LEDs actually expose onto film: it *adds* light rather than sitting on
 * top of it, so it naturally reads brighter/hotter over dark image areas
 * and can blow out to solid white over already-bright ones, the same way
 * real over/under-exposed date stamps do. */
enum class StampBlendMode(val label: String, val porterDuffMode: android.graphics.PorterDuff.Mode?) {
    NORMAL("Normal", null),
    DARKEN("Abdunkeln", android.graphics.PorterDuff.Mode.DARKEN),
    MULTIPLY("Multiplizieren", android.graphics.PorterDuff.Mode.MULTIPLY),
    LIGHTEN("Aufhellen", android.graphics.PorterDuff.Mode.LIGHTEN),
    SCREEN("Negativ multiplizieren", android.graphics.PorterDuff.Mode.SCREEN),
    LINEAR_DODGE("Linear abwedeln (Addieren)", android.graphics.PorterDuff.Mode.ADD),
    OVERLAY("Ineinanderkopieren", android.graphics.PorterDuff.Mode.OVERLAY),
}

data class DateStampSettings(
    val enabled: Boolean = false,
    val order: DateOrder = DateOrder.DMY,
    val separator: DateSeparator = DateSeparator.DOT,
    val yearStyle: YearStyle = YearStyle.SHORT,
    val color: StampColor = StampColor.ORANGE,
    val corner: StampCorner = StampCorner.BOTTOM_RIGHT,
    val typeface: StampTypeface = StampTypeface.SEVEN_SEGMENT_ITALIC,
    val blendMode: StampBlendMode = StampBlendMode.NORMAL,
    /** Many real date-backs don't zero-pad day/month ("4" not "04") - both
     * looks show up across real cameras, so it's a toggle, not a fixed rule. */
    val zeroPad: Boolean = true,
)

fun formatDateStamp(date: Date, settings: DateStampSettings): String {
    val cal = Calendar.getInstance().apply { time = date }
    val dayNum = cal.get(Calendar.DAY_OF_MONTH)
    val monthNum = cal.get(Calendar.MONTH) + 1
    val day = if (settings.zeroPad) "%02d".format(dayNum) else dayNum.toString()
    val month = if (settings.zeroPad) "%02d".format(monthNum) else monthNum.toString()
    val yearFull = cal.get(Calendar.YEAR)
    val year = when (settings.yearStyle) {
        YearStyle.FULL -> yearFull.toString()
        YearStyle.SHORT -> "%02d".format(yearFull % 100)
        YearStyle.SHORT_APOSTROPHE -> "'%02d".format(yearFull % 100)
    }
    val parts = when (settings.order) {
        DateOrder.DMY -> listOf(day, month, year)
        DateOrder.MDY -> listOf(month, day, year)
        DateOrder.YMD -> listOf(year, month, day)
    }
    return parts.joinToString(settings.separator.text)
}

/** Same LCD-style font/color as the date stamp, formatted either as
 * decimal-degree coordinates or (if the caller already resolved one) a
 * postal code - see LocationStamp.kt for how the value itself is obtained. */
fun formatCoordinates(latitude: Double, longitude: Double): String {
    val latHemi = if (latitude >= 0) "N" else "S"
    val lonHemi = if (longitude >= 0) "E" else "W"
    return "%.1f°%s %.1f°%s".format(Locale.ROOT, kotlin.math.abs(latitude), latHemi, kotlin.math.abs(longitude), lonHemi)
}
