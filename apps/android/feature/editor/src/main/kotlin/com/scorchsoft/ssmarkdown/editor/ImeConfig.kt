package com.scorchsoft.ssmarkdown.editor

import android.os.Build
import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * What the code view tells a keyboard about itself when it connects
 * (robustness.md, "The keyboard connection"):
 *
 * - Multi-line text with sentence caps and the keyboard's own suggestions
 *   and autocorrect left on. Markdown is prose; turning correction off
 *   would make the editor worse for most writing, and a user who wants it
 *   off sets that in their keyboard.
 * - Never fullscreen, never the extracted-text UI: the extract view would
 *   ask for the whole document.
 * - On API 30+, the initial surrounding text (a window around the
 *   selection), so the keyboard's first suggestions are right without a
 *   round trip.
 */
internal object ImeConfig {
    /** What `setInitialSurroundingSubText` keeps anyway; building more is waste. */
    private const val SURROUNDING_TEXT_CHARS = 4_096

    /** Fills [outAttrs] for [session]. */
    fun fill(
        outAttrs: EditorInfo,
        session: EditingSession,
    ) {
        outAttrs.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        outAttrs.imeOptions =
            EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        outAttrs.initialSelStart = session.selectionStart
        outAttrs.initialSelEnd = session.selectionEnd
        outAttrs.initialCapsMode = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val window = session.textWindow(SURROUNDING_TEXT_CHARS)
            outAttrs.setInitialSurroundingSubText(window.text, window.start)
        }
    }
}
