package com.scorchsoft.ssmarkdown.editor

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import com.scorchsoft.ssmarkdown.markdown.paste.PasteClip

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

    /**
     * True when "Paste as plain text" would paste something other than
     * Paste: the clip has HTML, or plain text holding Markdown syntax
     * ([PasteClip.isRich]). The toolbar shows the item only then.
     */
    val canPastePlain: Boolean
        get() {
            val description = clipboard?.primaryClipDescription ?: return false
            if (description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)) return true
            if (!description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) return false
            val text =
                clipboard.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.text ?: return false
            return PasteClip.isRich(text.take(PasteClip.RICH_SCAN_CHARS).toString(), null)
        }

    /** Runs [command]; [extend] makes a movement select. Returns true if the command applied. */
    fun run(
        command: EditorCommand,
        extend: Boolean = false,
    ): Boolean {
        if (command !in VERTICAL) goalX = Float.NaN
        // The toolbar and accessibility are not held by the paste's gate, as keys are: finish the paste first.
        // Undo and Redo do it themselves, holding the typing the paste held until after they act.
        if (command in EDITS && command != EditorCommand.UNDO && command != EditorCommand.REDO) view.completePaste()
        return edit(command) ?: move(command, extend)
    }

    /** Clipboard, history and deletion; null when [command] is a movement. */
    private fun edit(command: EditorCommand): Boolean? {
        val session = view.session
        return when (command) {
            EditorCommand.UNDO -> {
                view.undo()
            }

            EditorCommand.REDO -> {
                view.redo()
            }

            EditorCommand.CUT -> {
                copy(cut = true)
            }

            EditorCommand.COPY, EditorCommand.COPY_FORMATTED -> {
                copy(cut = false, formatted = command == EditorCommand.COPY_FORMATTED)
            }

            EditorCommand.PASTE, EditorCommand.PASTE_PLAIN -> {
                paste(plainOnly = command == EditorCommand.PASTE_PLAIN)
            }

            EditorCommand.SELECT_ALL -> {
                applied { session.selectAll() }
            }

            EditorCommand.BACKSPACE -> {
                applied {
                    if (session.hasSelection ||
                        view.listener?.onEditKey(EditKey.Backspace) != true
                    ) {
                        session.pressBackspace()
                    }
                }
            }

            EditorCommand.FORWARD_DELETE -> {
                applied { session.pressForwardDelete() }
            }

            EditorCommand.ENTER -> {
                applied { if (view.listener?.onEditKey(EditKey.Enter) != true) session.pressEnter() }
            }

            else -> {
                null
            }
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

    private fun copy(
        cut: Boolean,
        formatted: Boolean = false,
    ): Boolean {
        val session = view.session
        // "Copy formatted": the host renders the selection (Andrew, 2026-11-04).
        if (formatted) {
            return session.hasSelection &&
                view.listener?.onCopyFormatted(session.selectionStart, session.selectionEnd) == true
        }
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
     * Pastes the clipboard (docs/ux-spec.md, "Copy and paste"). Plain text
     * goes in as copied ([PastedText.clean]): Code is raw Markdown, so a
     * Markdown clip is already what it should be. HTML (a copy from a
     * browser, Gmail, Docs or Word) goes in converted to Markdown
     * ([PasteClip.content]), unless it only wraps the clip's plain text, or
     * the caret is in a code block, where the plain text goes in. A bare web
     * address over selected text outside code links it instead
     * ([PastedText.pasteLink]). [plainOnly] is "Paste as plain text": only
     * the words ([PasteClip.words]), and never a link (decision D9). A
     * URI, an HTML clip or a large text goes through the sliced paste,
     * which reads and converts it off the main thread (docs/edge-cases.md,
     * "Paste of 50 MB"); each paste is one undo step.
     */
    private fun paste(plainOnly: Boolean): Boolean {
        val item = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return false
        val plain = item.text
        val html = item.htmlText?.takeIf { it.isNotBlank() }
        if (plain?.isEmpty() == true) return false
        val raw = !plainOnly && html == null
        if (raw && plain != null && PastedText.pasteLink(view, plain)) return true
        val inCode = PastedText.inCode(view)
        val context = view.context
        view.pasteText(if (raw) plain?.length ?: UNKNOWN_LENGTH else UNKNOWN_LENGTH) {
            // Only a clip with neither text nor HTML (a URI) is read through coerceToText.
            val text = plain ?: if (html == null) item.coerceToText(context) else null
            PastedText.clean(PastedText.forClip(text, html, plainOnly, inCode))
        }
        return true
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
                EditorCommand.PASTE_PLAIN,
                EditorCommand.BACKSPACE,
                EditorCommand.FORWARD_DELETE,
                EditorCommand.ENTER,
            )

        /** Commands that keep the goal x of the previous vertical move. */
        val VERTICAL = setOf(EditorCommand.UP, EditorCommand.DOWN, EditorCommand.PAGE_UP, EditorCommand.PAGE_DOWN)
    }
}
