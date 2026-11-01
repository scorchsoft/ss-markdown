package com.scorchsoft.ssmarkdown.editor

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Rect
import android.util.AttributeSet
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
import com.scorchsoft.ssmarkdown.feature.editor.R
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextDocument

/**
 * The Code pane: a virtualised, syntax-coloured, monospace text view over a
 * [TextDocument] (docs/robustness.md, "Editor view"). It owns no text: the
 * document belongs to the host's store, and this view is bound to it with
 * [setDocument].
 *
 * - Draws only the visible rows, each line's text replayed from a cached
 *   display list while it is unchanged ([EditorRenderer]); lays out lines
 *   lazily through a cache keyed by line + edit version ([EditorGeometry]).
 * - **Wrap mode** ([wrap]) keeps a [HeightMap] and anchors the scroll so
 *   measuring never moves what is on screen ([EditorViewport]).
 * - Colours come from the incremental Markdown tokenizer within an 8 ms
 *   budget per frame, continued in idle slices ([HighlightScheduler]).
 * - Selection, handles, the text toolbar and keys ([SelectionUi],
 *   [TouchController], [KeyRouter], [CommandRunner]); accessibility
 *   ([EditorAccessibility]); pinch changes the font size ([EditorFont]).
 * - Find matches are drawn from ranges the host sets ([setFindMatches]); a
 *   paste too big for a frame goes in by slices as one undo step
 *   ([pasteText]); keyboards can follow the caret (`requestCursorUpdates`).
 *
 * This class wires those collaborators to the Android `View` contract.
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

        /** Receives selection, edit, anchor, zoom and paste changes. */
        var listener: EditorListener? = null

        internal val frame = FrameMetrics(resources.displayMetrics.density)
        internal val font = EditorFont(resources, frame)
        internal val geometry = EditorGeometry(font.measurer, { session.document.text }, { session.document.version })
        internal val viewport = EditorViewport(this, geometry, frame, OverScroller(context))
        internal val highlight = HighlightScheduler(onRepaint = ::invalidate)
        internal val finds = FindHighlights()
        internal val renderer = EditorRenderer(geometry, session, highlight, font.paint, finds)
        internal val commands = CommandRunner(this, geometry)
        internal val selectionUi = SelectionUi(this, commands)
        private val touch = TouchController(this)
        private val keys = KeyRouter(session, commands) { listener }
        private val accessibility = EditorAccessibility(this) { a11yManager?.isEnabled == true }
        private val inputMethods: InputMethodManager? = context.getSystemService(InputMethodManager::class.java)
        private val a11yManager: AccessibilityManager? = context.getSystemService(AccessibilityManager::class.java)
        internal val words = WordFinder()
        private val blink = CaretBlink(this, frame)
        private val gate = InputGate()
        internal val cursorAnchors = CursorAnchorReporter(this) { inputMethods?.updateCursorAnchorInfo(this, it) }

        internal val sizing = ViewSizing(this)

        internal val paste = ChunkedPaste.forView(this, gate)

        private var lastAnchorLine = -1
        private var lastCaretLine = 0
        private var themeFollowsSystem = true

        private val preDraw =
            ViewTreeObserver.OnPreDrawListener {
                prepareFrame()
                true
            }

        init {
            isFocusable = true
            isFocusableInTouchMode = true
            session.listener = this
            session.onRangeReplaced = { start, removed, inserted ->
                if (start < 0) finds.clear() else finds.onEdit(start, removed, inserted)
                accessibility.onTextReplaced(start, removed, inserted)
            }
            session.beforeReplace = accessibility::beforeReplace
            applyTheme(EditorTheme.forContext(context))
            sizing.onMetricsChanged()
        }

        /**
         * What TalkBack reads for an empty document ("Start writing…" by
         * default); null for none. Hosts may set their own placeholder.
         */
        var accessibilityHint: CharSequence? = context.getString(R.string.editor_a11y_hint)

        /** The colours. Until a host sets one, the view follows the context's light or dark mode. */
        var theme: EditorTheme
            get() = renderer.styles.theme
            set(value) {
                themeFollowsSystem = false
                applyTheme(value)
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
            paste.cancel()
            session.reset(document)
            highlight.reset(document.text)
            finds.clear()
            renderer.versions.reset(document.text.lineCount)
            viewport.stopFling()
            scrollTo(0, 0)
            sizing.onMetricsChanged()
            sizing.relayout(line = 0, viewY = 0f)
            inputMethods?.restartInput(this)
            invalidate()
        }

        /** Wrap mode: rows break at the text column's width. Switching keeps the top line in place. */
        var wrap: Boolean = false
            set(value) {
                if (field == value) return
                field = value
                sizing.relayout(anchorLine, 0f)
            }

        /**
         * Left and Right follow visual order on lines with right-to-left text
         * ([BidiCaret]). Off until the renderer reorders bidi runs too: runs
         * are drawn in logical order today, and the caret must move the way
         * the text is drawn.
         */
        var bidiCaretMovement: Boolean = false

        /** Text size in px. Prefer [zoomPercent]; this exists for benchmarks and tests. */
        var textSizePx: Float
            get() = font.paint.textSize
            set(value) {
                font.paint.textSize = value
                sizing.onFontChanged(anchorLine, 0f)
            }

        /** Zoom as a percentage of Code's 100 % (12.5 sp); the host restores it per file. */
        var zoomPercent: Int
            get() = font.zoom.percent
            set(value) {
                if (font.zoom.set(value)) sizing.applyZoom(viewY = 0f)
            }

        /** The source line at the top of the pane: the position anchor shared with Preview. */
        val anchorLine: Int get() = viewport.anchorLine

        /** Height of one text row, in px. */
        val lineHeight: Int get() = geometry.heights.rowHeight

        /** Height of one text row, in px (the same as [lineHeight], named for wrapped rows). */
        val rowHeight: Int get() = geometry.heights.rowHeight

        /** Width of one monospace cell, in px. */
        val charWidth: Float get() = font.measurer.charWidth

        /** Left edge of the text column, in view px (gutter plus its gap). */
        val textLeft: Int get() = frame.textLeft

        /** Scrolls so [line] is at the top of the pane. */
        fun scrollToLine(line: Int) {
            viewport.stopFling()
            viewport.placeLine(line, viewY = 0f)
        }

        /** Commits the keyboard's in-progress word; hosts call it before layout changes, commands and saves. */
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
        fun setCaret(offset: Int) = session.moveCaretTo(offset)

        /** Starts a vertical fling, as a finger would; the benchmark uses this for scripted scrolling. */
        fun fling(velocityY: Int) = viewport.startFling(0, velocityY)

        /** The document offset under view point ([x], [y]), as a tap would place the caret. */
        fun offsetAt(
            x: Float,
            y: Float,
        ): Int = offsetAtView(x, y)

        /** The caret's x in view px, for [offset]; the inverse of [offsetAt] along a row. */
        fun xForOffset(offset: Int): Float = pointInView(offset).x

        /** Top of the row holding [offset], in view px. */
        fun yForOffset(offset: Int): Float = pointInView(offset).y

        // ------------------- layout and draw (hit testing: CodeViewInternals.kt)

        private fun applyTheme(value: EditorTheme) {
            renderer.styles = SyntaxStyles(value)
            invalidate()
        }

        override fun onSizeChanged(
            w: Int,
            h: Int,
            oldw: Int,
            oldh: Int,
        ) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w != oldw) sizing.relayout(anchorLine, 0f)
        }

        /** Settles layout and scroll, and tokenises the visible lines within the main-thread budget. */
        private fun prepareFrame(): IntRange {
            val visible = viewport.settle(PREFETCH_LINES_PER_FRAME)
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
            frame.scrollX = viewport.horizontalScroll()
            frame.scrollY = scrollY
            frame.focused = hasFocus()
            // View.draw translated by the scroll it saw; draw in view coordinates instead.
            canvas.save()
            canvas.translate(drawnX.toFloat(), drawnY.toFloat())
            renderer.draw(canvas, visible, frame)
            selectionUi.drawHandles(canvas, theme.caret)
            canvas.restore()
            viewport.widestVisibleRow = visible.maxOfOrNull { geometry.layoutFor(it).width } ?: 0f
        }

        override fun computeScroll() {
            if (viewport.stepFling()) postInvalidateOnAnimation()
        }

        override fun onScrollChanged(
            l: Int,
            t: Int,
            oldl: Int,
            oldt: Int,
        ) {
            super.onScrollChanged(l, t, oldl, oldt)
            viewport.onScrolled(t, oldt)
            cursorAnchors.onCursorMoved()
        }

        // ------------------------------------------------- touch, keys and IME

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

        override fun onCheckIsTextEditor(): Boolean = true

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            ImeConfig.fill(outAttrs, session)
            return CodeInputConnection(this, session, keys::onKey, gate, cursorAnchors)
        }

        override fun onKeyDown(
            keyCode: Int,
            event: KeyEvent,
        ): Boolean {
            if (!gate.isHolding) return keys.onKey(event) || super.onKeyDown(keyCode, event)
            val held = KeyEvent(event)
            gate.run { keys.onKey(held) }
            return true
        }

        override fun onKeyUp(
            keyCode: Int,
            event: KeyEvent,
        ): Boolean = KeyCommands.actionFor(event) != null || super.onKeyUp(keyCode, event)

        override fun onTextChanged(change: LineChange) {
            geometry.onEdit(change)
            highlight.onEdit(change)
            renderer.versions.onEdit(change)
            if (sizing.onMetricsChanged()) sizing.relayout(anchorLine, 0f)
            selectionUi.setInsertionHandle(false)
            selectionUi.hideToolbar()
            listener?.onTextEdited(change, session.document.version)
            invalidate()
        }

        override fun onSelectionSettled() {
            val s = session
            inputMethods?.updateSelection(this, s.selectionStart, s.selectionEnd, s.composingStart, s.composingEnd)
            viewport.reveal(s.caret, rowHeight)
            blink.restart()
            cursorAnchors.onCursorMoved()
            listener?.onSelectionChanged(s.selectionStart, s.selectionEnd)
            val caretLine =
                s.document.text
                    .positionAt(s.caret)
                    .line
            accessibility.onSelectionSettled(lineChanged = caretLine != lastCaretLine)
            lastCaretLine = caretLine
            invalidate()
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
                blink.restart()
            } else {
                blink.stop()
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
            blink.stop()
            selectionUi.hideToolbar()
            super.onDetachedFromWindow()
        }

        override fun onConfigurationChanged(newConfig: Configuration) {
            super.onConfigurationChanged(newConfig)
            // Layout changes commit the keyboard's word first (robustness.md §5a.4).
            session.finishComposingText()
            if (themeFollowsSystem) applyTheme(EditorTheme.forContext(context))
            sizing.onFontChanged(anchorLine, 0f)
        }

        private companion object {
            const val PREFETCH_LINES_PER_FRAME = 4
        }
    }
