package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.find.FindAction
import com.scorchsoft.ssmarkdown.find.FindEngine
import com.scorchsoft.ssmarkdown.find.FindUiState
import com.scorchsoft.ssmarkdown.history.versions.VersionKind
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.text.EditOp

/**
 * Find & replace (ux-spec "Find & replace"). Searches run over a snapshot
 * of the buffer; the current match is selected in the editor. Replace all
 * records a History checkpoint first and applies as **one** undo step.
 */
class FindController(
    private val host: EditorHost,
) {
    /** A find-bar action; the reducer has already applied its pure part. */
    fun onAction(action: FindAction) {
        when (action) {
            FindAction.Close -> host.active?.clearHighlights()
            FindAction.Open, is FindAction.Query -> search()
            FindAction.ToggleMatchCase, FindAction.ToggleWholeWord, FindAction.ToggleRegex -> search()
            FindAction.Next, FindAction.Previous -> showCurrent().also { highlight() }
            FindAction.ReplaceOne -> replaceOne()
            FindAction.ReplaceAll -> replaceAll()
            else -> Unit
        }
    }

    /** The text changed: the match list follows it while the bar is open. */
    fun onTextChanged() {
        if (host.state.find != null) search(select = false)
    }

    private fun search(select: Boolean = true) {
        val find = host.state.find ?: return
        val doc = host.active ?: return
        val result = FindEngine.find(doc.text.snapshot(), find.query, find.options)
        val caret = doc.surface.selection.start
        host.update { it.copy(find = it.find?.withResult(result, caret)) }
        highlight()
        if (select) showCurrent()
    }

    /** Every match drawn in the Code view, the current one stronger (ux-spec "Find & replace"). */
    private fun highlight() {
        val find = host.state.find ?: return
        val starts = IntArray(find.matches.size) { find.matches[it].start }
        val ends = IntArray(find.matches.size) { find.matches[it].end }
        host.active?.highlight(starts, ends, find.current)
    }

    private fun showCurrent() {
        val match = host.state.find?.currentMatch ?: return
        host.active?.surface?.select(match.start, match.end)
    }

    @Suppress("ReturnCount") // No bar, no match and no document each end it.
    private fun replaceOne() {
        val find = host.state.find ?: return
        val match = find.currentMatch ?: return
        val doc = host.active ?: return
        val snapshot = doc.text.snapshot()
        val replacement = FindEngine.replacementFor(snapshot, match, find.query, find.replacement, find.options)
        val op = EditOp(match.start, snapshot.subSequence(match.start, match.end), replacement)
        doc.surface.apply(listOf(op), TextSelection.caret(match.start + replacement.length))
        search()
    }

    @Suppress("ReturnCount") // No bar, no document and no matches each end it.
    private fun replaceAll() {
        val find: FindUiState = host.state.find ?: return
        val doc = host.active ?: return
        if (find.matches.isEmpty()) return
        doc.surface.flushComposing()
        val snapshot = doc.text.snapshot()
        host.deps.history.record(doc.open.uri, snapshot, VersionKind.Checkpoint, LABEL_BEFORE_REPLACE_ALL)
        val ops =
            find.matches.asReversed().map { match ->
                val replacement = FindEngine.replacementFor(snapshot, match, find.query, find.replacement, find.options)
                EditOp(match.start, snapshot.subSequence(match.start, match.end), replacement)
            }
        doc.surface.apply(ops, TextSelection.caret(find.matches.first().start))
        host.message(MessageKind.ReplacedAll, find.matches.size.toString())
        search(select = false)
    }

    private companion object {
        /** The History label of the checkpoint before a replace-all; shown as recorded. */
        const val LABEL_BEFORE_REPLACE_ALL = "Before replace all"
    }
}
