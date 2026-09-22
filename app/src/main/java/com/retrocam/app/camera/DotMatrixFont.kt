package com.retrocam.app.camera

import android.graphics.Canvas
import android.graphics.Paint

/**
 * A real dot-matrix digit renderer (round LEDs on a 5x7 grid), drawn
 * directly rather than loaded from a font file - most actual camera
 * date-backs used a dot-matrix LED array rather than 7-segment, and no
 * suitable open-license dot-matrix font was available to bundle. Patterns
 * are the classic 5x7 numeric LED glyphs.
 */
object DotMatrixFont {
    private val glyphs: Map<Char, List<String>> = mapOf(
        '0' to listOf("01110", "10001", "10011", "10101", "11001", "10001", "01110"),
        '1' to listOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
        '2' to listOf("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
        '3' to listOf("11111", "00010", "00100", "00010", "00001", "10001", "01110"),
        '4' to listOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
        '5' to listOf("11111", "10000", "11110", "00001", "00001", "10001", "01110"),
        '6' to listOf("00110", "01000", "10000", "11110", "10001", "10001", "01110"),
        '7' to listOf("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
        '8' to listOf("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
        '9' to listOf("01110", "10001", "10001", "01111", "00001", "00010", "01100"),
        '\'' to listOf("01000", "01000", "00000", "00000", "00000", "00000", "00000"),
        '.' to listOf("00000", "00000", "00000", "00000", "00000", "00000", "00100"),
        '/' to listOf("00001", "00001", "00010", "00100", "01000", "10000", "10000"),
        ' ' to listOf("00000", "00000", "00000", "00000", "00000", "00000", "00000"),
    )

    private const val COLS = 5
    private const val ROWS = 7

    /** Total rendered width for right/left alignment math, in the same
     * units as [spacing]. */
    fun measureWidth(text: String, spacing: Float): Float {
        if (text.isEmpty()) return 0f
        val charAdvance = COLS * spacing + spacing * 1.5f
        return text.length * charAdvance - spacing * 1.5f
    }

    /** Draws left-to-right starting at ([startX], [baselineY]) - baselineY
     * is the vertical center of the FIRST row (top of the glyph), not a
     * text baseline. [glowPaint] should already have a blur mask filter set. */
    fun draw(canvas: Canvas, text: String, startX: Float, topY: Float, spacing: Float, dotRadius: Float, paint: Paint, glowPaint: Paint) {
        var cursorX = startX
        val charAdvance = COLS * spacing + spacing * 1.5f
        for (ch in text) {
            val pattern = glyphs[ch]
            if (pattern == null) {
                cursorX += charAdvance
                continue
            }
            for (row in 0 until ROWS) {
                for (col in 0 until COLS) {
                    if (pattern[row][col] == '1') {
                        val cx = cursorX + col * spacing
                        val cy = topY + row * spacing
                        canvas.drawCircle(cx, cy, dotRadius * 2.2f, glowPaint)
                        canvas.drawCircle(cx, cy, dotRadius, paint)
                    }
                }
            }
            cursorX += charAdvance
        }
    }
}
