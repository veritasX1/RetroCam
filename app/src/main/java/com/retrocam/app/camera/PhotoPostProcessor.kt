package com.retrocam.app.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuffXfermode
import android.location.Location
import android.net.Uri
import android.provider.MediaStore
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
        // than sitting flat on top, and are never quite pixel-sharp either
        // (real reference photos always show a touch of softness on the
        // glyph edges themselves, not just a glow around them) - four
        // layered passes (a wide soft halo, a tighter glow, then the glyph
        // itself with a small blur of its own) get much closer to that
        // than a single small shadow or a perfectly crisp glyph does. The
        // radii step up roughly geometrically and the alphas step down so
        // the three glow layers blend into one continuous falloff instead
        // of reading as separate rings - tuned subtler than earlier drafts
        // (lower peak alpha, wider spread) so it reads as "glow", not
        // "smear".
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
                val dotPaint = glowPaint(dotSpacing * 0.12f, 255)
                val haloPaint = glowPaint(shortSide * 0.024f, 90)
                val innerGlowPaint = glowPaint(shortSide * 0.009f, 170)

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
                    // Never quite pixel-sharp, matching real date-back
                    // reference photos - see the comment on glowPaint above.
                    maskFilter = BlurMaskFilter(shortSide * 0.0012f, BlurMaskFilter.Blur.NORMAL)
                }
                val haloPaint = Paint(basePaint).apply { maskFilter = BlurMaskFilter(shortSide * 0.018f, BlurMaskFilter.Blur.NORMAL); alpha = 90 }
                val innerGlowPaint = Paint(basePaint).apply { maskFilter = BlurMaskFilter(shortSide * 0.006f, BlurMaskFilter.Blur.NORMAL); alpha = 170 }

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

        // Compress to memory FIRST, then write - opening the real output
        // stream in "wt" mode truncates the file immediately, before a
        // single byte of the new JPEG exists. Writing bitmap.compress()
        // straight into that stream means any failure partway through
        // (OOM, IO error, the process getting killed) leaves the file
        // empty or truncated - the original photo is gone, even though
        // nothing about the capture itself failed. Encoding to a byte
        // buffer first means the real file is only ever touched once a
        // complete, valid replacement actually exists.
        val encoded = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bitmap.recycle()
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(encoded.toByteArray())
        }
        // The file's actual bytes just changed size (stamp/EXIF added) via
        // a direct stream write, which MediaStore doesn't always notice on
        // its own - other apps reading this URI's SIZE/DATE_MODIFIED
        // columns (a gallery, Immich's own MediaStore sync, etc.) could
        // otherwise see stale metadata from the moment ImageCapture first
        // inserted the row, before this post-processing pass touched it.
        runCatching {
            context.contentResolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.SIZE, encoded.size())
                    put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                },
                null,
                null,
            )
        }
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
