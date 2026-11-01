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
 * - When the caret moves to another line, [onCaretLineChanged] reports the
 *   new line as traversed text, which TalkBack reads aloud.
 */
internal class EditorAccessibility(
    private val view: MarkdownCodeView,
) : AccessibilityNodeProvider() {
    private val text = AccessibilityText()
    private val bounds = Rect()
    private var focusedLine = View.NO_ID

    override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? =
        if (virtualViewId == View.NO_ID) hostNode() else lineNode(virtualViewId)

    override fun performAction(
        virtualViewId: Int,
        action: Int,
        arguments: Bundle?,
    ): Boolean = if (virtualViewId == View.NO_ID) hostAction(action, arguments) else lineAction(virtualViewId, action)

    /** Tells TalkBack the caret moved onto [line]: the line is read as traversed text. */
    fun onCaretLineChanged(line: Int) {
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
        view.parent?.requestSendAccessibilityEvent(view, event)
    }

    private fun hostNode(): AccessibilityNodeInfo {
        val node = newNode(view, View.NO_ID)
        view.onInitializeAccessibilityNodeInfo(node)
        val session = view.session
        val window = text.nodeWindow(session)
        node.className = EDIT_TEXT_CLASS
        node.text = window.text
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
        for (line in view.visibleLineRange()) node.addChild(view, line)
        return node
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

            else -> {
                view.performAccessibilityAction(action, arguments)
            }
        }
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
        view.parent?.requestSendAccessibilityEvent(view, event)
    }

    private companion object {
        const val EDIT_TEXT_CLASS = "android.widget.EditText"
        const val TEXT_VIEW_CLASS = "android.widget.TextView"

        val HOST_ACTIONS =
            listOf(
                AccessibilityAction.ACTION_NEXT_AT_MOVEMENT_GRANULARITY,
                AccessibilityAction.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY,
                AccessibilityAction.ACTION_SET_SELECTION,
                AccessibilityAction.ACTION_COPY,
                AccessibilityAction.ACTION_CUT,
                AccessibilityAction.ACTION_PASTE,
                AccessibilityAction.ACTION_CLICK,
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
