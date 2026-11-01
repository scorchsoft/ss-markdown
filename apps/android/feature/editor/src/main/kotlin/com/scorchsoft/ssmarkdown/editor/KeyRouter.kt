package com.scorchsoft.ssmarkdown.editor

import android.view.KeyEvent

/**
 * Where a key goes, for hardware keys and the soft keyboard's
 * `sendKeyEvent` alike: an [EditorCommand] when [KeyCommands] maps it, the
 * host for shortcuts it owns (Ctrl+S, Ctrl+F, Ctrl+B...), a tab char for an
 * unclaimed Tab, and the typed char otherwise. Acts on key down; reports
 * key up as handled for the same keys so nothing else sees half a press.
 */
internal class KeyRouter(
    private val session: EditingSession,
    private val commands: CommandRunner,
    private val listener: () -> EditorListener?,
) {
    /** Handles [event]; returns true when the editor consumed it. */
    fun onKey(event: KeyEvent): Boolean {
        val action = KeyCommands.actionFor(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        return when {
            action != null -> {
                if (down) commands.run(action.command, action.extend)
                true
            }

            event.isCtrlPressed || event.keyCode == KeyEvent.KEYCODE_TAB -> {
                onShortcut(event, down)
            }

            event.unicodeChar != 0 -> {
                if (down) session.commitText(String(Character.toChars(event.unicodeChar)), 1)
                true
            }

            else -> {
                false
            }
        }
    }

    /** Offers a shortcut to the host; an unclaimed plain Tab types a tab. */
    private fun onShortcut(
        event: KeyEvent,
        down: Boolean,
    ): Boolean {
        val handled = down && listener()?.onShortcut(event.keyCode, event.isCtrlPressed, event.isShiftPressed) == true
        val plainTab = event.keyCode == KeyEvent.KEYCODE_TAB && !event.isCtrlPressed
        if (!handled && plainTab && down) session.commitText("\t", 1)
        return handled || plainTab
    }
}
