package com.scorchsoft.ssmarkdown.editor

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager

/**
 * Carries out [EditorCommand]s from the keyboard, the text toolbar and
 * accessibility against the view's session: clipboard, undo/redo,
 * deletion and caret movement. Vertical moves go through the
 * [EditorGeometry] so Up/Down follow visual rows in Wrap mode, and keep
 * a goal x across consecutive presses as every editor does.
 */
internal class CommandRunner(
    private val view: MarkdownCodeView,
    private val geometry: EditorGeometry,
) {
    private val clipboard: ClipboardManager? = view.context.getSystemService(ClipboardManager::class.java)
    private val words = WordFinder()
    private var goalX = Float.NaN

    /** True when the clipboard holds something pasteable as text. */
    val canPaste: Boolean
        get() {
            val description = clipboard?.primaryClipDescription ?: return false
            return description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
                description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
        }

    /** Runs [command]; [extend] makes a movement select. Returns true if the command applied. */
    fun run(
        command: EditorCommand,
        extend: Boolean = false,
    ): Boolean {
        if (command !in VERTICAL) goalX = Float.NaN
        // The toolbar and accessibility are not held by the paste's gate, as keys are: finish the paste first.
        if (command in EDITS && !view.completePaste()) return false
        return edit(command) ?: move(command, extend)
    }

    /** Clipboard, history and deletion; null when [command] is a movement. */
    private fun edit(command: EditorCommand): Boolean? {
        val session = view.session
        return when (command) {
            EditorCommand.UNDO -> session.undo()
            EditorCommand.REDO -> session.redo()
            EditorCommand.CUT -> copy(cut = true)
            EditorCommand.COPY -> copy(cut = false)
            EditorCommand.PASTE -> paste()
            EditorCommand.SELECT_ALL -> applied { session.selectAll() }
            EditorCommand.BACKSPACE -> applied { session.pressBackspace() }
            EditorCommand.FORWARD_DELETE -> applied { session.pressForwardDelete() }
            EditorCommand.ENTER -> applied { if (view.listener?.onEnter() != true) session.pressEnter() }
            else -> null
        }
    }

    private fun move(
        command: EditorCommand,
        extend: Boolean,
    ): Boolean {
        val session = view.session
        when (command) {
            EditorCommand.LEFT -> {
                horizontal(right = false, extend = extend)
            }

            EditorCommand.RIGHT -> {
                horizontal(right = true, extend = extend)
            }

            EditorCommand.WORD_LEFT -> {
                session.moveCaretByWord(forward = false, words = words, extend = extend)
            }

            EditorCommand.WORD_RIGHT -> {
                session.moveCaretByWord(forward = true, words = words, extend = extend)
            }

            EditorCommand.UP -> {
                vertical(-1, extend)
            }

            EditorCommand.DOWN -> {
                vertical(1, extend)
            }

            EditorCommand.PAGE_UP -> {
                vertical(-rowsPerPage(), extend)
            }

            EditorCommand.PAGE_DOWN -> {
                vertical(rowsPerPage(), extend)
            }

            EditorCommand.LINE_START -> {
                session.moveCaretToBoundary(end = false, wholeDocument = false, extend = extend)
            }

            EditorCommand.LINE_END -> {
                session.moveCaretToBoundary(end = true, wholeDocument = false, extend = extend)
            }

            EditorCommand.DOCUMENT_START -> {
                session.moveCaretToBoundary(
                    end = false,
                    wholeDocument = true,
                    extend = extend,
                )
            }

            EditorCommand.DOCUMENT_END -> {
                session.moveCaretToBoundary(end = true, wholeDocument = true, extend = extend)
            }

            else -> {
                return false
            }
        }
        return true
    }

    /**
     * Left or Right: by grapheme in logical order, or in visual order on a
     * line with right-to-left text when the view asks for it ([BidiCaret]).
     * Past a line's visual edge the caret goes to the next line's start or
     * the previous line's end, as it does on any line.
     */
    private fun horizontal(
        right: Boolean,
        extend: Boolean,
    ) {
        val session = view.session
        val visual = if (view.bidiCaretMovement) visualTarget(right) else null
        if (visual !=
            null
        ) {
            session.moveOrExtend(visual, extend)
        } else {
            session.moveCaretByGrapheme(right, extend = extend)
        }
    }

    /** The caret's next offset in visual order, or null when the line needs no bidi. */
    private fun visualTarget(right: Boolean): Int? {
        val text = view.session.document.text
        val position = text.positionAt(view.session.caret)
        val start = text.lineStartOffset(position.line)
        val end = text.lineEndOffset(position.line)
        val line = text.getText(start, end)
        if (!BidiCaret.needsBidi(line)) return null
        val column = BidiCaret.move(line, position.column, right)
        return when {
            column != position.column -> start + column
            right -> if (end < text.length) end + 1 else view.session.caret
            else -> if (start > 0) start - 1 else view.session.caret
        }
    }

    private fun vertical(
        rows: Int,
        extend: Boolean,
    ) {
        val session = view.session
        if (goalX.isNaN()) goalX = geometry.locate(session.caret).x
        session.moveOrExtend(geometry.offsetByRows(session.caret, rows, goalX), extend)
    }

    /** Runs a command that always applies. */
    private inline fun applied(action: () -> Unit): Boolean {
        action()
        return true
    }

    private fun rowsPerPage(): Int = (view.height / view.rowHeight.coerceAtLeast(1) - 1).coerceAtLeast(1)

    private fun copy(cut: Boolean): Boolean {
        val session = view.session
        val length = session.selectionEnd - session.selectionStart
        val tooLarge = length > MAX_CLIPBOARD_CHARS
        if (tooLarge) view.listener?.onCopyTooLarge(length)
        val copied = session.hasSelection && !tooLarge && clipboard != null
        if (copied) {
            val text = session.document.text.getText(session.selectionStart, session.selectionEnd)
            clipboard?.setPrimaryClip(ClipData.newPlainText(null, text))
            if (cut) view.applyEdit(session.selectionStart, session.selectionEnd, "")
        }
        return copied
    }

    /**
     * Pastes the clipboard. Plain text the clip already holds is measured
     * first; a URI or a large text goes through the sliced paste, which
     * reads it off the main thread (docs/edge-cases.md, "Paste of 50 MB").
     */
    private fun paste(): Boolean {
        val item = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val plain = item?.text
        val pasteable = item != null && (plain == null || plain.isNotEmpty())
        if (pasteable) {
            val context = view.context
            view.pasteText(plain?.length ?: UNKNOWN_LENGTH) { plain ?: item.coerceToText(context) }
        }
        return pasteable
    }

    private companion object {
        /**
         * Text over this many chars (about 1 MB as UTF-16) never goes on
         * the clipboard: Binder's limit makes it a crash (robustness.md §5).
         */
        const val MAX_CLIPBOARD_CHARS = 500_000

        /** A clip item with no text yet (a URI): its size is known only once read, off the main thread. */
        const val UNKNOWN_LENGTH = -1

        /** Commands that change the text or the history; they wait for a large paste to be in. */
        val EDITS =
            setOf(
                EditorCommand.UNDO,
                EditorCommand.REDO,
                EditorCommand.CUT,
                EditorCommand.PASTE,
                EditorCommand.BACKSPACE,
                EditorCommand.FORWARD_DELETE,
                EditorCommand.ENTER,
            )

        /** Commands that keep the goal x of the previous vertical move. */
        val VERTICAL = setOf(EditorCommand.UP, EditorCommand.DOWN, EditorCommand.PAGE_UP, EditorCommand.PAGE_DOWN)
    }
}
