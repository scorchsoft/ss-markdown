package com.scorchsoft.ssmarkdown.editor

import android.view.KeyEvent
import com.scorchsoft.ssmarkdown.find.FindAction
import com.scorchsoft.ssmarkdown.navigator.NavigatorAction

/**
 * Hardware-keyboard shortcuts the editor view does not own: Ctrl+S save,
 * Ctrl+F find, Ctrl+K / Ctrl+P the Navigator, Ctrl+Home the top
 * (ux-spec "Navigator", "Where am I?"). Undo, redo and the clipboard are
 * the view's own.
 */
object Shortcuts {
    /** The action for a key, or null if it is not ours. */
    fun actionFor(
        keyCode: Int,
        ctrl: Boolean,
        shift: Boolean,
    ): EditorAction? {
        if (!ctrl) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_S -> if (shift) EditorAction.ChooseMenuItem(MenuItem.SaveAs) else EditorAction.SaveTapped
            KeyEvent.KEYCODE_F -> EditorAction.Find(FindAction.Open)
            KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_P -> EditorAction.TitleTapped
            KeyEvent.KEYCODE_MOVE_HOME -> EditorAction.Navigator(NavigatorAction.TopOfDocument)
            else -> null
        }
    }
}
