package com.scorchsoft.ssmarkdown.editor

import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/** The virtual node tree: an editable host node with a capped window, and one child per visible line. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorAccessibilityTest {
    private val view =
        MarkdownCodeView(ApplicationProvider.getApplicationContext()).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, WIDTH, HEIGHT)
        }

    private val provider = view.accessibilityNodeProvider

    private fun show(text: String) {
        view.setDocument(TextDocument(PieceTree.of(text)))
    }

    @Test
    fun `the host node is editable text with the selection inside its window`() {
        show("hello\nworld")
        view.session.setSelection(1, 3)
        val node = checkNotNull(provider.createAccessibilityNodeInfo(View.NO_ID))
        assertTrue(node.isEditable)
        assertTrue(node.isMultiLine)
        assertEquals("hello\nworld", node.text.toString())
        assertEquals(1, node.textSelectionStart)
        assertEquals(3, node.textSelectionEnd)
        val granularities = node.movementGranularities
        assertTrue(granularities and AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE != 0)
        assertTrue(granularities and AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD != 0)
    }

    @Test
    fun `a huge document hands accessibility at most 100K chars`() {
        show("z".repeat(3_000_000))
        view.setCaret(2_000_000)
        val node = checkNotNull(provider.createAccessibilityNodeInfo(View.NO_ID))
        assertEquals(AccessibilityText.MAX_NODE_CHARS, node.text.length)
    }

    @Test
    fun `visible lines are children, with their text and bounds`() {
        show((0 until 5_000).joinToString("\n") { "line $it" })
        view.scrollToLine(1_000)
        val host = checkNotNull(provider.createAccessibilityNodeInfo(View.NO_ID))
        val visible = HEIGHT / view.lineHeight
        assertTrue("about a screen of children, was ${host.childCount}", host.childCount in visible..visible + 2)
        val child = checkNotNull(provider.createAccessibilityNodeInfo(1_000))
        assertEquals("line 1000", child.text.toString())
        assertTrue(child.isVisibleToUser)
        assertNull(provider.createAccessibilityNodeInfo(10_000))
    }

    @Test
    fun `movement by granularity moves the caret`() {
        show("one two\nthree")
        view.setCaret(0)
        val args =
            Bundle().apply {
                putInt(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
                    AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD,
                )
            }
        assertTrue(provider.performAction(View.NO_ID, AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, args))
        assertEquals(3, view.session.caret)
        args.putInt(
            AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE,
        )
        args.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, true)
        assertTrue(provider.performAction(View.NO_ID, AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, args))
        assertEquals(3, view.session.selectionStart)
        assertEquals(7, view.session.selectionEnd)
    }

    @Test
    fun `set selection is relative to the node's window`() {
        show("abcdef")
        val args =
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 2)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 4)
            }
        assertTrue(provider.performAction(View.NO_ID, AccessibilityNodeInfo.ACTION_SET_SELECTION, args))
        assertEquals(2, view.session.selectionStart)
        assertEquals(4, view.session.selectionEnd)
    }

    @Test
    fun `clicking a line node puts the caret at its start`() {
        show("a\nb\nc")
        assertNotNull(provider.createAccessibilityNodeInfo(2))
        provider.performAction(2, AccessibilityNodeInfo.ACTION_CLICK, null)
        assertEquals(4, view.session.caret)
    }

    // ------------------------------------------------------------ UX-2

    private val events = ArrayList<AccessibilityEvent>()

    /** Turns an accessibility service on and records what the editor tells it. */
    private fun listen() {
        val manager = view.context.getSystemService(AccessibilityManager::class.java)
        shadowOf(manager).setEnabled(true)
        (provider as EditorAccessibility).sendEvent = { events += AccessibilityEvent(it) }
    }

    private fun host() = checkNotNull(provider.createAccessibilityNodeInfo(View.NO_ID))

    private fun AccessibilityNodeInfo.has(action: AccessibilityNodeInfo.AccessibilityAction) = action in actionList

    @Test
    fun `the editor is scrollable and pages past the visible lines`() {
        show((0 until 5_000).joinToString("\n") { "line $it" })
        listen()
        val top = host()
        assertTrue(top.isScrollable)
        assertTrue(top.has(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD))
        assertFalse(top.has(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD))
        val firstLines = view.visibleLineRange()
        assertTrue(provider.performAction(View.NO_ID, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
        val nextLines = view.visibleLineRange()
        assertTrue("paged from $firstLines to $nextLines", nextLines.first in firstLines.last - 1..firstLines.last)
        assertTrue(host().has(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD))
        assertTrue(events.any { it.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED })
        assertTrue(provider.performAction(View.NO_ID, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null))
        assertEquals(0, view.scrollY)
    }

    @Test
    fun `typing and deleting are spoken`() {
        show("ab")
        listen()
        view.setCaret(2)
        view.applyEdit(2, 2, "cd")
        val typed = events.last { it.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED }
        assertEquals(2, typed.addedCount)
        assertEquals("cd", typed.text.single().toString())
        view.applyEdit(0, 1, "")
        val deleted = events.last { it.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED }
        assertEquals(1, deleted.removedCount)
        assertEquals("a", deleted.beforeText.toString())
        assertEquals(0, deleted.addedCount)
    }

    @Test
    fun `a selection is announced within the node's window`() {
        show("hello world")
        listen()
        view.session.setSelection(6, 11)
        val selected = events.last { it.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED }
        assertEquals(6, selected.fromIndex)
        assertEquals(11, selected.toIndex)
        assertEquals(11, selected.itemCount)
    }

    @Test
    fun `next and previous heading move the cursor, skipping fenced hashes`() {
        show("# One\ntext\n## Two\nmore\n```\n# not a heading\n```\n### Three")
        // Tokenise every line now, whatever the machine's speed: the outline is what the actions read.
        check(view.highlight.tokens.tokenizeUntil(7, Long.MAX_VALUE))
        view.setCaret(6) // "text"
        val next = host().actionList.single { it.label == "Next heading" }.id
        val previous = host().actionList.single { it.label == "Previous heading" }.id
        assertTrue(provider.performAction(View.NO_ID, next, null))
        assertEquals(11, view.session.caret) // "## Two"
        assertTrue(provider.performAction(View.NO_ID, next, null))
        assertEquals("### Three".let { view.document.text.length - it.length }, view.session.caret)
        assertFalse(provider.performAction(View.NO_ID, next, null))
        assertTrue(provider.performAction(View.NO_ID, previous, null))
        assertEquals(11, view.session.caret)
        // Line nodes carry the heading flag, so TalkBack's heading navigation works too.
        assertTrue(checkNotNull(provider.createAccessibilityNodeInfo(0)).isHeading)
        assertFalse(checkNotNull(provider.createAccessibilityNodeInfo(5)).isHeading)
    }

    @Test
    fun `an empty document reads its hint`() {
        show("")
        val node = host()
        assertEquals("Start writing…", node.hintText.toString())
        assertTrue(node.isShowingHintText)
        show("x")
        assertFalse(host().isShowingHintText)
    }

    @Test
    fun `set text replaces the node's text`() {
        show("old")
        val args =
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "new text")
            }
        assertTrue(provider.performAction(View.NO_ID, AccessibilityNodeInfo.ACTION_SET_TEXT, args))
        assertEquals("new text", view.document.text.toString())
    }

    private companion object {
        const val WIDTH = 1_080
        const val HEIGHT = 1_920
    }
}
