package com.scorchsoft.ssmarkdown.editor

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Typeface
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
import com.scorchsoft.ssmarkdown.text.EditOp
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextDocument
import java.util.concurrent.Executor

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

        /**
         * Where a large paste's clip becomes text, off the main thread. The
         * host injects its thread (AppGraph's `pasteExecutor`); the default
         * runs inline, for tests and previews. It used to be a hidden
         * process-wide executor (code review CC-D).
         */
        var backgroundExecutor: Executor = Executor(Runnable::run)

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
        private var lastScrollFraction = -1f
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
            // A new document reports its position afresh, even where it matches the old one's.
            lastScrollFraction = -1f
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
         * Whether the line-number gutter is drawn (Settings → Show line
         * numbers). Off, the gutter is 0 px wide and the text column starts
         * a small inset from the left edge, so it widens and wrapped rows
         * break again. Switching keeps the caret's row where it is on screen
         * (or as near as the new wrapping allows), else the top line where
         * it is ([ViewSizing.onGutterChanged]).
         */
        var showLineNumbers: Boolean
            get() = frame.showGutter
            set(value) {
                if (frame.showGutter == value) return
                frame.showGutter = value
                sizing.onGutterChanged()
            }

        /**
         * Left and Right follow visual order on lines with right-to-left text
         * ([BidiCaret]), the order the renderer draws them in ([BidiText]).
         * On by default; off moves logically, char by char in memory order.
         */
        var bidiCaretMovement: Boolean = true

        /** Text size in px. Prefer [zoomPercent]; this exists for benchmarks and tests. */
        var textSizePx: Float
            get() = font.paint.textSize
            set(value) {
                font.paint.textSize = value
                sizing.onFontChanged(anchorLine, 0f)
            }

        /**
         * The face the source is set in. The app hands in the bundled
         * JetBrains Mono (docs/ux-spec.md, "Code font"; UX2-12); a view
         * nobody configures keeps the system monospace. A change keeps the
         * top line in place, like a zoom.
         */
        var codeTypeface: Typeface
            get() = font.paint.typeface ?: Typeface.MONOSPACE
            set(value) {
                if (font.paint.typeface == value) return
                font.paint.typeface = value
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

        /** Left edge of the text column, in view px (gutter plus its gap; the gap alone with no line numbers). */
        val textLeft: Int get() = frame.textLeft

        /** Scrolls so [line] is at the top of the pane. */
        fun scrollToLine(line: Int) {
            viewport.stopFling()
            viewport.placeLine(line, viewY = 0f)
        }

        /**
         * Scrolls the least distance that shows `[start, end)` with two rows
         * of context, wrapped rows included; its start wins when it is
         * taller than the pane. A no-op when it is already in view. Hosts use
         * it to show a find match or a selection they set.
         */
        fun revealRange(
            start: Int,
            end: Int,
        ) {
            val length = session.document.text.length
            viewport.revealRange(start.coerceIn(0, length), end.coerceIn(0, length), rowHeight, rowHeight * REVEAL_ROWS)
        }

        /**
         * Commits the keyboard's in-progress word, and puts the rest of a large paste in
         * ([completePaste]); hosts call it before layout changes, commands and saves.
         */
        fun requestFlushComposing() {
            completePaste()
            session.finishComposingText()
        }

        /**
         * Puts the rest of a running large paste in now, as part of its undo
         * step, so a host edit or undo can follow it ([applyEdit] and
         * [applyEdits] do this themselves, moving their offsets past it).
         * While the clip is still becoming text nothing is open, and nothing waits.
         */
        fun completePaste() {
            paste.completeNow()
        }

        /**
         * Undoes one step. A running paste goes in first and is what is
         * undone; typing held during it goes in after, at the caret Undo
         * leaves (edge-cases "Undo during a paste with typing held").
         */
        fun undo(): Boolean = paste.completeThen { session.undo() }

        /** Redoes one step, finishing a running paste as [undo] does. */
        fun redo(): Boolean = paste.completeThen { session.redo() }

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
            // Inside a paste's open edit group it would join the paste and shift under its slices: finish it first.
            val rest = paste.completeNow()
            if (rest != null && rest.splits(minOf(start, end), maxOf(start, end))) return
            val follow = { at: Int -> if (rest == null || at == EditingSession.NONE) at else rest.follow(at) }
            session.document.beginEditGroup()
            try {
                session.applyEdit(follow(start), follow(end), text, follow(selectStart), follow(selectEnd))
            } finally {
                session.document.endEditGroup()
            }
        }

        /** Places the caret at [offset], ends any composition and scrolls it into view. */
        fun setCaret(offset: Int) = session.moveCaretTo(offset)

        /** True while any selection handle is drawn: the insertion handle after a tap, or the selection's ends. */
        val isSelectionHandleShown: Boolean
            get() = HandleKind.entries.any { selectionUi.handlePoint(it) != null }

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
            if (w != oldw) {
                sizing.relayout(anchorLine, 0f)
            } else if (h in 1 until oldh) {
                // The keyboard or the find bar took room: a caret that was on screen stays on screen,
                // as in EditText, so a find match is never left under the keyboard.
                val caretY = yForOffset(session.caret)
                if (caretY >= 0f && caretY < oldh) viewport.reveal(session.caret, rowHeight)
            }
        }

        /** Settles layout and scroll, and tokenises the visible lines within the main-thread budget. */
        private fun prepareFrame(): IntRange {
            val visible = viewport.settle(PREFETCH_LINES_PER_FRAME)
            highlight.prepare(visible)
            if (visible.first != lastAnchorLine) {
                lastAnchorLine = visible.first
                listener?.onAnchorLineChanged(visible.first)
            }
            // Checked every frame: an edit, wrap or zoom moves it as surely as scrolling does.
            val fraction = viewport.scrollFraction()
            if (fraction != lastScrollFraction) {
                lastScrollFraction = fraction
                listener?.onScrollFractionChanged(fraction)
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
            viewport.widestVisibleRow = widestRow(visible)
        }

        /** The widest laid-out row on screen; a plain loop, because `maxOfOrNull` boxes every line number. */
        private fun widestRow(visible: IntRange): Float {
            var widest = 0f
            for (line in visible) widest = maxOf(widest, geometry.layoutFor(line).width)
            return widest
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
            return CodeInputConnection(this, session, keys::onKey, gate, cursorAnchors) { listener }
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
            if (compoundDepth > 0) heldChange = change else listener?.onTextEdited(change, session.document.version)
            invalidate()
        }

        /** Open [applyEdits] calls; while above 0 the host hears of edits once, at the end. */
        private var compoundDepth = 0

        /** The last edit made inside [applyEdits], reported when it ends; null when none. */
        private var heldChange: LineChange? = null

        /**
         * Applies [ops] (each at its offset in the text as the ops before it
         * left it, so highest offset first for offsets in one snapshot) as
         * one undo step, then selects `[selectStart, selectEnd)`. The host
         * and the keyboard hear about it once, not once per op: a
         * replace-all of 10,000 matches told them 10,000 times and took
         * seconds on the main thread (edge-cases "Replace all with many matches").
         */
        fun applyEdits(
            ops: List<EditOp>,
            selectStart: Int,
            selectEnd: Int,
        ) {
            // A running paste goes in first; ops taken before it follow the text past it, or are refused
            // when it landed inside one of them (edge-cases "Undo or a command during a large paste").
            val rest = paste.completeNow()
            if (rest != null) {
                if (ops.any { rest.splits(it.offset, it.offset + it.deleted.length) }) return
                applyEdits(
                    ops.map { EditOp(rest.follow(it.offset), it.deleted, it.inserted) },
                    rest.follow(selectStart),
                    rest.follow(selectEnd),
                )
                return
            }
            val document = session.document
            // Ops built on text that has since moved would delete the wrong chars: refuse them whole.
            if (!fits(document.text, ops)) return
            session.beginBatchEdit()
            document.beginEditGroup()
            compoundDepth++
            try {
                if (ops.size > MANY_OPS) {
                    // Every cache told once, for the envelope of all of them, as undo does: told op by op,
                    // the tokenizer's and the highlights' shifting made this quadratic.
                    session.finishComposingText()
                    ops.forEach { document.replace(it.offset, it.deleted.length, it.inserted) }
                    session.reportExternalChange(EditEnvelope.of(ops).lineChange(document.text), selectStart)
                } else {
                    ops.forEach { session.applyEdit(it.offset, it.offset + it.deleted.length, it.inserted) }
                }
            } finally {
                document.endEditGroup()
                compoundDepth--
                if (compoundDepth == 0) {
                    heldChange?.let { listener?.onTextEdited(it, document.version) }
                    heldChange = null
                }
                session.setSelection(selectStart, selectEnd)
                session.endBatchEdit()
            }
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

            /** Chars compared at each end of an op's deleted text by [fits]; a whole-text replace stays cheap. */
            const val FIT_CHARS = 256

            /**
             * True when each op still finds its deleted text at its offset. Ops in one snapshot (highest
             * offset first, none overlapping) are all checked against the text as it is; any other list
             * has only its first op checked, the one that sees the text unchanged.
             */
            fun fits(
                text: PieceTree,
                ops: List<EditOp>,
            ): Boolean {
                val descending = ops.zipWithNext().all { (a, b) -> a.offset >= b.offset + b.deleted.length }
                return (if (descending) ops else ops.take(1)).all { holds(text, it) }
            }

            private fun holds(
                text: PieceTree,
                op: EditOp,
            ): Boolean {
                val deleted = op.deleted
                val end = op.offset + deleted.length
                if (end > text.length) return false
                val n = minOf(deleted.length, FIT_CHARS)
                return text.getText(op.offset, op.offset + n).contentEquals(deleted.subSequence(0, n)) &&
                    text.getText(end - n, end).contentEquals(deleted.subSequence(deleted.length - n, deleted.length))
            }

            /** Rows of context [revealRange] keeps above and below what it shows. */
            const val REVEAL_ROWS = 2

            /** More ops than this in one [applyEdits] reach the caches as one change and drop the find highlights. */
            const val MANY_OPS = 64
        }
    }
