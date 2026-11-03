package com.scorchsoft.ssmarkdown.editor

import android.app.Activity
import android.view.View
import android.view.inputmethod.InputMethodManager
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowInputMethodManager

/**
 * edge-cases "Strip command flashes the keyboard away": a formatting
 * command (Bold, a ¶ choice) runs while the keyboard is up and composing.
 * It must commit the composing word first (R6), apply as one batch, and
 * tell the keyboard the new selection with `updateSelection`: never
 * `restartInput` (which some keyboards draw as closing and reopening),
 * never hide the keyboard, never move focus off the editor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [CommandKeepsKeyboardTest.RecordingInputMethodManager::class])
class CommandKeepsKeyboardTest {
    private lateinit var view: MarkdownCodeView
    private lateinit var imm: InputMethodManager

    @Before
    fun setUp() {
        calls.clear()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        view = MarkdownCodeView(activity)
        activity.setContentView(view)
        view.document = TextDocument(PieceTree.of("Make  bold\n"))
        view.requestFocus()
        imm = activity.getSystemService(InputMethodManager::class.java)
        imm.showSoftInput(view, 0)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        calls.clear()
    }

    /** What StripController.run does for a strip key: flush, one batch of ops, then the selection. */
    private fun bold(
        start: Int,
        end: Int,
    ) {
        view.requestFlushComposing()
        val document = view.session.document
        document.beginEditGroup()
        try {
            view.session.applyEdit(end, end, "**")
            view.session.applyEdit(start, start, "**")
        } finally {
            document.endEditGroup()
        }
        view.session.setSelection(start + 2, end + 2)
    }

    @Test
    fun `Bold with a composing word keeps the keyboard, its connection and the focus`() {
        view.session.setSelection(5, 5)
        view.session.setComposingText("this", 1)
        calls.clear()
        bold(5, 9)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(
            "Make **this** bold\n",
            view.session.document.text
                .toString(),
        )
        assertFalse("a command must never restart input", "restartInput" in calls)
        assertTrue(shadowOf(imm).isSoftInputVisible)
        assertTrue(view.isFocused)
        // The keyboard hears the end result: the selection, and no composing region left.
        assertEquals("updateSelection 7 11 -1 -1", calls.last())
    }

    @Test
    fun `a heading from the block menu keeps the keyboard`() {
        view.session.setSelection(3, 3)
        calls.clear()
        view.requestFlushComposing()
        view.applyEdit(0, 0, "# ", selectStart = 5, selectEnd = 5)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse("restartInput" in calls)
        assertTrue(shadowOf(imm).isSoftInputVisible)
        assertTrue(view.isFocused)
        assertEquals("updateSelection 5 5 -1 -1", calls.last())
    }

    @Test
    fun `only a new document restarts input`() {
        view.document = TextDocument(PieceTree.of("Another file\n"))
        assertEquals(listOf("restartInput"), calls.filter { it == "restartInput" })
    }

    /** Records what the editor asks of the keyboard; Robolectric's own shadow does the rest. */
    @Implements(InputMethodManager::class)
    class RecordingInputMethodManager : ShadowInputMethodManager() {
        @Implementation
        override fun restartInput(view: View) {
            calls += "restartInput"
            super.restartInput(view)
        }

        @Implementation
        fun updateSelection(
            @Suppress("UNUSED_PARAMETER") view: View,
            selStart: Int,
            selEnd: Int,
            candidatesStart: Int,
            candidatesEnd: Int,
        ) {
            calls += "updateSelection $selStart $selEnd $candidatesStart $candidatesEnd"
        }
    }

    private companion object {
        val calls = mutableListOf<String>()
    }
}
