package com.scorchsoft.ssmarkdown.editor

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityNodeProvider
import com.scorchsoft.ssmarkdown.feature.editor.R

/**
 * The code view's accessibility tree (robustness.md: "a virtual node tree
 * for visible lines"):
 *
 * - The view itself is an editable, multi-line text node whose text is a
 *   window of at most 100K chars around the selection
 *   ([AccessibilityText.MAX_NODE_CHARS]), with the selection inside it, so
 *   TalkBack can read, select and edit without the whole file crossing
 *   Binder. It supports character, word and line movement and the
 *   clipboard actions.
 * - Each visible line is a virtual child (id = line index) with its text
 *   and on-screen bounds, so explore-by-touch reads the line under a finger.
 * - The host is scrollable (UX-2): scroll forward and backward page the
 *   document, so TalkBack's linear navigation and Switch Access carry on
 *   past the last visible line instead of jumping to the mode bar.
 * - Headings are marked on their line nodes, so TalkBack's "Headings"
 *   navigation works, and "Next heading" / "Previous heading" are custom
 *   actions that move the cursor (docs/ux-spec.md, "Editor accessibility").
 * - Edits and selection changes are spoken: [onTextReplaced] sends what a
 *   keystroke, paste, strip command or deletion added or removed, and
 *   [onSelectionSettled] the new selection, or the new line when the caret
 *   changed lines.
 * - An empty document shows its hint, "Start writing…".
 */
@Suppress("TooManyFunctions") // The provider contract plus one small handler per action and event kind.
internal class EditorAccessibility(
    private val view: MarkdownCodeView,
    /** Whether an accessibility service is listening; events are skipped when none is. */
    private val active: () -> Boolean,
) : AccessibilityNodeProvider() {
    private val text = AccessibilityText()
    private val bounds = Rect()
    private var focusedLine = View.NO_ID

    /** Where events go; the view's parent in the app, a list in tests. */
    var sendEvent: (AccessibilityEvent) -> Unit = { view.parent?.requestSendAccessibilityEvent(view, it) }

    /** What an edit removed, read in [beforeReplace] while the old text still exists. */
    private var removedText: CharSequence = ""

    /** An edit just moved the selection; its own text-changed event already said so. */
    private var selectionFromEdit = false

    /** Captures the text `[start, end)` is about to lose, for the next text-changed event. */
    fun beforeReplace(
        start: Int,
        end: Int,
    ) {
        if (!active()) return
        removedText =
            view.session.document.text
                .getText(start, minOf(end, start + MAX_EVENT_CHARS))
    }

    /**
     * Tells TalkBack what an edit did: `[start, start + removed)` became
     * [inserted] chars. The event carries only the change, capped, never the
     * whole document; TalkBack reads added text, or the removed text on a
     * deletion. Undo and redo report no extent (`start < 0`) and are left to
     * the selection event that follows them.
     */
    fun onTextReplaced(
        start: Int,
        removed: Int,
        inserted: Int,
    ) {
        if (!active() || start < 0 || view.isPasting) return
        val added =
            view.session.document.text
                .getText(start, start + minOf(inserted, MAX_EVENT_CHARS))
        val event = newEvent(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
        event.setSource(view)
        event.className = EDIT_TEXT_CLASS
        event.packageName = view.context.packageName
        event.fromIndex = 0
        event.removedCount = minOf(removed, removedText.length)
        event.addedCount = added.length
        event.beforeText = removedText
        event.text.add(added)
        removedText = ""
        selectionFromEdit = true
        sendEvent(event)
    }

    /**
     * The selection settled after a change. A caret that moved to another
     * line reads that line; any other move or selection sends the new
     * selection inside the node's window; one that an edit made is already
     * covered by the edit's own event.
     */
    fun onSelectionSettled(lineChanged: Boolean) {
        if (!active()) return
        val session = view.session
        val fromEdit = selectionFromEdit
        selectionFromEdit = false
        when {
            lineChanged && !session.hasSelection -> {
                onCaretLineChanged(
                    session.document.text
                        .positionAt(session.caret)
                        .line,
                )
            }

            fromEdit -> {
                Unit
            }

            else -> {
                sendSelection()
            }
        }
    }

    private fun sendSelection() {
        val session = view.session
        val window = text.nodeWindow(session)
        val event = newEvent(AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED)
        event.setSource(view)
        event.className = EDIT_TEXT_CLASS
        event.packageName = view.context.packageName
        event.fromIndex = session.selectionStart - window.start
        event.toIndex = session.selectionEnd - window.start
        event.itemCount = window.text.length
        event.text.add(window.text)
        sendEvent(event)
    }

    override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? =
        if (virtualViewId == View.NO_ID) hostNode() else lineNode(virtualViewId)

    override fun performAction(
        virtualViewId: Int,
        action: Int,
        arguments: Bundle?,
    ): Boolean = if (virtualViewId == View.NO_ID) hostAction(action, arguments) else lineAction(virtualViewId, action)

    /** Tells TalkBack the caret moved onto [line]: the line is read as traversed text. */
    private fun onCaretLineChanged(line: Int) {
        val window = text.nodeWindow(view.session)
        val tree = view.session.document.text
        val from = (tree.lineStartOffset(line) - window.start).coerceIn(0, window.text.length)
        val to = (tree.lineEndOffset(line) - window.start).coerceIn(from, window.text.length)
        val event = newEvent(AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY)
        event.setSource(view)
        event.movementGranularity = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE
        event.fromIndex = from
        event.toIndex = to
        event.text.add(window.text)
        sendEvent(event)
    }

    private fun hostNode(): AccessibilityNodeInfo {
        val node = newNode(view, View.NO_ID)
        view.onInitializeAccessibilityNodeInfo(node)
        val session = view.session
        val window = text.nodeWindow(session)
        node.className = EDIT_TEXT_CLASS
        node.hintText = view.accessibilityHint
        if (session.document.text.length == 0 && view.accessibilityHint != null) {
            // As an empty EditText does: the hint is the text, flagged as a hint.
            node.text = view.accessibilityHint
            node.isShowingHintText = true
        } else {
            node.text = window.text
        }
        node.isEditable = true
        node.isMultiLine = true
        node.isFocusable = true
        node.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        node.maxTextLength = -1
        node.setTextSelection(session.selectionStart - window.start, session.selectionEnd - window.start)
        node.movementGranularities =
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER or
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD or
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE
        HOST_ACTIONS.forEach(node::addAction)
        addScrollActions(node)
        node.addAction(
            AccessibilityAction(
                R.id.editor_action_next_heading,
                view.context.getString(R.string.editor_a11y_next_heading),
            ),
        )
        node.addAction(
            AccessibilityAction(
                R.id.editor_action_previous_heading,
                view.context.getString(R.string.editor_a11y_previous_heading),
            ),
        )
        for (line in view.visibleLineRange()) node.addChild(view, line)
        return node
    }

    /** Scrollable, with forward and back offered only where there is somewhere to go. */
    private fun addScrollActions(node: AccessibilityNodeInfo) {
        node.isScrollable = true
        if (view.scrollY < view.viewport.maxScrollY()) {
            node.addAction(AccessibilityAction.ACTION_SCROLL_FORWARD)
            node.addAction(AccessibilityAction.ACTION_SCROLL_DOWN)
        }
        if (view.scrollY > 0) {
            node.addAction(AccessibilityAction.ACTION_SCROLL_BACKWARD)
            node.addAction(AccessibilityAction.ACTION_SCROLL_UP)
        }
    }

    /**
     * Pages the document by a screen less one line, so a line is shared
     * across the page turn. The view itself announces the scroll; the
     * content-changed event makes services fetch the new visible lines.
     */
    private fun scrollPage(forward: Boolean): Boolean {
        val before = view.scrollY
        val page = (view.height - view.lineHeight).coerceAtLeast(view.lineHeight)
        view.stopFling()
        view.scrollByClamped(0, if (forward) page else -page)
        if (view.scrollY == before) return false
        val event = newEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        event.setSource(view)
        event.contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
        sendEvent(event)
        return true
    }

    /**
     * Moves the caret to the start of the next heading below its line, or
     * the nearest heading above it, from the tokenizer's outline (so a `#`
     * inside a fence is never one). The caret's line change is then read.
     */
    private fun jumpToHeading(forward: Boolean): Boolean {
        val outline = view.highlight.tokens.outline
        val tree = view.session.document.text
        val line = tree.positionAt(view.session.caret).line
        val index = if (forward) outline.indexAt(line) + 1 else outline.indexAt(line - 1)
        if (index !in 0 until outline.size) return false
        view.session.moveCaretTo(tree.lineStartOffset(outline.heading(index).line))
        return true
    }

    private fun lineNode(line: Int): AccessibilityNodeInfo? {
        val tree = view.session.document.text
        if (line !in 0 until tree.lineCount) return null
        val node = newNode(view, line)
        node.setParent(view)
        node.packageName = view.context.packageName
        node.className = TEXT_VIEW_CLASS
        node.text = text.lineText(tree, line)
        val visible = view.lineBoundsInView(line, bounds)
        node.setBoundsInParent(bounds)
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        bounds.offset(location[0], location[1])
        node.setBoundsInScreen(bounds)
        node.isVisibleToUser = visible
        node.isEnabled = true
        node.isHeading = view.highlight.tokens.outline
            .indexOfLine(line) >= 0
        node.isAccessibilityFocused = focusedLine == line
        node.addAction(AccessibilityAction.ACTION_CLICK)
        node.addAction(
            if (focusedLine == line) {
                AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
            } else {
                AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS
            },
        )
        return node
    }

    private fun lineAction(
        line: Int,
        action: Int,
    ): Boolean =
        when (action) {
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                focusedLine = line
                sendLineEvent(line, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                true
            }

            AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                if (focusedLine == line) focusedLine = View.NO_ID
                sendLineEvent(line, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                true
            }

            AccessibilityNodeInfo.ACTION_CLICK -> {
                view.session.moveCaretTo(
                    view.session.document.text
                        .lineStartOffset(line),
                )
                view.performClick()
            }

            else -> {
                false
            }
        }

    private fun hostAction(
        action: Int,
        arguments: Bundle?,
    ): Boolean {
        val session = view.session
        return when (action) {
            AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY,
            AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY,
            -> {
                move(action == AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, arguments)
            }

            AccessibilityNodeInfo.ACTION_SET_SELECTION -> {
                val window = text.nodeWindow(session)
                val start = arguments?.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT) ?: 0
                val end = arguments?.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT) ?: start
                session.setSelection(window.start + start, window.start + end)
                true
            }

            AccessibilityNodeInfo.ACTION_COPY -> {
                view.runCommand(EditorCommand.COPY)
            }

            AccessibilityNodeInfo.ACTION_CUT -> {
                view.runCommand(EditorCommand.CUT)
            }

            AccessibilityNodeInfo.ACTION_PASTE -> {
                view.runCommand(EditorCommand.PASTE)
            }

            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, android.R.id.accessibilityActionScrollDown -> {
                scrollPage(forward = true)
            }

            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, android.R.id.accessibilityActionScrollUp -> {
                scrollPage(forward = false)
            }

            AccessibilityNodeInfo.ACTION_SET_TEXT -> {
                setText(arguments?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE))
            }

            R.id.editor_action_next_heading -> {
                jumpToHeading(forward = true)
            }

            R.id.editor_action_previous_heading -> {
                jumpToHeading(forward = false)
            }

            else -> {
                view.performAccessibilityAction(action, arguments)
            }
        }
    }

    /**
     * Voice Access and other services replace the node's text: here that is
     * the window the node shows (the whole document when it is under 100K
     * chars), replaced as one undoable edit.
     */
    private fun setText(value: CharSequence?): Boolean {
        val window = text.nodeWindow(view.session)
        view.applyEdit(window.start, window.start + window.text.length, value ?: "")
        return true
    }

    private fun move(
        forward: Boolean,
        arguments: Bundle?,
    ): Boolean {
        val session = view.session
        val granularity =
            when (arguments?.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT)) {
                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER -> Granularity.CHARACTER
                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD -> Granularity.WORD
                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE -> Granularity.LINE
                else -> null
            }
        val extend = arguments?.getBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN) == true
        val span = granularity?.let { text.step(session, session.caret, it, forward) }
        span?.let { session.moveOrExtend(if (forward) it.last + 1 else it.first, extend) }
        return span != null
    }

    private fun sendLineEvent(
        line: Int,
        type: Int,
    ) {
        val event = newEvent(type)
        event.setSource(view, line)
        event.packageName = view.context.packageName
        event.className = TEXT_VIEW_CLASS
        sendEvent(event)
    }

    private companion object {
        const val EDIT_TEXT_CLASS = "android.widget.EditText"
        const val TEXT_VIEW_CLASS = "android.widget.TextView"

        /** The most text one text-changed event carries: a paste is announced by its start. */
        const val MAX_EVENT_CHARS = 1_000

        val HOST_ACTIONS =
            listOf(
                AccessibilityAction.ACTION_NEXT_AT_MOVEMENT_GRANULARITY,
                AccessibilityAction.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY,
                AccessibilityAction.ACTION_SET_SELECTION,
                AccessibilityAction.ACTION_COPY,
                AccessibilityAction.ACTION_CUT,
                AccessibilityAction.ACTION_PASTE,
                AccessibilityAction.ACTION_CLICK,
                AccessibilityAction.ACTION_SET_TEXT,
            )
    }
}

// Node and event construction: the constructors arrived in API 30, and minSdk is 29.

private fun newNode(
    view: View,
    virtualId: Int,
): AccessibilityNodeInfo =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        AccessibilityNodeInfo(view, virtualId)
    } else {
        @Suppress("DEPRECATION") // The constructor arrived in API 30; minSdk is 29.
        AccessibilityNodeInfo.obtain(view, virtualId)
    }

private fun newEvent(type: Int): AccessibilityEvent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        AccessibilityEvent(type)
    } else {
        @Suppress("DEPRECATION") // The constructor arrived in API 30; minSdk is 29.
        AccessibilityEvent.obtain(type)
    }
