package com.scorchsoft.ssmarkdown.editor

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextDocument
import kotlin.math.roundToInt

/**
 * The Code pane: a virtualised, syntax-coloured, monospace text view over a
 * [TextDocument] (docs/robustness.md, "Editor view"). It owns no text: the
 * document belongs to the host's store, and this view is bound to it with
 * [setDocument].
 *
 * - Draws only the visible rows; lays out lines lazily through a cache
 *   keyed by line + edit version ([EditorGeometry]). Long lines are
 *   windowed (No wrap) or measured up to 10,000 chars (Wrap).
 * - **Wrap mode** ([wrap]) keeps a [HeightMap] of measured lines and
 *   estimated gaps, and anchors the scroll so measuring never moves what
 *   is on screen; switching modes keeps the top line.
 * - Colours come from the incremental Markdown tokenizer within an 8 ms
 *   budget per frame, continued in idle slices ([HighlightScheduler]).
 * - Selection: long-press and double-tap select a word, teardrop handles
 *   with the magnifier, the floating Cut/Copy/Paste/Select all toolbar,
 *   Shift+arrows and the Ctrl shortcuts ([KeyCommands]).
 * - Accessibility: an editable node with a 100K window and a virtual node
 *   per visible line ([EditorAccessibility]).
 * - Pinch changes the font size, never a bitmap scale ([zoomPercent]).
 *
 * Host it from Compose with [com.scorchsoft.ssmarkdown.editor.compose.MarkdownEditor].
 */
@Suppress("TooManyFunctions") // A View's contract is wide: draw, touch, keys, IME, scroll, a11y. Each piece is small.
class MarkdownCodeView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs),
        EditingSession.Listener {
        /** The editing state: selection, composition and every edit. Hosts may read it; edit through [applyEdit]. */
        val session: EditingSession = EditingSession(TextDocument(PieceTree.empty()))

        /** Receives selection, edit, anchor and zoom changes. */
        var listener: EditorListener? = null

        private val textPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.MONOSPACE
                textSize = spToPx(ZoomModel.BASE_SP)
            }
        private val measurer = LineMeasurer(textPaint)
        private val geometry = EditorGeometry(measurer, { session.document.text }, { session.document.version })
        private val highlight = HighlightScheduler(onRepaint = ::invalidate)
        private val renderer = EditorRenderer(geometry, session, highlight, textPaint)
        private val commands = CommandRunner(this, geometry)
        private val selectionUi = SelectionUi(this, commands)
        private val touch = TouchController(this)
        private val keys = KeyRouter(session, commands) { listener }
        private val accessibility = EditorAccessibility(this)
        private val zoom = ZoomModel()
        private val scroller = OverScroller(context)
        private val inputMethods: InputMethodManager? = context.getSystemService(InputMethodManager::class.java)
        private val a11yManager: AccessibilityManager? = context.getSystemService(AccessibilityManager::class.java)
        private val frame = FrameMetrics(resources.displayMetrics.density)
        private val words = WordFinder()

        private var direction = ScrollDirection.IDLE
        private var lastAnchorLine = -1
        private var lastCaretLine = 0
        private var widestVisibleRow = 0f

        private val blink =
            object : Runnable {
                override fun run() {
                    frame.caretVisible = !frame.caretVisible
                    invalidate()
                    postDelayed(this, BLINK_MILLIS)
                }
            }

        private val preDraw =
            ViewTreeObserver.OnPreDrawListener {
                prepareFrame()
                true
            }

        init {
            isFocusable = true
            isFocusableInTouchMode = true
            session.listener = this
            applyTheme(EditorTheme.forContext(context))
            onMetricsChanged()
        }

        /**
         * The colours. Until a host sets one, the view follows the context's
         * light or dark mode, including across a `uiMode` change.
         */
        var theme: EditorTheme
            get() = renderer.styles.theme
            set(value) {
                themeFollowsSystem = false
                applyTheme(value)
            }

        private var themeFollowsSystem = true

        private fun applyTheme(value: EditorTheme) {
            renderer.styles = SyntaxStyles(value)
            invalidate()
        }

        /** The document shown. Assigning it is [setDocument]. */
        @set:JvmName("bindDocument")
        var document: TextDocument
            get() = session.document
            set(value) = setDocument(value)

        /**
         * Binds the view to [document]: caret and scroll go to the start, the
         * caches start over, and the keyboard is restarted, which is the one
         * time it is (robustness.md: `restartInput` only on outside replacement).
         */
        fun setDocument(document: TextDocument) {
            session.reset(document)
            highlight.reset(document.text)
            scroller.forceFinished(true)
            scrollTo(0, 0)
            onMetricsChanged()
            relayout(line = 0, viewY = 0f)
            inputMethods?.restartInput(this)
            invalidate()
        }

        /** Wrap mode: rows break at the text column's width. Switching keeps the top line in place. */
        var wrap: Boolean = false
            set(value) {
                if (field == value) return
                field = value
                relayout(anchorLine, 0f)
            }

        /** Text size in px. Prefer [zoomPercent]; this exists for benchmarks and tests. */
        var textSizePx: Float
            get() = textPaint.textSize
            set(value) {
                textPaint.textSize = value
                onFontChanged(anchorLine, 0f)
            }

        /** Zoom as a percentage of Code's 100 % (12.5 sp); the host restores it per file. */
        var zoomPercent: Int
            get() = zoom.percent
            set(value) {
                if (zoom.set(value)) applyZoom(viewY = 0f)
            }

        /** The source line at the top of the pane: the position anchor shared with Preview. */
        val anchorLine: Int get() = geometry.heights.lineAt(scrollY.toLong())

        /** Height of one text row, in px. */
        val lineHeight: Int get() = geometry.heights.rowHeight

        /** Height of one text row, in px (the same as [lineHeight], named for wrapped rows). */
        val rowHeight: Int get() = geometry.heights.rowHeight

        /** Width of one monospace cell, in px. */
        val charWidth: Float get() = measurer.charWidth

        /** Left edge of the text column, in view px (gutter plus its gap). */
        val textLeft: Int get() = frame.textLeft

        /** Scrolls so [line] is at the top of the pane. */
        fun scrollToLine(line: Int) {
            scroller.forceFinished(true)
            placeLine(line, viewY = 0f)
        }

        /**
         * Commits the keyboard's in-progress word. Hosts call this before any
         * layout change, toolbar command or save (robustness.md §5a.4).
         */
        fun requestFlushComposing() {
            session.finishComposingText()
        }

        /**
         * An edit from outside the keyboard (toolbar, Preview round-trip):
         * flushes composition, replaces `[start, end)` with [text] as one undo
         * step, then selects `[selectStart, selectEnd)` (default: a caret after it).
         */
        fun applyEdit(
            start: Int,
            end: Int,
            text: CharSequence,
            selectStart: Int = EditingSession.NONE,
            selectEnd: Int = EditingSession.NONE,
        ) {
            session.document.beginEditGroup()
            try {
                session.applyEdit(start, end, text, selectStart, selectEnd)
            } finally {
                session.document.endEditGroup()
            }
        }

        /** Places the caret at [offset], ends any composition and scrolls it into view. */
        fun setCaret(offset: Int) {
            session.moveCaretTo(offset)
        }

        /** Starts a vertical fling, as a finger would; the benchmark uses this for scripted scrolling. */
        fun fling(velocityY: Int) {
            startFling(0, velocityY)
        }

        /** The document offset under view point ([x], [y]), as a tap would place the caret. */
        fun offsetAt(
            x: Float,
            y: Float,
        ): Int = offsetAtView(x, y)

        /** The caret's x in view px, for [offset]; the inverse of [offsetAt] along a row. */
        fun xForOffset(offset: Int): Float = pointInView(offset).x

        /** Top of the row holding [offset], in view px. */
        fun yForOffset(offset: Int): Float = pointInView(offset).y

        // ------------------------------------------------------- internal API

        internal val handles: SelectionHandles get() = selectionUi.handles

        /** Lines currently laid out; lets tests prove drawing stays proportional to the screen. */
        internal fun cachedLineCount(): Int = geometry.cachedLineCount

        internal fun offsetAtView(
            x: Float,
            y: Float,
        ): Int = geometry.offsetAt(x - textLeft + horizontalScroll(), scrollY.toLong() + y.toLong())

        /** Where [offset] is drawn: x at the caret, y at the top of its row, in view px. */
        internal fun pointInView(offset: Int): PointF {
            val point = geometry.locate(offset.coerceIn(0, session.document.text.length))
            return PointF(textLeft - horizontalScroll() + point.x, (point.top - scrollY).toFloat())
        }

        internal fun caretPointInView(): PointF? = pointInView(session.caret).takeIf { it.y in 0f..height.toFloat() }

        internal fun handlePoint(kind: HandleKind): PointF? = selectionUi.handlePoint(kind)

        internal fun visibleLineRange(): IntRange = geometry.heights.visibleLines(scrollY, height)

        /** Fills [out] with [line]'s bounds in view px; returns true if any of it is on screen. */
        internal fun lineBoundsInView(
            line: Int,
            out: Rect,
        ): Boolean {
            val top = (geometry.heights.lineTop(line) - scrollY).toInt()
            out.set(textLeft, top, width, top + geometry.heights.heightOf(line))
            return out.bottom > 0 && out.top < height
        }

        internal fun runCommand(command: EditorCommand): Boolean = commands.run(command)

        internal fun showTextActions() = selectionUi.showToolbar()

        internal fun hideTextActions() = selectionUi.hideToolbar()

        internal fun onTap(
            x: Float,
            y: Float,
        ) {
            selectionUi.hideToolbar()
            session.moveCaretTo(offsetAtView(x, y))
            selectionUi.setInsertionHandle(true)
        }

        internal fun selectWordAt(
            x: Float,
            y: Float,
        ) {
            requestFocus()
            session.selectWord(offsetAtView(x, y), words)
        }

        internal fun scrollByClamped(
            dx: Int,
            dy: Int,
        ) {
            val x = if (wrap) 0 else (scrollX + dx).coerceIn(0, maxScrollX())
            scrollTo(x, (scrollY + dy).coerceIn(0, maxScrollY()))
        }

        internal fun startFling(
            velocityX: Int,
            velocityY: Int,
        ) {
            val maxX = if (wrap) 0 else maxScrollX()
            scroller.fling(scrollX, scrollY, velocityX, velocityY, 0, maxX, 0, maxScrollY())
            postInvalidateOnAnimation()
        }

        internal fun stopFling() {
            scroller.forceFinished(true)
        }

        /** One pinch step: scales the font about view y [focusY], keeping the line under the fingers still. */
        internal fun zoomBy(
            factor: Float,
            focusY: Float,
        ) {
            val before = zoom.percent
            if (!zoom.scaleBy(factor)) return
            if (ZoomModel.crossesDefault(
                    before,
                    zoom.percent,
                )
            ) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            applyZoom(focusY)
            listener?.onZoomChanged(zoom.percent, pinching = true)
        }

        internal fun endPinch() {
            listener?.onZoomChanged(zoom.percent, pinching = false)
        }

        // ------------------------------------------------------------ layout

        private fun applyZoom(viewY: Float) {
            val line = geometry.heights.lineAt(scrollY.toLong() + viewY.toLong())
            textPaint.textSize = spToPx(zoom.textSizeSp)
            onFontChanged(line, viewY)
        }

        private fun onFontChanged(
            line: Int,
            viewY: Float,
        ) {
            measurer.onPaintChanged()
            renderer.onPaintChanged(textPaint)
            onMetricsChanged()
            relayout(line, viewY)
        }

        /** Recomputes row height, baseline and gutter; returns true if the text column's width changed. */
        private fun onMetricsChanged(): Boolean {
            frame.width = width
            geometry.heights.rowHeight = frame.update(textPaint, measurer.charWidth, session.document.text.lineCount)
            return frame.textColumnWidth() != geometry.textWidth
        }

        /** Throws away layouts and heights, then scrolls so [line] sits at view y [viewY] again. */
        private fun relayout(
            line: Int,
            viewY: Float,
        ) {
            frame.width = width
            geometry.configure(geometry.heights.rowHeight, wrap, frame.textColumnWidth())
            placeLine(line, viewY)
        }

        /** Scrolls so the top of [line] is at view y [viewY], from the heights as they are now. */
        private fun placeLine(
            line: Int,
            viewY: Float,
        ) {
            val target = line.coerceIn(0, session.document.text.lineCount - 1)
            geometry.anchorLine = target
            geometry.layoutFor(target)
            val y = (geometry.heights.lineTop(target) - viewY.toLong()).coerceIn(0, maxScrollY().toLong())
            scrollTo(horizontalScroll(), y.toInt())
            // The position was computed from the current tops, so there is nothing left to correct.
            geometry.takeScrollShift()
            invalidate()
        }

        private fun horizontalScroll(): Int = if (wrap) 0 else scrollX

        private fun maxScrollY(): Int = geometry.heights.maxScrollY(height)

        private fun maxScrollX(): Int =
            (widestVisibleRow + frame.gap - frame.textColumnWidth()).roundToInt().coerceAtLeast(0)

        override fun onSizeChanged(
            w: Int,
            h: Int,
            oldw: Int,
            oldh: Int,
        ) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w != oldw) relayout(anchorLine, 0f)
        }

        // ----------------------------------------------------------- drawing

        /**
         * Settles the frame before drawing: lays out the margin and the
         * visible rows, applies the scroll anchoring that measuring caused,
         * and tokenises the visible lines within the main-thread budget.
         */
        private fun prepareFrame(): IntRange {
            geometry.scrollX = horizontalScroll()
            geometry.anchorLine = geometry.heights.lineAt(scrollY.toLong())
            val margin = geometry.heights.renderLines(scrollY, height, direction)
            geometry.prefetch(
                geometry.heights.visibleLines(scrollY, height),
                margin,
                direction,
                PREFETCH_LINES_PER_FRAME,
            )
            var y = scrollY + geometry.takeScrollShift()
            y = geometry.settle(y, height).coerceIn(0, maxScrollY())
            if (y != scrollY) scrollTo(scrollX, y)
            val visible = geometry.heights.visibleLines(scrollY, height)
            highlight.prepare(visible)
            if (visible.first != lastAnchorLine) {
                lastAnchorLine = visible.first
                listener?.onAnchorLineChanged(visible.first)
            }
            return visible
        }

        override fun onDraw(canvas: Canvas) {
            val drawnX = scrollX
            val drawnY = scrollY
            val visible = prepareFrame()
            frame.width = width
            frame.height = height
            frame.scrollX = horizontalScroll()
            frame.scrollY = scrollY
            frame.focused = hasFocus()
            // View.draw translated by the scroll it saw; draw in view coordinates instead.
            canvas.save()
            canvas.translate(drawnX.toFloat(), drawnY.toFloat())
            renderer.draw(canvas, visible, frame)
            selectionUi.drawHandles(canvas, theme.caret)
            canvas.restore()
            widestVisibleRow = visible.maxOfOrNull { geometry.layoutFor(it).width } ?: 0f
        }

        override fun computeScroll() {
            if (scroller.computeScrollOffset()) {
                val x = if (wrap) 0 else scroller.currX.coerceIn(0, maxScrollX())
                scrollTo(x, scroller.currY.coerceIn(0, maxScrollY()))
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
            geometry.anchorLine = geometry.heights.lineAt(t.toLong())
            direction =
                when {
                    t > oldt -> ScrollDirection.TOWARDS_END
                    t < oldt -> ScrollDirection.TOWARDS_START
                    else -> direction
                }
        }

        // ------------------------------------------------------------ touch

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val consumed = touch.onTouchEvent(event)
            // The tap already placed the caret; the click is what accessibility services can repeat.
            if (touch.takeTap()) performClick()
            return consumed || super.onTouchEvent(event)
        }

        /** A click (a tap, or an accessibility action) focuses the editor and raises the keyboard. */
        override fun performClick(): Boolean {
            super.performClick()
            requestFocus()
            inputMethods?.showSoftInput(this, 0)
            return true
        }

        // --------------------------------------------------- keyboard and keys

        override fun onCheckIsTextEditor(): Boolean = true

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            ImeConfig.fill(outAttrs, session)
            return CodeInputConnection(this, session, keys::onKey)
        }

        override fun onKeyDown(
            keyCode: Int,
            event: KeyEvent,
        ): Boolean = keys.onKey(event) || super.onKeyDown(keyCode, event)

        override fun onKeyUp(
            keyCode: Int,
            event: KeyEvent,
        ): Boolean = KeyCommands.actionFor(event) != null || super.onKeyUp(keyCode, event)

        override fun onTextChanged(change: LineChange) {
            geometry.onEdit(change)
            highlight.onEdit(change)
            if (onMetricsChanged()) relayout(anchorLine, 0f)
            selectionUi.setInsertionHandle(false)
            selectionUi.hideToolbar()
            listener?.onTextEdited(change, session.document.version)
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
            listener?.onSelectionChanged(session.selectionStart, session.selectionEnd)
            val caretLine =
                session.document.text
                    .positionAt(session.caret)
                    .line
            if (caretLine != lastCaretLine &&
                a11yManager?.isEnabled == true
            ) {
                accessibility.onCaretLineChanged(caretLine)
            }
            lastCaretLine = caretLine
            invalidate()
        }

        private fun revealCaret() {
            if (height == 0) return
            val point = geometry.locate(session.caret)
            val y = revealScroll(point.top, point.top + rowHeight, scrollY, height)
            val x =
                when {
                    wrap -> {
                        0
                    }

                    point.x < scrollX -> {
                        point.x.toInt()
                    }

                    point.x > scrollX + frame.textColumnWidth() - frame.gap -> {
                        (point.x - frame.textColumnWidth() + frame.gap)
                            .roundToInt()
                    }

                    else -> {
                        scrollX
                    }
                }
            if (x != scrollX || y != scrollY) scrollTo(x.coerceAtLeast(0), y)
        }

        // --------------------------------------------- focus, a11y, lifecycle

        override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = accessibility

        override fun onFocusChanged(
            gainFocus: Boolean,
            direction: Int,
            previouslyFocusedRect: Rect?,
        ) {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
            if (gainFocus) {
                restartBlink()
            } else {
                removeCallbacks(blink)
                session.finishComposingText()
                selectionUi.hideToolbar()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            viewTreeObserver.addOnPreDrawListener(preDraw)
        }

        override fun onDetachedFromWindow() {
            viewTreeObserver.removeOnPreDrawListener(preDraw)
            removeCallbacks(blink)
            selectionUi.hideToolbar()
            super.onDetachedFromWindow()
        }

        override fun onConfigurationChanged(newConfig: Configuration) {
            super.onConfigurationChanged(newConfig)
            // Layout changes commit the keyboard's word first (robustness.md §5a.4).
            session.finishComposingText()
            if (themeFollowsSystem) applyTheme(EditorTheme.forContext(context))
            onFontChanged(anchorLine, 0f)
        }

        private fun restartBlink() {
            removeCallbacks(blink)
            frame.caretVisible = true
            if (hasFocus()) postDelayed(blink, BLINK_MILLIS)
        }

        private fun spToPx(sp: Float): Float =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)

        private companion object {
            const val BLINK_MILLIS = 500L
            const val PREFETCH_LINES_PER_FRAME = 4
        }
    }
