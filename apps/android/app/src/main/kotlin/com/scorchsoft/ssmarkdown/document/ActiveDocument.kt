package com.scorchsoft.ssmarkdown.document

import com.scorchsoft.ssmarkdown.editor.EditorListener
import com.scorchsoft.ssmarkdown.editor.LineChange
import com.scorchsoft.ssmarkdown.editor.MarkdownCodeView
import com.scorchsoft.ssmarkdown.files.document.OpenDocument
import com.scorchsoft.ssmarkdown.markdown.edit.DocumentStyle
import com.scorchsoft.ssmarkdown.text.TextDocument

/**
 * What the editor screen hears from the document on screen. The view
 * model implements it; every call is on the main thread.
 */
interface ActiveDocumentListener {
    /** The text changed by typing, a command or undo. */
    fun onEdited() = Unit

    /** The selection settled. */
    fun onSelectionChanged(
        start: Int,
        end: Int,
    ) = Unit

    /** The source line at the top of the Code pane changed. */
    fun onAnchorLineChanged(line: Int) = Unit

    /** The Code pane's zoom changed; [pinching] while fingers are down. */
    fun onZoomChanged(
        percent: Int,
        pinching: Boolean,
    ) = Unit

    /** A copy was refused for Binder size; offer Share as file. */
    fun onCopyTooLarge(chars: Int) = Unit

    /** A hardware shortcut the editor does not own (Ctrl+S, Ctrl+F, Ctrl+K…). */
    fun onShortcut(
        keyCode: Int,
        ctrl: Boolean,
        shift: Boolean,
    ): Boolean = false
}

/**
 * The document on screen, bound to its view: the bridge between
 * `MarkdownCodeView` (which edits the [TextDocument] directly) and the
 * store's [OpenDocument] (which must journal every edit before anything
 * else happens — the never-lose-work promise at the keystroke level).
 *
 * One instance per [TextDocument]: when the store replaces the buffer
 * (a reload or a resolved conflict) the session builds a new one, so the
 * old undo stack and snapshots never reach into new content.
 */
class ActiveDocument(
    val open: OpenDocument,
    private val listener: ActiveDocumentListener,
) : EditorListener {
    /** The buffer this session is bound to. */
    val text: TextDocument = open.document

    /** Headings for the Navigator, breadcrumb and Copy section. */
    val outline: OutlineTracker = OutlineTracker(text.text)

    /** The file's own Markdown dialect, detected once (ux-spec "Editing in Preview"). */
    val style: DocumentStyle = DocumentStyle.detect(text.snapshot())

    private val recorder = EditRecorder(text.snapshot())
    private var view: MarkdownCodeView? = null
    private val buffer = BufferSurface(open) { afterBufferEdit() }

    /** Where programmatic edits go: the Code view while it is attached, else the buffer. */
    val surface: EditorSurface get() = view?.let(::CodeViewSurface) ?: buffer

    /** The Code view is on screen (or moved by `movableContentOf`; it is the same instance). */
    fun attach(codeView: MarkdownCodeView) {
        view = codeView
    }

    /** The Code view left the composition. */
    fun detach(codeView: MarkdownCodeView) {
        if (view === codeView) view = null
    }

    override fun onTextEdited(
        change: LineChange,
        version: Long,
    ) {
        val op = recorder.record(text.text, change.firstLine, change.removedBreaks, change.addedBreaks)
        // The journal starts lazily on the first edit (JournalLink). The view has
        // already applied this edit, so the op is recorded immediately after.
        open.prepareForEdit()
        open.recordApplied(listOf(op))
        outline.onLinesChanged(change.firstLine, change.removedBreaks, change.addedBreaks)
        listener.onEdited()
    }

    override fun onSelectionChanged(
        start: Int,
        end: Int,
    ) = listener.onSelectionChanged(start, end)

    override fun onAnchorLineChanged(line: Int) = listener.onAnchorLineChanged(line)

    override fun onZoomChanged(
        percent: Int,
        pinching: Boolean,
    ) = listener.onZoomChanged(percent, pinching)

    override fun onCopyTooLarge(chars: Int) = listener.onCopyTooLarge(chars)

    override fun onShortcut(
        keyCode: Int,
        ctrl: Boolean,
        shift: Boolean,
    ): Boolean = listener.onShortcut(keyCode, ctrl, shift)

    private fun afterBufferEdit() {
        // Buffer edits are journalled by OpenDocument itself; only derived state needs refreshing.
        recorder.reset(text.snapshot())
        outline.invalidateAll()
        listener.onEdited()
    }
}
