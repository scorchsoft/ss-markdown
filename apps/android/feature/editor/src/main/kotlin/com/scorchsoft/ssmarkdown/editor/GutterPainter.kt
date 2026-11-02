package com.scorchsoft.ssmarkdown.editor

import android.graphics.Canvas
import android.graphics.Paint

/** Draws the line-number gutter, right-aligned, without allocating a String per number per frame. */
internal class GutterPainter(
    basePaint: Paint,
) {
    private val paint = Paint(basePaint).apply { textAlign = Paint.Align.RIGHT }
    private val fill = Paint()
    private val digits = CharArray(MAX_DIGITS)

    /** Call after the base paint's size or typeface changed. */
    fun onPaintChanged(basePaint: Paint) {
        paint.set(basePaint)
        paint.textAlign = Paint.Align.RIGHT
    }

    fun draw(
        canvas: Canvas,
        visible: IntRange,
        frame: FrameMetrics,
        theme: EditorTheme,
        heights: HeightMap,
    ) {
        fill.color = theme.gutterBackground
        canvas.drawRect(0f, 0f, frame.gutterWidth.toFloat(), frame.height.toFloat(), fill)
        paint.color = theme.gutterText
        val right = (frame.gutterWidth - frame.gap).toFloat()
        for (line in visible) {
            val start = write(line + 1)
            val y = (heights.lineTop(line) - frame.scrollY) + frame.baseline
            canvas.drawText(digits, start, digits.size - start, right, y, paint)
        }
    }

    /** Writes [number] right-aligned into [digits]; returns its first index. */
    private fun write(number: Int): Int {
        var n = number
        var index = digits.size
        do {
            digits[--index] = '0' + n % DECIMAL
            n /= DECIMAL
        } while (n > 0)
        return index
    }

    private companion object {
        const val MAX_DIGITS = 10
        const val DECIMAL = 10
    }
}
