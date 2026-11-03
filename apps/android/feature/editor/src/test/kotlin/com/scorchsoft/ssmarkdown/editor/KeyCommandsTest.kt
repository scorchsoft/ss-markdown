package com.scorchsoft.ssmarkdown.editor

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyCommandsTest {
    private fun action(
        keyCode: Int,
        ctrl: Boolean = false,
        shift: Boolean = false,
    ) = KeyCommands.actionFor(keyCode, ctrl, shift)

    @Test
    fun `ctrl shortcuts route to the clipboard and the undo stack`() {
        assertEquals(KeyAction(EditorCommand.UNDO), action(KeyEvent.KEYCODE_Z, ctrl = true))
        assertEquals(KeyAction(EditorCommand.REDO), action(KeyEvent.KEYCODE_Z, ctrl = true, shift = true))
        assertEquals(KeyAction(EditorCommand.REDO), action(KeyEvent.KEYCODE_Y, ctrl = true))
        assertEquals(KeyAction(EditorCommand.SELECT_ALL), action(KeyEvent.KEYCODE_A, ctrl = true))
        assertEquals(KeyAction(EditorCommand.COPY), action(KeyEvent.KEYCODE_C, ctrl = true))
        assertEquals(KeyAction(EditorCommand.CUT), action(KeyEvent.KEYCODE_X, ctrl = true))
        assertEquals(KeyAction(EditorCommand.PASTE), action(KeyEvent.KEYCODE_V, ctrl = true))
        assertEquals(KeyAction(EditorCommand.PASTE_PLAIN), action(KeyEvent.KEYCODE_V, ctrl = true, shift = true))
    }

    @Test
    fun `shift turns movement into selection`() {
        assertEquals(KeyAction(EditorCommand.LEFT, extend = true), action(KeyEvent.KEYCODE_DPAD_LEFT, shift = true))
        assertEquals(KeyAction(EditorCommand.DOWN, extend = true), action(KeyEvent.KEYCODE_DPAD_DOWN, shift = true))
        assertEquals(
            KeyAction(EditorCommand.WORD_RIGHT, extend = true),
            action(KeyEvent.KEYCODE_DPAD_RIGHT, ctrl = true, shift = true),
        )
        assertEquals(
            KeyAction(EditorCommand.DOCUMENT_END, extend = true),
            action(KeyEvent.KEYCODE_MOVE_END, ctrl = true, shift = true),
        )
    }

    @Test
    fun `shift never makes an edit extend`() {
        assertEquals(KeyAction(EditorCommand.BACKSPACE), action(KeyEvent.KEYCODE_DEL, shift = true))
        assertEquals(KeyAction(EditorCommand.ENTER), action(KeyEvent.KEYCODE_ENTER, shift = true))
    }

    @Test
    fun `host shortcuts and printable keys are not the editor's`() {
        assertNull(action(KeyEvent.KEYCODE_S, ctrl = true))
        assertNull(action(KeyEvent.KEYCODE_F, ctrl = true))
        assertNull(action(KeyEvent.KEYCODE_B, ctrl = true))
        assertNull(action(KeyEvent.KEYCODE_TAB))
        assertNull(action(KeyEvent.KEYCODE_A))
    }

    @Test
    fun `home end and page keys`() {
        assertEquals(KeyAction(EditorCommand.LINE_START), action(KeyEvent.KEYCODE_MOVE_HOME))
        assertEquals(KeyAction(EditorCommand.DOCUMENT_START), action(KeyEvent.KEYCODE_MOVE_HOME, ctrl = true))
        assertEquals(KeyAction(EditorCommand.PAGE_DOWN), action(KeyEvent.KEYCODE_PAGE_DOWN))
    }
}
