package com.scorchsoft.ssmarkdown.editor

import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Magnifier
import kotlin.math.roundToInt

/**
 * Every touch gesture on the code view, in precedence order:
 *
 * 1. **Pinch** changes the font size (never a bitmap scale), anchored at
 *    the fingers ([MarkdownCodeView.zoomBy]).
 * 2. **Handle drag** moves one end of the selection (or the caret), with
 *    the platform [Magnifier] above the finger and auto-scroll near the
 *    top and bottom edges ("drag-scroll").
 * 3. **Long-press** selects the word and, while the finger stays down,
 *    dragging extends the selection; lifting shows the text toolbar.
 * 4. **Double-tap** selects the word; **tap** places the caret and shows
 *    the insertion handle; **drag/fling** scroll.
 */
internal class TouchController(
    private val view: MarkdownCodeView,
) {
    private val gestures = GestureDetector(view.context, Gestures())
    private val scale = ScaleGestureDetector(view.context, Pinch())
    private val magnifier: Magnifier by lazy { Magnifier.Builder(view).build() }
    private var magnifierShown = false
    private val edge = EDGE_DP * view.resources.displayMetrics.density

    private var dragging: HandleKind? = null
    private var selectingByLongPress = false
    private var pinching = false
    private var tapped = false

    /** Vertical gap between the finger and the handle's point, so the finger never hides the text it moves. */
    private var grabOffsetY = 0f

    /** True once after a tap placed the caret; the view answers it with `performClick`. */
    fun takeTap(): Boolean = tapped.also { tapped = false }

    /** Routes [event]; returns true when it was consumed. */
    fun onTouchEvent(event: MotionEvent): Boolean {
        val down = event.actionMasked == MotionEvent.ACTION_DOWN
        if (down) view.stopFling()
        return when {
            down && startHandleDrag(event) -> {
                true
            }

            dragging != null || selectingByLongPress -> {
                continueDrag(event)
            }

            else -> {
                scale.onTouchEvent(event)
                pinching || scale.isInProgress || gestures.onTouchEvent(event)
            }
        }
    }

    private fun startHandleDrag(event: MotionEvent): Boolean {
        for (kind in HandleKind.entries) {
            val point = view.handlePoint(kind) ?: continue
            if (view.handles.hits(kind, point.x, point.y, event.x, event.y)) {
                dragging = kind
                grabOffsetY = event.y - point.y + view.rowHeight / 2f
                view.hideTextActions()
                return true
            }
        }
        return false
    }

    private fun continueDrag(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                autoScroll(event.y)
                val y = if (dragging != null) event.y - grabOffsetY else event.y
                moveSelectionEnd(view.offsetAtView(event.x, y))
                showMagnifier(event.x)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = null
                selectingByLongPress = false
                if (magnifierShown) magnifier.dismiss()
                magnifierShown = false
                view.showTextActions()
            }
        }
        return true
    }

    private fun moveSelectionEnd(offset: Int) {
        val session = view.session
        when (dragging) {
            HandleKind.INSERTION -> session.moveCaretTo(offset)

            // Dragging the start handle keeps the end fixed; handles may cross, the platform lets them too.
            HandleKind.START -> session.setSelection(session.selectionEnd, offset)

            HandleKind.END -> session.setSelection(session.selectionStart, offset)

            null -> session.extendSelection(offset)
        }
    }

    private fun showMagnifier(x: Float) {
        val point = view.caretPointInView() ?: return
        magnifier.show(x, point.y + view.rowHeight / 2f)
        magnifierShown = true
    }

    private fun autoScroll(y: Float) {
        val step = (view.rowHeight / 2f).roundToInt().coerceAtLeast(1)
        when {
            y < edge -> view.scrollByClamped(0, -step)
            y > view.height - edge -> view.scrollByClamped(0, step)
        }
    }

    private inner class Gestures : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float,
        ): Boolean {
            view.hideTextActions()
            view.scrollByClamped(distanceX.roundToInt(), distanceY.roundToInt())
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            view.startFling(-velocityX.toInt(), -velocityY.toInt())
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            view.onTap(e.x, e.y)
            tapped = true
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            view.selectWordAt(e.x, e.y)
            view.showTextActions()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (pinching) return
            view.selectWordAt(e.x, e.y)
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            selectingByLongPress = true
        }
    }

    private inner class Pinch : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            pinching = true
            view.hideTextActions()
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            view.zoomBy(detector.scaleFactor, detector.focusY)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            pinching = false
            view.endPinch()
        }
    }

    private companion object {
        /** Within this distance of the top or bottom edge, a drag scrolls the text. */
        const val EDGE_DP = 32f
    }
}
