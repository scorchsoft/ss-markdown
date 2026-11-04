package com.scorchsoft.ssmarkdown.nav

import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager

/**
 * Gives the keyboard back to the text after a sheet that had it for its own
 * fields (the Link sheet). The sheet is a window of its own; when it closes,
 * focus returns to the editor's window with the Code view, or the Preview's
 * editable block, still focused and the caret where the edit left it, but
 * Android does not reopen the keyboard for it. This does, once the window
 * has focus again, and only for a view that takes text.
 */
internal object KeyboardReturn {
    /**
     * Puts the keyboard away from whichever view has it. Compose's
     * `SoftwareKeyboardController.hide()` acts only on a Compose text field,
     * and the editors are platform views (the Code view, the Preview's
     * WebView), so on its own it left the keyboard up over the ⋮ menu
     * (Andrew, 2026-11-04). The window's token reaches the IME whatever is
     * focused in it.
     */
    fun hide(anyView: View) {
        val root = anyView.rootView
        root.context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(root.windowToken, 0)
    }

    fun toFocusedText(anyView: View) {
        val root = anyView.rootView
        if (root.hasWindowFocus()) {
            root.post { show(root) }
            return
        }
        root.viewTreeObserver.addOnWindowFocusChangeListener(
            object : ViewTreeObserver.OnWindowFocusChangeListener {
                override fun onWindowFocusChanged(hasFocus: Boolean) {
                    if (!hasFocus) return
                    root.viewTreeObserver.removeOnWindowFocusChangeListener(this)
                    root.post { show(root) }
                }
            },
        )
    }

    private fun show(root: View) {
        val focused = root.findFocus() ?: return
        if (!focused.onCheckIsTextEditor()) return
        focused.context.getSystemService(InputMethodManager::class.java)?.showSoftInput(focused, 0)
    }
}
