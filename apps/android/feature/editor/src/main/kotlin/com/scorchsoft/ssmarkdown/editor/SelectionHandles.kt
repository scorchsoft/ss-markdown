package com.scorchsoft.ssmarkdown.editor

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Which handle a finger is on. */
enum class HandleKind {
    /** The single handle under a bare caret, shown after a tap. */
    INSERTION,

    /** The handle at the selection's start; its point is at its top-right. */
    START,

    /** The handle at the selection's end; its point is at its top-left. */
    END,
}

/**
 * The platform-like teardrop selection handles: where they sit, how they
 * are drawn, and which one a touch lands on. A handle hangs below the
 * caret position it belongs to: the insertion handle is a teardrop with
 * its point straight up, the start and end handles lean outwards with
 * their point at the selection's edge, as in `TextView`.
 *
 * Touch targets are [touchSize] square (48 dp, quality-bar) even though
 * the drawn drop is smaller.
 */
internal class SelectionHandles(
    /** The drawn handle's diameter, px. */
    private val size: Float,
    /** The touch target's side, px. */
    private val touchSize: Float,
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val hit = RectF()

    /** The handle's drawn bounds when its point is at ([x], [y]). */
    fun bounds(
        kind: HandleKind,
        x: Float,
        y: Float,
        out: RectF,
    ): RectF {
        val left =
            when (kind) {
                HandleKind.INSERTION -> x - size / 2
                HandleKind.START -> x - size
                HandleKind.END -> x
            }
        out.set(left, y, left + size, y + size)
        return out
    }

    /** True when ([touchX], [touchY]) is inside the touch target of a handle pointing at ([x], [y]). */
    fun hits(
        kind: HandleKind,
        x: Float,
        y: Float,
        touchX: Float,
        touchY: Float,
    ): Boolean {
        bounds(kind, x, y, hit)
        val growX = (touchSize - hit.width()).coerceAtLeast(0f) / 2
        val growY = (touchSize - hit.height()).coerceAtLeast(0f) / 2
        return touchX in hit.left - growX..hit.right + growX && touchY in hit.top - growY..hit.bottom + growY
    }

    /** Draws a [kind] handle in [color] with its point at ([x], [y]). */
    fun draw(
        canvas: Canvas,
        kind: HandleKind,
        x: Float,
        y: Float,
        color: Int,
    ) {
        paint.color = color
        val r = size / 2
        bounds(kind, x, y, hit)
        val cx = hit.centerX()
        val cy = y + r
        canvas.drawCircle(cx, cy, r, paint)
        // The square corner that turns the circle into a drop, pointing at the caret.
        when (kind) {
            HandleKind.INSERTION -> {
                path.reset()
                path.moveTo(cx, y)
                path.lineTo(cx + r * DIAGONAL, cy - r * DIAGONAL)
                path.lineTo(cx - r * DIAGONAL, cy - r * DIAGONAL)
                path.close()
                canvas.drawPath(path, paint)
            }

            HandleKind.START -> {
                canvas.drawRect(cx, y, x, cy, paint)
            }

            HandleKind.END -> {
                canvas.drawRect(x, y, cx, cy, paint)
            }
        }
    }

    private companion object {
        /** cos 45°: where the drop's sides meet the circle. */
        const val DIAGONAL = 0.7071f
    }
}
