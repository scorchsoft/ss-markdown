package com.scorchsoft.ssmarkdown.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.text.InputType
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextDocument
import kotlin.math.roundToInt

/**
 * The Code pane: a virtualised, monospace, No-wrap text view over a
 * [TextDocument] (docs/robustness.md, "Editor view").
 *
 * **This is the editor-engine spike** (ADR 0001). It proves the shape the
 * real view will grow from, and is benchmarked against `EditText` and
 * Sora Editor by `:tools:editor-bench`:
 *
 * - Draws only the visible lines; lays out at most a few margin lines per
 *   frame ahead of the scroll direction ([Viewport.renderLines]).
 * - Fixed line height, so scroll height and line lookup are arithmetic.
 * - Long lines are windowed: only ~2,000 chars around the viewport are
 *   measured and drawn ([LineWindow]); the rest is estimated.
 * - Per-line layouts are cached by line identity + edit version
 *   ([LineLayoutCache]).
 * - Vertical and horizontal drag and fling through [OverScroller].
 * - A caret, tap-to-place, and a keyboard connection ([CodeInputConnection])
 *   good enough to type with Gboard, including composition.
 *
 * Not yet (later iterations): selection handles, the text-action toolbar,
 * highlighting, Wrap mode, `RenderNode` per line, accessibility nodes,
 * scrollbars and fast scroll.
 *
 * Everything that is maths rather than drawing lives in pure classes
 * ([Viewport], [LineLayout], [EditingSession]) with their own tests.
 */
@Suppress("TooManyFunctions") // A View's contract is wide: draw, touch, keys, IME, scroll. Each piece is small.
class MarkdownCodeView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs),
        EditingSession.Listener {
        /** The editing state; the benchmark and the future view model drive it directly. */
        val session: EditingSession = EditingSession(TextDocument(PieceTree.empty()))

        private val textPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.MONOSPACE
                textSize =
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, DEFAULT_TEXT_SP, resources.displayMetrics)
            }
        private val gutterPaint = Paint(textPaint).apply { textAlign = Paint.Align.RIGHT }
        private val fillPaint = Paint()
        private val measurer = LineMeasurer(textPaint)
        private val cache = LineLayoutCache()
        private val scroller = OverScroller(context)
        private val gestures = GestureDetector(context, GestureListener())
        private val inputMethods: InputMethodManager? = context.getSystemService(InputMethodManager::class.java)
        private val density = resources.displayMetrics.density
        private val lineNumberChars = CharArray(MAX_LINE_NUMBER_DIGITS)

        private var palette = EditorPalette.forContext(context)
        private var viewport = Viewport(1)
        private var baseline = 0f
        private var gutterWidth = 0
        private var direction = ScrollDirection.IDLE
        private var widestVisibleLine = 0f
        private var caretVisible = true
        private var tapped = false

        private val blink =
            object : Runnable {
                override fun run() {
                    caretVisible = !caretVisible
                    invalidate()
                    postDelayed(this, BLINK_MILLIS)
                }
            }

        init {
            isFocusable = true
            isFocusableInTouchMode = true
            session.listener = this
            onMetricsChanged()
        }

        /** The document shown. Setting it resets caret and scroll and restarts the keyboard. */
        var document: TextDocument
            get() = session.document
            set(value) {
                session.reset(value)
                cache.clear()
                scroller.forceFinished(true)
                scrollTo(0, 0)
                onMetricsChanged()
                inputMethods?.restartInput(this)
                invalidate()
            }

        /** Text size in px. Changing it re-measures everything. */
        var textSizePx: Float
            get() = textPaint.textSize
            set(value) {
                textPaint.textSize = value
                gutterPaint.textSize = value
                measurer.onPaintChanged()
                cache.clear()
                onMetricsChanged()
                invalidate()
            }

        /** Fixed height of every line, in px. */
        val lineHeight: Int get() = viewport.lineHeight

        /** Width of one monospace cell, in px. */
        val charWidth: Float get() = measurer.charWidth

        /** Left edge of the text column, in view px (gutter plus its gap). */
        val textLeft: Int get() = gutterWidth + gap()

        /** Places the caret at [offset], ends any composition and scrolls it into view. */
        fun setCaret(offset: Int) {
            session.moveCaretTo(offset)
        }

        /** Starts a vertical fling, as a finger would; the benchmark uses this for scripted scrolling. */
        fun fling(velocityY: Int) {
            scroller.fling(scrollX, scrollY, 0, velocityY, 0, maxScrollX(), 0, maxScrollY())
            postInvalidateOnAnimation()
        }

        // ------------------------------------------------------------ maths

        /** The document offset under view point ([x], [y]), as a tap would place the caret. */
        fun offsetAt(
            x: Float,
            y: Float,
        ): Int {
            val text = session.document.text
            val line = viewport.lineAt(scrollY.toLong() + y.toLong(), text.lineCount)
            val column = layoutFor(text, line).columnAt(x + scrollX - textLeft)
            return text.lineStartOffset(line) + column
        }

        /** The caret's x in view px, for [offset]; the inverse of [offsetAt] along a line. */
        fun xForOffset(offset: Int): Float {
            val text = session.document.text
            val position = text.positionAt(offset)
            return textLeft - scrollX + layoutFor(text, position.line).xOf(position.column)
        }

        /** Top of the line holding [offset], in view px. */
        fun yForOffset(offset: Int): Float {
            val line =
                session.document.text
                    .positionAt(offset)
                    .line
            return (viewport.lineTop(line) - scrollY).toFloat()
        }

        /** Lines currently laid out; lets tests prove drawing stays proportional to the screen. */
        internal fun cachedLineCount(): Int = cache.size

        private fun onMetricsChanged() {
            val metrics = textPaint.fontMetricsInt
            val glyphHeight = metrics.descent - metrics.ascent
            val lineHeight = (glyphHeight * LINE_SPACING).roundToInt().coerceAtLeast(1)
            viewport = Viewport(lineHeight)
            baseline = (lineHeight - glyphHeight) / 2f - metrics.ascent
            val digits =
                session.document.text.lineCount
                    .toString()
                    .length
                    .coerceAtLeast(MIN_GUTTER_DIGITS)
            gutterWidth = (digits * measurer.charWidth).roundToInt() + 2 * gap()
        }

        private fun gap(): Int = (GAP_DP * density).roundToInt()

        private fun textWidth(): Int = (width - textLeft).coerceAtLeast(0)

        private fun maxScrollY(): Int = viewport.maxScrollY(session.document.text.lineCount, height)

        private fun maxScrollX(): Int = (widestVisibleLine + gap() - textWidth()).roundToInt().coerceAtLeast(0)

        /** The column at the middle of the viewport, which picks every long line's window. */
        private fun centreColumn(): Int = ((scrollX + textWidth() / 2) / measurer.charWidth.coerceAtLeast(1f)).toInt()

        private fun layoutFor(
            text: PieceTree,
            line: Int,
        ): LineLayout {
            val windowKey = LineWindow.startFor(text.lineLength(line), centreColumn())
            val version = session.document.version
            return cache.get(line, version, windowKey)
                ?: measurer.measure(text, line, windowKey).also { cache.put(line, version, windowKey, it) }
        }

        private fun cachedLayout(
            text: PieceTree,
            line: Int,
        ): LineLayout? =
            cache.get(line, session.document.version, LineWindow.startFor(text.lineLength(line), centreColumn()))

        // ---------------------------------------------------------- drawing

        override fun onDraw(canvas: Canvas) {
            val text = session.document.text
            val visible = viewport.visibleLines(scrollY, height, text.lineCount)
            prefetchMargin(text, visible)
            // View.draw has translated by the scroll; work in view coordinates instead.
            canvas.save()
            canvas.translate(scrollX.toFloat(), scrollY.toFloat())
            canvas.drawColor(palette.background)
            canvas.save()
            canvas.clipRect(gutterWidth, 0, width, height)
            widestVisibleLine = 0f
            for (line in visible) drawLine(canvas, text, line)
            drawComposingUnderline(canvas, text, visible)
            drawCaret(canvas, text, visible)
            canvas.restore()
            drawGutter(canvas, visible)
            canvas.restore()
        }

        private fun drawLine(
            canvas: Canvas,
            text: PieceTree,
            line: Int,
        ) {
            val layout = layoutFor(text, line)
            widestVisibleLine = maxOf(widestVisibleLine, layout.width)
            val y = (viewport.lineTop(line) - scrollY) + baseline
            // Only the columns on screen are drawn, even inside a 2,000-char window.
            val from = (layout.columnAt(scrollX.toFloat()) - 1).coerceIn(layout.windowStart, layout.windowEnd)
            val to = (layout.columnAt((scrollX + textWidth()).toFloat()) + 1).coerceIn(from, layout.windowEnd)
            textPaint.color = palette.text
            var runStart = from
            for (column in from..to) {
                val atTab = column < to && layout.chars[column - layout.windowStart] == '\t'
                if (column == to || atTab) {
                    drawRun(canvas, layout, runStart, column, y)
                    runStart = column + 1
                }
            }
        }

        private fun drawRun(
            canvas: Canvas,
            layout: LineLayout,
            from: Int,
            to: Int,
            y: Float,
        ) {
            val x = textLeft - scrollX + layout.xOf(from)
            var start = from - layout.windowStart
            val end = to - layout.windowStart
            // A run cut at the screen edge may start on the second half of a pair.
            if (start in 1 until end && layout.chars[start].isLowSurrogate()) start--
            if (end <= start) return
            canvas.drawTextRun(layout.chars, start, end - start, start, end - start, x, y, false, textPaint)
        }

        private fun drawCaret(
            canvas: Canvas,
            text: PieceTree,
            visible: IntRange,
        ) {
            if (!caretVisible || !hasFocus()) return
            val position = text.positionAt(session.caret)
            if (position.line !in visible) return
            val x = xForOffset(session.caret)
            val top = (viewport.lineTop(position.line) - scrollY).toFloat()
            fillPaint.color = palette.caret
            canvas.drawRect(x, top, x + CARET_WIDTH_DP * density, top + viewport.lineHeight, fillPaint)
        }

        private fun drawComposingUnderline(
            canvas: Canvas,
            text: PieceTree,
            visible: IntRange,
        ) {
            if (!session.isComposing) return
            val first = text.positionAt(session.composingStart)
            val last = text.positionAt(session.composingEnd)
            fillPaint.color = palette.composingUnderline
            val thickness = UNDERLINE_DP * density
            for (line in maxOf(first.line, visible.first)..minOf(last.line, visible.last)) {
                val layout = layoutFor(text, line)
                val startColumn = if (line == first.line) first.column else 0
                val endColumn = if (line == last.line) last.column else layout.lineLength
                val left = textLeft - scrollX
                val bottom = (viewport.lineTop(line) + viewport.lineHeight - scrollY).toFloat()
                canvas.drawRect(
                    left + layout.xOf(startColumn),
                    bottom - thickness,
                    left + layout.xOf(endColumn),
                    bottom,
                    fillPaint,
                )
            }
        }

        private fun drawGutter(
            canvas: Canvas,
            visible: IntRange,
        ) {
            fillPaint.color = palette.gutterBackground
            canvas.drawRect(0f, 0f, gutterWidth.toFloat(), height.toFloat(), fillPaint)
            gutterPaint.color = palette.gutterText
            val right = (gutterWidth - gap()).toFloat()
            for (line in visible) {
                val start = writeLineNumber(line + 1)
                val y = (viewport.lineTop(line) - scrollY) + baseline
                canvas.drawText(lineNumberChars, start, lineNumberChars.size - start, right, y, gutterPaint)
            }
        }

        /** Writes [number] right-aligned into [lineNumberChars] (no String per frame); returns its first index. */
        private fun writeLineNumber(number: Int): Int {
            var n = number
            var index = lineNumberChars.size
            do {
                lineNumberChars[--index] = '0' + n % DECIMAL
                n /= DECIMAL
            } while (n > 0)
            return index
        }

        /**
         * Lays out up to [PREFETCH_LINES_PER_FRAME] lines from the margin each
         * frame, nearest first and on the side the scroll is heading, so a fling
         * finds them ready. Cached lines cost a map lookup.
         */
        private fun prefetchMargin(
            text: PieceTree,
            visible: IntRange,
        ) {
            if (visible.isEmpty()) return
            val margin = viewport.renderLines(scrollY, height, text.lineCount, direction)
            val below = (visible.last + 1)..margin.last
            val above = (visible.first - 1) downTo margin.first
            var budget = PREFETCH_LINES_PER_FRAME
            if (direction == ScrollDirection.TOWARDS_START) {
                budget = prefetch(text, above, budget)
                prefetch(text, below, budget)
            } else {
                budget = prefetch(text, below, budget)
                prefetch(text, above, budget)
            }
        }

        /** Lays out [lines] in order until [budget] runs out; returns what is left. */
        private fun prefetch(
            text: PieceTree,
            lines: IntProgression,
            budget: Int,
        ): Int {
            var left = budget
            for (line in lines) {
                if (left == 0) break
                if (cachedLayout(text, line) == null) {
                    layoutFor(text, line)
                    left--
                }
            }
            return left
        }

        // ----------------------------------------------------------- scrolling

        override fun computeScroll() {
            if (scroller.computeScrollOffset()) {
                scrollTo(scroller.currX.coerceIn(0, maxScrollX()), scroller.currY.coerceIn(0, maxScrollY()))
                postInvalidateOnAnimation()
            }
        }

        override fun onScrollChanged(
            l: Int,
            t: Int,
            oldl: Int,
            oldt: Int,
        ) {
            super.onScrollChanged(l, t, oldl, oldt)
            direction =
                when {
                    t > oldt -> ScrollDirection.TOWARDS_END
                    t < oldt -> ScrollDirection.TOWARDS_START
                    else -> direction
                }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) scroller.forceFinished(true)
            tapped = false
            val consumed = gestures.onTouchEvent(event)
            // The tap already placed the caret; the click is what accessibility services can repeat.
            if (tapped) performClick()
            return consumed || super.onTouchEvent(event)
        }

        /** A click (a tap, or an accessibility action) focuses the editor and raises the keyboard. */
        override fun performClick(): Boolean {
            super.performClick()
            requestFocus()
            inputMethods?.showSoftInput(this, 0)
            return true
        }

        private fun revealCaret() {
            val text = session.document.text
            val position = text.positionAt(session.caret)
            val y = viewport.scrollToReveal(position.line, scrollY, height)
            val caretX = layoutFor(text, position.line).xOf(position.column)
            val x =
                when {
                    caretX < scrollX -> caretX.toInt()
                    caretX > scrollX + textWidth() - gap() -> (caretX - textWidth() + gap()).roundToInt()
                    else -> scrollX
                }
            if (x != scrollX || y != scrollY) scrollTo(x.coerceAtLeast(0), y)
        }

        private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                val x = (scrollX + distanceX.roundToInt()).coerceIn(0, maxScrollX())
                val y = (scrollY + distanceY.roundToInt()).coerceIn(0, maxScrollY())
                scrollTo(x, y)
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                scroller.fling(
                    scrollX,
                    scrollY,
                    -velocityX.toInt(),
                    -velocityY.toInt(),
                    0,
                    maxScrollX(),
                    0,
                    maxScrollY(),
                )
                postInvalidateOnAnimation()
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                session.moveCaretTo(offsetAt(e.x, e.y))
                tapped = true
                return true
            }
        }

        // --------------------------------------------------- keyboard and keys

        override fun onCheckIsTextEditor(): Boolean = true

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            outAttrs.inputType =
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            outAttrs.imeOptions =
                EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
            outAttrs.initialSelStart = session.selectionStart
            outAttrs.initialSelEnd = session.selectionEnd
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val window = session.textWindow(SURROUNDING_TEXT_CHARS)
                outAttrs.setInitialSurroundingSubText(window.text, window.start)
            }
            return CodeInputConnection(this, session, ::onKey)
        }

        override fun onKeyDown(
            keyCode: Int,
            event: KeyEvent,
        ): Boolean = onKey(event) || super.onKeyDown(keyCode, event)

        /** Handles a key from a hardware keyboard or from the soft keyboard's `sendKeyEvent`. */
        private fun onKey(event: KeyEvent): Boolean {
            val handled = isEditingKey(event)
            if (handled && event.action == KeyEvent.ACTION_DOWN) applyKey(event)
            return handled
        }

        private fun applyKey(event: KeyEvent) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> session.pressBackspace()
                KeyEvent.KEYCODE_FORWARD_DEL -> session.pressForwardDelete()
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> session.pressEnter()
                KeyEvent.KEYCODE_DPAD_LEFT -> session.moveCaretByGrapheme(forward = false)
                KeyEvent.KEYCODE_DPAD_RIGHT -> session.moveCaretByGrapheme(forward = true)
                KeyEvent.KEYCODE_DPAD_UP -> session.moveCaretByLine(-1)
                KeyEvent.KEYCODE_DPAD_DOWN -> session.moveCaretByLine(1)
                else -> session.commitText(String(Character.toChars(event.unicodeChar)), 1)
            }
        }

        /** A key this view acts on: an editing or arrow key, or a printable char without Ctrl. */
        private fun isEditingKey(event: KeyEvent): Boolean =
            event.keyCode in EDITING_KEYS || (event.unicodeChar != 0 && !event.isCtrlPressed)

        override fun onTextChanged(change: LineChange) {
            cache.onEdit(change.firstLine, change.removedBreaks, change.addedBreaks, session.document.version)
            if (change.removedBreaks != change.addedBreaks) onMetricsChanged()
            invalidate()
        }

        override fun onSelectionSettled() {
            inputMethods?.updateSelection(
                this,
                session.selectionStart,
                session.selectionEnd,
                session.composingStart,
                session.composingEnd,
            )
            revealCaret()
            restartBlink()
            invalidate()
        }

        // ------------------------------------------------------------- focus

        override fun onFocusChanged(
            gainFocus: Boolean,
            direction: Int,
            previouslyFocusedRect: Rect?,
        ) {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
            if (gainFocus) restartBlink() else removeCallbacks(blink)
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(blink)
            super.onDetachedFromWindow()
        }

        override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
            super.onConfigurationChanged(newConfig)
            palette = EditorPalette.forContext(context)
            invalidate()
        }

        private fun restartBlink() {
            removeCallbacks(blink)
            caretVisible = true
            if (hasFocus()) postDelayed(blink, BLINK_MILLIS)
        }

        private companion object {
            const val DEFAULT_TEXT_SP = 14f
            const val LINE_SPACING = 1.3f
            const val GAP_DP = 8f
            const val CARET_WIDTH_DP = 2f
            const val UNDERLINE_DP = 1.5f
            const val MIN_GUTTER_DIGITS = 2
            const val MAX_LINE_NUMBER_DIGITS = 10
            const val DECIMAL = 10
            const val BLINK_MILLIS = 500L
            const val PREFETCH_LINES_PER_FRAME = 4

            /** What `setInitialSurroundingSubText` keeps anyway; building more is waste. */
            const val SURROUNDING_TEXT_CHARS = 4_096

            val EDITING_KEYS =
                setOf(
                    KeyEvent.KEYCODE_DEL,
                    KeyEvent.KEYCODE_FORWARD_DEL,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                )
        }
    }
