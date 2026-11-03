package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.document.ActiveDocument
import com.scorchsoft.ssmarkdown.editor.SaveController.Companion.CLIPBOARD_LIMIT_CHARS
import com.scorchsoft.ssmarkdown.settings.CopyFormat
import kotlinx.coroutines.launch

/**
 * Copy, for the whole document and for one section (ux-spec "Top bar" and
 * "Navigator"): the Markdown source exactly, or formatted text for mail and
 * document editors. The chooser lists the last-used choice first, and
 * long-press Copy repeats it at once.
 *
 * Formatted text is the Preview page's own rendering (inline light styles,
 * pictures and diagrams embedded within the clipboard's budget, a plain-text
 * twin), so it needs a page that holds this document and is drawn. From Code
 * the Preview comes into view while it renders and Code returns afterwards,
 * as Export PDF does (edge-cases "Export PDF from Code did nothing"). If the
 * page cannot answer, or another document opens first, the requested
 * document's Markdown is copied instead and the message says so: a copy is
 * never silent and never of the wrong file.
 */
class CopyController(
    private val vm: EditorViewModel,
) {
    /** The header's Copy, ⋮ → Copy as…: the chooser, last-used choice first. */
    fun openChooser() {
        vm.active?.surface?.flushComposing()
        vm.update { it.copy(overlay = EditorOverlay.CopyChooser(vm.deps.settings.settings.value.copyFormat)) }
    }

    /** Copies the whole document as [format] and remembers the choice. */
    fun copyDocument(format: CopyFormat) {
        val doc = vm.active ?: return
        doc.surface.flushComposing()
        vm.update { it.copy(overlay = null) }
        remember(format)
        val text = doc.text.snapshot()
        when (format) {
            CopyFormat.Markdown -> copyMarkdown(text, MessageKind.Copied)
            CopyFormat.Formatted -> copyFormatted(doc, text, 0, doc.text.text.lineCount, section = false)
        }
    }

    /** The section menu's Copy rows: the section under the heading at [line] as [format]. */
    fun copySection(
        line: Int,
        format: CopyFormat,
    ) {
        val doc = vm.active ?: return
        val range = doc.outline.sectionRange(line) ?: return
        val tree = doc.text.text
        val text = tree.getText(tree.lineStartOffset(range.first), tree.lineEndOffset(range.last))
        vm.update { it.copy(overlay = null) }
        remember(format)
        when (format) {
            CopyFormat.Markdown -> copyMarkdown(text, MessageKind.SectionCopied)
            CopyFormat.Formatted -> copyFormatted(doc, text, range.first, range.last + 1, section = true)
        }
    }

    private fun remember(format: CopyFormat) {
        if (vm.deps.settings.settings.value.copyFormat != format) {
            vm.deps.settings.update { it.copy(copyFormat = format) }
            vm.refresh()
        }
    }

    /** Markdown as text; too big for Binder, it goes as a file instead (robustness §5). */
    private fun copyMarkdown(
        text: CharSequence,
        done: MessageKind?,
    ) {
        if (text.length > CLIPBOARD_LIMIT_CHARS) {
            vm.emit(EditorEvent.ShareAsFile(vm.saves.shareName(), text))
        } else {
            vm.emit(EditorEvent.CopyText(text))
            done?.let(vm::message)
        }
    }

    private fun copyFormatted(
        doc: ActiveDocument,
        markdown: CharSequence,
        fromLine: Int,
        toLine: Int,
        section: Boolean,
    ) {
        val fromCode = vm.state.mode == ViewMode.Code
        if (fromCode) vm.onAction(EditorAction.SelectMode(ViewMode.Preview))
        vm.deps.scope.launch {
            val copy = vm.preview.copyFormatted(doc, fromLine, toLine, CLIPBOARD_LIMIT_CHARS)
            // Back to Code, unless the user has moved on to another view or file meanwhile.
            if (fromCode && vm.state.mode == ViewMode.Preview && vm.active === doc) {
                vm.onAction(EditorAction.SelectMode(ViewMode.Code))
            }
            when {
                copy == null -> {
                    copyMarkdown(markdown, null)
                    if (markdown.length <= CLIPBOARD_LIMIT_CHARS) vm.message(MessageKind.FormattedFailed)
                }

                else -> {
                    vm.emit(EditorEvent.CopyFormatted(copy))
                    vm.message(
                        when {
                            copy.truncated -> MessageKind.CopiedFormattedShortened
                            section -> MessageKind.SectionCopiedFormatted
                            else -> MessageKind.CopiedFormatted
                        },
                    )
                }
            }
        }
    }
}
