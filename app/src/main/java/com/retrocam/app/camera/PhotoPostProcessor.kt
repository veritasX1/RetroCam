package com.retrocam.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuffXfermode
import android.location.Location
import android.net.Uri
import androidx.core.content.res.ResourcesCompat
import androidx.exifinterface.media.ExifInterface
import com.retrocam.app.R
import com.retrocam.app.data.DateStampSettings
import com.retrocam.app.data.StampCorner
import com.retrocam.app.data.StampTypeface
import com.retrocam.app.data.formatDateStamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import kotlin.math.min

/**
 * Runs after a photo is already saved (i.e. after the film-look GL pass
 * has been baked in by RetroCamSurfaceProcessor): optionally burns a
 * date/location stamp into the pixels like an old camera's date-back, and
 * always writes the active recipe (plus GPS, if enabled) into the file's
 * EXIF - see README "Privacy" for why this exists instead of silently
 * tracking anything.
 */
object PhotoPostProcessor {

    suspend fun process(
        context: Context,
        uri: Uri,
        recipeDescription: String,
        dateStamp: DateStampSettings,
        locationText: String?,
        location: Location?,
    ) = withContext(Dispatchers.IO) {
        if (dateStamp.enabled || locationText != null) {
            burnInStamp(context, uri, dateStamp, locationText)
        }
        writeExif(context, uri, recipeDescription, location)
    }

    private fun burnInStamp(context: Context, uri: Uri, dateStamp: DateStampSettings, locationText: String?) {
        val original = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return
        val bitmap = original.copy(Bitmap.Config.ARGB_8888, true)
        original.recycle()

        val canvas = Canvas(bitmap)
        val shortSide = min(bitmap.width, bitmap.height).toFloat()
        val margin = shortSide * 0.032f
        val stampColor = dateStamp.color.argb.toInt()

        val lines = listOfNotNull(
            locationText,
            if (dateStamp.enabled) formatDateStamp(Date(), dateStamp) else null,
        )
        if (lines.isEmpty()) {
            bitmap.recycle()
            return
        }

        val rightAligned = dateStamp.corner == StampCorner.BOTTOM_RIGHT || dateStamp.corner == StampCorner.TOP_RIGHT
        val bottomAligned = dateStamp.corner == StampCorner.BOTTOM_RIGHT || dateStamp.corner == StampCorner.BOTTOM_LEFT
        val rightX = bitmap.width - margin
        val leftX = margin

        // How the stamp's pixels combine with the photo underneath - see
        // StampBlendMode. null (NORMAL) leaves Paint's default alpha
        // compositing in place, same as before this setting existed.
        val xfermode = dateStamp.blendMode.porterDuffMode?.let { PorterDuffXfermode(it) }

        // Real LED date-backs bloom into the film grain around them rather
        // than sitting flat on top - three layered passes (a wide soft
        // halo, a tighter glow, then the crisp glyph) get much closer to
        // that than a single small shadow does.
        fun glowPaint(radius: Float, alpha: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = stampColor
            this.alpha = alpha
            maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
            this.xfermode = xfermode
        }

        when (dateStamp.typeface) {
            StampTypeface.DOT_MATRIX -> {
                val dotSpacing = shortSide * 0.0036f
                val dotRadius = dotSpacing * 0.34f
                val glyphHeight = 7 * dotSpacing
                val lineHeight = glyphHeight * 1.5f
                val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = stampColor; this.xfermode = xfermode }
                val haloPaint = glowPaint(shortSide * 0.02f, 130)
                val innerGlowPaint = glowPaint(shortSide * 0.008f, 200)

                var topY = if (bottomAligned) {
                    bitmap.height - margin - glyphHeight - (lines.size - 1) * lineHeight
                } else {
                    margin
                }
                for (line in lines) {
                    val width = DotMatrixFont.measureWidth(line, dotSpacing)
                    val startX = if (rightAligned) rightX - width else leftX
                    DotMatrixFont.draw(canvas, line, startX, topY, dotSpacing, dotRadius, dotPaint, haloPaint)
                    DotMatrixFont.draw(canvas, line, startX, topY, dotSpacing, dotRadius, dotPaint, innerGlowPaint)
                    topY += lineHeight
                }
            }
            StampTypeface.SEVEN_SEGMENT_ITALIC, StampTypeface.SEVEN_SEGMENT_UPRIGHT -> {
                val fontRes = if (dateStamp.typeface == StampTypeface.SEVEN_SEGMENT_ITALIC) {
                    R.font.dseg7_classic_bold_italic
                } else {
                    R.font.dseg7_classic_bold
                }
                val textSize = shortSide * 0.020f
                val lineHeight = textSize * 1.3f
                val typeface = ResourcesCompat.getFont(context, fontRes)

                val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.typeface = typeface
                    this.textSize = textSize
                    textAlign = Paint.Align.LEFT
                    color = stampColor
                    this.xfermode = xfermode
                }
                val haloPaint = Paint(basePaint).apply { maskFilter = BlurMaskFilter(shortSide * 0.010f, BlurMaskFilter.Blur.NORMAL); alpha = 140 }
                val innerGlowPaint = Paint(basePaint).apply { maskFilter = BlurMaskFilter(shortSide * 0.0035f, BlurMaskFilter.Blur.NORMAL) }

                // A real 7-segment date-back is fixed-pitch: every position
                // (digit or separator) occupies one equally-wide cell, so
                // draw character by character at a constant advance instead
                // of trusting drawText's own font-shaping-dependent layout
                // of the whole string.
                val charAdvance = basePaint.measureText("0")
                fun drawLine(line: String, startX: Float, y: Float, paint: Paint) {
                    var cx = startX
                    for (ch in line) {
                        canvas.drawText(ch.toString(), cx, y, paint)
                        cx += charAdvance
                    }
                }

                var y = if (bottomAligned) {
                    bitmap.height - margin - (lines.size - 1) * lineHeight
                } else {
                    margin + textSize
                }
                for (line in lines) {
                    val lineWidth = charAdvance * line.length
                    val startX = if (rightAligned) rightX - lineWidth else leftX
                    drawLine(line, startX, y, haloPaint)
                    drawLine(line, startX, y, innerGlowPaint)
                    drawLine(line, startX, y, basePaint)
                    y += lineHeight
                }
            }
        }

        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        bitmap.recycle()
    }

    private fun writeExif(context: Context, uri: Uri, recipeDescription: String, location: Location?) {
        val pfd = context.contentResolver.openFileDescriptor(uri, "rw") ?: return
        pfd.use {
            val exif = ExifInterface(it.fileDescriptor)
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "RetroCam")
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, "RetroCam recipe: $recipeDescription")
            if (location != null) {
                exif.setGpsInfo(location)
            }
            exif.saveAttributes()
        }
    }
}
