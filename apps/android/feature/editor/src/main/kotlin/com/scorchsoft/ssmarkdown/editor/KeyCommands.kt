package com.scorchsoft.ssmarkdown.editor

import android.view.KeyEvent

/** What a key press means to the editor, independent of how it was typed. */
enum class EditorCommand {
    UNDO,
    REDO,
    CUT,
    COPY,
    PASTE,

    /** "Paste as plain text": only the words, without Markdown or HTML (EB-CLIP-14). */
    PASTE_PLAIN,
    SELECT_ALL,
    BACKSPACE,
    FORWARD_DELETE,
    ENTER,
    LEFT,
    RIGHT,
    WORD_LEFT,
    WORD_RIGHT,
    UP,
    DOWN,
    PAGE_UP,
    PAGE_DOWN,
    LINE_START,
    LINE_END,
    DOCUMENT_START,
    DOCUMENT_END,
}

/** A key's [command] and whether Shift extends the selection while it moves the caret. */
data class KeyAction(
    val command: EditorCommand,
    val extend: Boolean = false,
)

/**
 * The hardware-keyboard map (edge-cases: "Hardware keyboard or
 * Chromebook"): arrows, Home/End, Page Up/Down, Shift to select, Ctrl for
 * words and the clipboard, Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y for undo and
 * redo, Ctrl+Shift+V to paste as plain text. A pure function of key code and modifiers, so the whole table is
 * tested on the host. Shortcuts that are the host's business (Ctrl+S,
 * Ctrl+F, Ctrl+B, Tab) map to null and are offered to the host instead.
 */
object KeyCommands {
    /** The action for [keyCode] with the given modifiers, or null when the editor does not own the key. */
    fun actionFor(
        keyCode: Int,
        ctrl: Boolean,
        shift: Boolean,
    ): KeyAction? =
        if (ctrl) {
            ctrlAction(keyCode, shift)
        } else {
            plainCommand(keyCode)?.let { KeyAction(it, extend = shift && it in MOVEMENTS) }
        }

    /** The action for [event], reading its modifier state. */
    fun actionFor(event: KeyEvent): KeyAction? = actionFor(event.keyCode, event.isCtrlPressed, event.isShiftPressed)

    private fun ctrlAction(
        keyCode: Int,
        shift: Boolean,
    ): KeyAction? {
        val command =
            when (keyCode) {
                KeyEvent.KEYCODE_Z -> if (shift) EditorCommand.REDO else EditorCommand.UNDO
                KeyEvent.KEYCODE_V -> if (shift) EditorCommand.PASTE_PLAIN else EditorCommand.PASTE
                else -> CTRL_KEYS[keyCode]
            }
        return command?.let { KeyAction(it, extend = shift && it in MOVEMENTS) }
    }

    private fun plainCommand(keyCode: Int): EditorCommand? = PLAIN_KEYS[keyCode]

    /** Keys pressed with Ctrl (Ctrl+Z and Ctrl+V are special-cased: Shift makes them redo and plain paste). */
    private val CTRL_KEYS =
        mapOf(
            KeyEvent.KEYCODE_Y to EditorCommand.REDO,
            KeyEvent.KEYCODE_X to EditorCommand.CUT,
            KeyEvent.KEYCODE_C to EditorCommand.COPY,
            KeyEvent.KEYCODE_A to EditorCommand.SELECT_ALL,
            KeyEvent.KEYCODE_DPAD_LEFT to EditorCommand.WORD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to EditorCommand.WORD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP to EditorCommand.UP,
            KeyEvent.KEYCODE_DPAD_DOWN to EditorCommand.DOWN,
            KeyEvent.KEYCODE_MOVE_HOME to EditorCommand.DOCUMENT_START,
            KeyEvent.KEYCODE_MOVE_END to EditorCommand.DOCUMENT_END,
        )

    /** Keys without Ctrl. */
    private val PLAIN_KEYS =
        mapOf(
            KeyEvent.KEYCODE_DEL to EditorCommand.BACKSPACE,
            KeyEvent.KEYCODE_FORWARD_DEL to EditorCommand.FORWARD_DELETE,
            KeyEvent.KEYCODE_ENTER to EditorCommand.ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER to EditorCommand.ENTER,
            KeyEvent.KEYCODE_DPAD_LEFT to EditorCommand.LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to EditorCommand.RIGHT,
            KeyEvent.KEYCODE_DPAD_UP to EditorCommand.UP,
            KeyEvent.KEYCODE_DPAD_DOWN to EditorCommand.DOWN,
            KeyEvent.KEYCODE_PAGE_UP to EditorCommand.PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN to EditorCommand.PAGE_DOWN,
            KeyEvent.KEYCODE_MOVE_HOME to EditorCommand.LINE_START,
            KeyEvent.KEYCODE_MOVE_END to EditorCommand.LINE_END,
            KeyEvent.KEYCODE_CUT to EditorCommand.CUT,
            KeyEvent.KEYCODE_COPY to EditorCommand.COPY,
            KeyEvent.KEYCODE_PASTE to EditorCommand.PASTE,
        )

    /** Commands that move the caret, and so select when Shift is held. */
    private val MOVEMENTS =
        setOf(
            EditorCommand.LEFT,
            EditorCommand.RIGHT,
            EditorCommand.WORD_LEFT,
            EditorCommand.WORD_RIGHT,
            EditorCommand.UP,
            EditorCommand.DOWN,
            EditorCommand.PAGE_UP,
            EditorCommand.PAGE_DOWN,
            EditorCommand.LINE_START,
            EditorCommand.LINE_END,
            EditorCommand.DOCUMENT_START,
            EditorCommand.DOCUMENT_END,
        )
}
