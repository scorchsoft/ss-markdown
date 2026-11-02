package com.scorchsoft.ssmarkdown.editor

import android.os.Build
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.SurroundingText
import android.view.inputmethod.TextAttribute
import android.view.inputmethod.TextSnapshot
import androidx.annotation.RequiresApi

/**
 * The keyboard's connection to the code view: a thin adapter from the
 * `InputConnection` verbs onto [EditingSession], which holds the logic and
 * its tests.
 *
 * `BaseInputConnection` is extended (with `fullEditor = true`, so text is
 * never turned into fake key events) only for its defaults on verbs we do
 * not need yet. Every verb whose default would read `getEditable()` is
 * overridden, because there is no `Editable`: the text lives in a piece
 * tree, and copying a 100 MB document into a `SpannableStringBuilder` is
 * exactly what this view exists to avoid.
 *
 * Nothing handed to the keyboard is ever longer than
 * [EditingSession.MAX_IME_WINDOW] chars (robustness.md: without that cap,
 * select-all on a large file is an ANR).
 *
 * While a large paste is going in ([ChunkedPaste]), every edit verb is
 * held by the [gate] and replayed after it, in order, so nothing typed is
 * lost or lands inside the pasted text. `requestCursorUpdates` is answered
 * by the [cursor] reporter. Spike limits, recorded for the real view: no
 * `GET_EXTRACTED_TEXT_MONITOR` updates (extracted-text UI is disabled
 * through `IME_FLAG_NO_EXTRACT_UI`), no context-menu actions.
 */
@Suppress("TooManyFunctions") // InputConnection is a wide interface; each override is one line onto the session.
class CodeInputConnection internal constructor(
    view: View,
    private val session: EditingSession,
    /** Hard and soft keys the session does not handle as text (arrows, Enter, Backspace) go here. */
    private val onKey: (KeyEvent) -> Boolean,
    private val gate: InputGate,
    private val cursor: CursorAnchorReporter?,
) : BaseInputConnection(view, true) {
    /** A connection with no paste gate and no cursor reports, as before either existed. */
    constructor(
        view: View,
        session: EditingSession,
        onKey: (KeyEvent) -> Boolean,
    ) : this(view, session, onKey, InputGate(), null)

    override fun beginBatchEdit(): Boolean = edit { session.beginBatchEdit() }

    override fun endBatchEdit(): Boolean {
        if (!gate.isHolding) return session.endBatchEdit()
        gate.run { session.endBatchEdit() }
        return false
    }

    override fun commitText(
        text: CharSequence?,
        newCursorPosition: Int,
    ): Boolean = edit { session.commitText(text ?: "", newCursorPosition) }

    override fun setComposingText(
        text: CharSequence?,
        newCursorPosition: Int,
    ): Boolean = edit { session.setComposingText(text ?: "", newCursorPosition) }

    override fun setComposingRegion(
        start: Int,
        end: Int,
    ): Boolean = edit { session.setComposingRegion(start, end) }

    override fun finishComposingText(): Boolean = edit { session.finishComposingText() }

    override fun deleteSurroundingText(
        beforeLength: Int,
        afterLength: Int,
    ): Boolean = edit { session.deleteSurroundingText(beforeLength, afterLength) }

    override fun deleteSurroundingTextInCodePoints(
        beforeLength: Int,
        afterLength: Int,
    ): Boolean =
        edit {
            val before = session.textBeforeCursor(beforeLength * 2)
            val after = session.textAfterCursor(afterLength * 2)
            session.deleteSurroundingText(
                charsForCodePoints(before, beforeLength, fromEnd = true),
                charsForCodePoints(after, afterLength, fromEnd = false),
            )
        }

    override fun setSelection(
        start: Int,
        end: Int,
    ): Boolean = edit { session.setSelection(start, end) }

    /** Reports where the caret is drawn, now and/or on every change (handwriting, floating and CJK keyboards). */
    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = cursor?.request(cursorUpdateMode) ?: false

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun requestCursorUpdates(
        cursorUpdateMode: Int,
        cursorUpdateFilter: Int,
    ): Boolean = requestCursorUpdates(cursorUpdateMode or cursorUpdateFilter)

    override fun getTextBeforeCursor(
        n: Int,
        flags: Int,
    ): CharSequence = session.textBeforeCursor(n)

    override fun getTextAfterCursor(
        n: Int,
        flags: Int,
    ): CharSequence = session.textAfterCursor(n)

    override fun getSelectedText(flags: Int): CharSequence? = session.selectedText()

    override fun getCursorCapsMode(reqModes: Int): Int {
        val before = session.textBeforeCursor(CAPS_CONTEXT)
        return TextUtils.getCapsMode(before, before.length, reqModes)
    }

    /** A window around the selection, never the document: offsets in it are relative to [ExtractedText.startOffset]. */
    override fun getExtractedText(
        request: ExtractedTextRequest?,
        flags: Int,
    ): ExtractedText {
        val hint = request?.hintMaxChars?.takeIf { it > 0 } ?: EditingSession.MAX_IME_WINDOW
        val window = session.textWindow(hint)
        return ExtractedText().apply {
            text = window.text
            startOffset = window.start
            partialStartOffset = -1
            partialEndOffset = -1
            selectionStart = session.selectionStart - window.start
            selectionEnd = session.selectionEnd - window.start
            this.flags = 0
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun getSurroundingText(
        beforeLength: Int,
        afterLength: Int,
        flags: Int,
    ): SurroundingText {
        val before = session.textBeforeCursor(beforeLength)
        val selected = session.selectedText() ?: ""
        val after = session.textAfterCursor(afterLength)
        val text =
            buildString(before.length + selected.length + after.length) {
                append(before)
                append(selected)
                append(after)
            }
        val selectionStart = before.length
        return SurroundingText(
            text,
            selectionStart,
            selectionStart + selected.length,
            session.selectionStart - before.length,
        )
    }

    /** Not supported yet: the default would snapshot the empty dummy `Editable` and mislead the keyboard. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun takeSnapshot(): TextSnapshot? = null

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun replaceText(
        start: Int,
        end: Int,
        text: CharSequence,
        newCursorPosition: Int,
        textAttribute: TextAttribute?,
    ): Boolean =
        edit {
            session.beginBatchEdit()
            session.finishComposingText()
            session.setSelection(start, end)
            session.commitText(text, newCursorPosition)
            session.endBatchEdit()
        }

    /** Keys from soft keyboards (Backspace with nothing composing, Enter) and from `performEditorAction`. */
    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (!gate.isHolding) return onKey(event)
        gate.run { onKey(event) }
        return true
    }

    override fun closeConnection() {
        cursor?.stop()
        gate.run { session.finishComposingText() }
        super.closeConnection()
    }

    /** Runs [action] now, or after a running paste; the keyboard is told it was accepted either way. */
    private inline fun edit(crossinline action: () -> Unit): Boolean {
        gate.run { action() }
        return true
    }

    private companion object {
        /** Enough text before the caret to see a sentence end for auto-capitalisation. */
        const val CAPS_CONTEXT = 64

        /** How many chars at one end of [text] make up [codePoints] code points. */
        fun charsForCodePoints(
            text: CharSequence,
            codePoints: Int,
            fromEnd: Boolean,
        ): Int {
            var chars = 0
            var remaining = codePoints
            while (remaining > 0 && chars < text.length) {
                val index = if (fromEnd) text.length - 1 - chars else chars
                val pairs =
                    if (fromEnd) {
                        index > 0 && text[index].isLowSurrogate() && text[index - 1].isHighSurrogate()
                    } else {
                        index + 1 < text.length && text[index].isHighSurrogate() && text[index + 1].isLowSurrogate()
                    }
                chars += if (pairs) 2 else 1
                remaining--
            }
            return chars
        }
    }
}
