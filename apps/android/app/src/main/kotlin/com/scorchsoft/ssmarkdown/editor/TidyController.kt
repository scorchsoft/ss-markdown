package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.history.versions.VersionKind
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.markdown.tidy.MarkdownTidy
import com.scorchsoft.ssmarkdown.markdown.tidy.TidyEdit
import com.scorchsoft.ssmarkdown.markdown.tidy.TidyOptions
import com.scorchsoft.ssmarkdown.markdown.tidy.TidyPlan
import com.scorchsoft.ssmarkdown.text.EditOp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tidy (Andrew, 2026-11-04: "a button … that can tidy … in a safe way …
 * tell you how tidy mode works, and then say, are you sure"). ⋮'s Tidy icon
 * works out the plan off the main thread and shows [EditorOverlay.TidyPrompt]:
 * how Tidy works and what it would change in this file. Tidy there records a
 * History checkpoint, then applies the plan as **one** undo step, as Replace
 * all does. The rules and their safety guard are core/markdown's
 * [MarkdownTidy]: nothing they change alters how the document reads.
 */
class TidyController(
    private val host: EditorHost,
) {
    /** The plan the prompt shows, and the text it was made for. */
    private var planned: Pair<CharSequence, TidyPlan>? = null

    /** Works out what Tidy would change, then asks. A read-only file is never tidied. */
    fun ask() {
        val doc = host.active ?: return
        if (doc.open.readOnly != null) {
            host.message(MessageKind.EditRefused)
            return
        }
        doc.surface.flushComposing()
        val snapshot = doc.text.snapshot()
        host.deps.scope.launch {
            val options = options()
            val plan = withContext(host.deps.compute) { MarkdownTidy.plan(snapshot.toString(), options) }
            // The file on screen changed while the plan was made: it would be for other text.
            if (host.active !== doc || !sameText(doc.text.snapshot(), snapshot)) return@launch
            planned = snapshot to plan
            host.update { it.copy(overlay = EditorOverlay.TidyPrompt(plan.counts)) }
        }
    }

    /** Applies the plan the prompt showed, if the text is still what it was made for. */
    fun apply() {
        val (text, plan) = planned ?: return
        planned = null
        val doc = host.active ?: return
        doc.surface.flushComposing()
        val now = doc.text.snapshot()
        if (plan.isEmpty || !sameText(now, text)) return
        host.deps.history.record(doc.open.uri, now, VersionKind.Checkpoint, LABEL_BEFORE_TIDY)
        val ops = plan.edits.asReversed().map { EditOp(it.start, now.subSequence(it.start, it.end), it.replacement) }
        val selection = doc.surface.selection
        doc.surface.apply(ops, TextSelection(follow(selection.start, plan.edits), follow(selection.end, plan.edits)))
        host.message(MessageKind.Tidied)
    }

    /** Settings → Tidy's conventions. */
    private fun options(): TidyOptions {
        val s = host.deps.settings.settings.value
        return TidyOptions(countUpRepeatedLists = s.tidyCountUpLists, collapseBlankLines = s.tidyCollapseBlankLines)
    }

    private fun sameText(
        a: CharSequence,
        b: CharSequence,
    ): Boolean = a === b || a.toString() == b.toString()

    private companion object {
        /** The History label of the checkpoint before Tidy; shown as recorded. */
        const val LABEL_BEFORE_TIDY = "Before tidy"

        /** Where [offset] lands once [edits] (ascending, in the old text's offsets) are applied. */
        fun follow(
            offset: Int,
            edits: List<TidyEdit>,
        ): Int {
            var shift = 0
            for (e in edits) {
                if (e.start >= offset) break
                // An edit before the offset shifts it; one around it puts it at the end of what replaced it.
                shift +=
                    if (e.end <= offset) {
                        e.replacement.length - (e.end - e.start)
                    } else {
                        e.start + e.replacement.length - offset
                    }
            }
            return offset + shift
        }
    }
}
