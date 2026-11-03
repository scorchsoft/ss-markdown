package com.scorchsoft.ssmarkdown.editor

import android.view.KeyCharacterMap
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
    private val deadKeys = DeadKeys()

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
                if (down) deadKeys.typed(event.unicodeChar)?.let(::type)
                true
            }

            else -> {
                false
            }
        }
    }

    /** A typed char; a closer is offered to the host first (it may take an indent unit off a code line). */
    private fun type(text: String) {
        if (text in CLOSERS && listener()?.onCloser(text) == true) return
        session.typeKey(text)
    }

    /** Offers a shortcut to the host; an unclaimed plain Tab types a tab. */
    private fun onShortcut(
        event: KeyEvent,
        down: Boolean,
    ): Boolean {
        val handled = down && listener()?.onShortcut(event.keyCode, event.isCtrlPressed, event.isShiftPressed) == true
        val plainTab = event.keyCode == KeyEvent.KEYCODE_TAB && !event.isCtrlPressed
        if (!handled && plainTab && down) session.typeKey("\t")
        return handled || plainTab
    }
}

/** The chars offered to [EditorListener.onCloser]. */
internal val CLOSERS = setOf("}", "]", ")")

/**
 * A hardware keyboard's dead keys (´ then e is é on international layouts). The accent arrives as a
 * code point with [KeyCharacterMap.COMBINING_ACCENT] set, which is not a char: turning it into one
 * threw, and the app crashed on the first accent typed (edge-cases "Dead key on a hardware keyboard").
 */
internal class DeadKeys {
    private var accent = 0

    /** The text a key with [unicodeChar] types, or null when it only starts an accent. */
    fun typed(unicodeChar: Int): String? {
        if (unicodeChar and KeyCharacterMap.COMBINING_ACCENT != 0) {
            val mark = unicodeChar and KeyCharacterMap.COMBINING_ACCENT_MASK
            // Two accents in a row: the first is typed on its own, as TextView does.
            val pending = accent
            accent = mark
            return if (pending != 0) String(Character.toChars(pending)) else null
        }
        val pending = accent
        accent = 0
        if (pending == 0) return String(Character.toChars(unicodeChar))
        val composed = KeyEvent.getDeadChar(pending, unicodeChar)
        return if (composed != 0) {
            String(Character.toChars(composed))
        } else {
            String(Character.toChars(pending)) + String(Character.toChars(unicodeChar))
        }
    }
}
