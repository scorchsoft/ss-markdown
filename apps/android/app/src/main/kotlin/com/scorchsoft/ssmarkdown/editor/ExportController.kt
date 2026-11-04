package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.preview.FormattedCopy
import kotlinx.coroutines.launch

/**
 * ⋮ → Export… as HTML or plain text (Andrew, 2026-11-04: "export as PDF,
 * HTML, or plain text"). Both come from the page's formatted copy of the
 * whole document, as Copy formatted text does: the HTML has its styles
 * inline and its pictures embedded, so it opens anywhere as one file; the
 * text has no Markdown symbols (headings underlined, lists kept, bold and
 * links as words), as it would read pasted into an email. The page renders
 * only on screen, so from Code the Preview is shown while it is read, then
 * Code comes back. PDF stays [preview.PreviewEffects.exportPdf].
 */
class ExportController(
    private val vm: EditorViewModel,
) {
    fun export(format: ExportFormat) {
        val doc = vm.active ?: return
        val fromCode = vm.state.mode == ViewMode.Code
        if (fromCode) vm.onAction(EditorAction.SelectMode(ViewMode.Preview))
        val name = baseName()
        vm.deps.scope.launch {
            val copy = vm.preview.copyFormatted(doc, 0, doc.text.text.lineCount, EXPORT_LIMIT_CHARS)
            if (fromCode && vm.state.mode == ViewMode.Preview && vm.active === doc) {
                vm.onAction(EditorAction.SelectMode(ViewMode.Code))
            }
            if (copy == null || vm.active !== doc) {
                vm.message(MessageKind.ExportFailed)
                return@launch
            }
            val text = if (format == ExportFormat.Html) page(name, copy) else plain(copy)
            vm.emit(EditorEvent.PickExportLocation("$name.${format.extension}", text, format.mimeType))
        }
    }

    private fun baseName(): String =
        vm.state.title
            .substringBeforeLast('.')
            .ifEmpty { "Untitled" }

    internal companion object {
        /** Pictures are embedded, so a page can be large; the page caps its own pictures' share. */
        const val EXPORT_LIMIT_CHARS = 20_000_000

        /** The formatted copy as a page of its own: a title, UTF-8, readable on a phone. */
        fun page(
            title: String,
            copy: FormattedCopy,
        ): String =
            buildString {
                append("<!doctype html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n")
                append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                append("<title>").append(escape(title)).append("</title>\n")
                append("</head>\n<body style=\"max-width:46em;margin:2em auto;padding:0 1em;\">\n")
                append(copy.html)
                append("\n</body>\n</html>\n")
            }

        /** The text, ending in one line break. */
        fun plain(copy: FormattedCopy): String = copy.text.trimEnd() + "\n"

        private fun escape(s: String): String =
            s
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
    }
}
