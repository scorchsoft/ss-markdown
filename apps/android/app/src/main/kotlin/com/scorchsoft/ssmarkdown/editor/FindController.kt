package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.find.CancellableText
import com.scorchsoft.ssmarkdown.find.FindAction
import com.scorchsoft.ssmarkdown.find.FindEngine
import com.scorchsoft.ssmarkdown.find.FindOptions
import com.scorchsoft.ssmarkdown.find.FindResult
import com.scorchsoft.ssmarkdown.find.FindUiState
import com.scorchsoft.ssmarkdown.history.versions.VersionKind
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.text.EditOp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Find & replace (ux-spec "Find & replace"). Replace all records a
 * History checkpoint first and applies as **one** undo step.
 *
 * Searching never runs on the main thread (performance review PERF-8): a
 * no-match query over 20 MB is a 184 ms scan on the host, and it used to
 * run on every keystroke while the bar was open. Now each search reads an
 * immutable snapshot on [EditorDependencies.compute]; a newer query or
 * edit cancels the one in flight, even mid-scan ([CancellableText]); and
 * edits wait [EDIT_DEBOUNCE_MILLIS] so a burst of typing costs one search.
 * Until it lands, the Code view keeps shifting the old highlights with
 * each edit, and replacing waits for the fresh matches rather than act on
 * stale offsets. The match count is capped at [FindEngine.MAX_MATCHES].
 *
 * Next and Previous move every pane on screen to the match (edge-cases
 * "Find next does not move"): the Code pane selects it, so typing replaces
 * it, and scrolls the least distance that shows it, wrapped rows included;
 * in Preview and Split the page paints every match on the rendered text,
 * marks the current one and scrolls to it. The page hears nothing while
 * only Code is on screen; a switch to Preview or Split sends the matches.
 */
@Suppress("TooManyFunctions") // Search, steps, both panes and both replaces: each small, over one state.
class FindController(
    private val host: EditorHost,
) {
    private var job: Job? = null

    /** Searches started, cancelled ones included; the bounded-work tests count them. */
    var searchesStarted: Int = 0
        private set

    /** A find-bar action; the reducer has already applied its pure part. */
    fun onAction(action: FindAction) {
        when (action) {
            FindAction.Close -> {
                cancel()
                host.active?.clearHighlights()
            }

            FindAction.Open, is FindAction.Query -> {
                search()
            }

            FindAction.ToggleMatchCase, FindAction.ToggleWholeWord, FindAction.ToggleRegex -> {
                search()
            }

            FindAction.Next, FindAction.Previous -> {
                showCurrent()
                highlight(ranges = false, reveal = true)
            }

            FindAction.ReplaceOne -> {
                afterSearch { replaceOne() }
            }

            FindAction.ReplaceAll -> {
                afterSearch { replaceAll() }
            }

            else -> {
                Unit
            }
        }
    }

    /** The text changed: the match list follows it, a moment later, while the bar is open. */
    fun onTextChanged() {
        if (host.state.find != null) search(select = false, debounceMillis = EDIT_DEBOUNCE_MILLIS)
    }

    private fun cancel() {
        job?.cancel()
        job = null
    }

    private fun search(
        select: Boolean = true,
        debounceMillis: Long = 0,
    ) {
        cancel()
        if (host.state.find == null || host.active == null) return
        job =
            host.deps.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                if (debounceMillis > 0) delay(debounceMillis)
                val find = host.state.find ?: return@launch
                val doc = host.active ?: return@launch
                val snapshot = doc.text.snapshot()
                searchesStarted++
                val result = withContext(host.deps.compute) { find(snapshot, find.query, find.options) }
                val caret = doc.surface.selection.start
                host.update { it.copy(find = it.find?.withResult(result, caret)) }
                if (select) showCurrent()
                highlight(reveal = select)
            }
    }

    /** Runs [action] once the search in flight (if any) has landed, so it acts on current offsets. */
    private fun afterSearch(action: () -> Unit) {
        if (job?.isCompleted != false) {
            action()
            return
        }
        host.deps.scope.launch {
            // A newer search may replace the one awaited (typing went on): wait for whichever is current.
            while (job?.isCompleted == false) job?.join()
            action()
        }
    }

    /** [FindEngine.find] that stops reading the text as soon as this coroutine is cancelled. */
    private suspend fun find(
        text: CharSequence,
        query: String,
        options: FindOptions,
    ): FindResult {
        val context = coroutineContext
        val searching = context[Job]
        val cancellable = CancellableText(text) { searching?.isActive == false }
        return try {
            FindEngine.find(cancellable, query, options)
        } catch (_: CancellableText.SearchCancelled) {
            context.ensureActive()
            FindResult.Found(emptyList(), capped = false)
        }
    }

    /** The view mode changed: a page coming on screen with the bar open paints the matches too. */
    fun onModeChanged() {
        if (host.state.find != null && host.state.mode != ViewMode.Code) highlight(reveal = false)
    }

    /**
     * Every match drawn in the Code view and, while it is on screen, the
     * page; the current one stronger (ux-spec "Find & replace"). [ranges]
     * false sends the page only the current index (a step); [reveal]
     * scrolls the page to it.
     */
    private fun highlight(
        ranges: Boolean = true,
        reveal: Boolean = false,
    ) {
        val find = host.state.find ?: return
        val starts = IntArray(find.matches.size) { find.matches[it].start }
        val ends = IntArray(find.matches.size) { find.matches[it].end }
        host.active?.highlight(starts, ends, find.current)
        if (host.state.mode == ViewMode.Code) return
        if (ranges) {
            host.preview.find(starts, ends, find.current, reveal)
        } else {
            host.preview.find(null, null, find.current, reveal)
        }
    }

    /** Selects the current match in the source, which scrolls the Code pane to it when one is on screen. */
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
        if (match.end > snapshot.length) return
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
        if (find.matches.last().end > snapshot.length) return
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

        /** Typing settles this long before the matches are recounted: one search per burst, not per key. */
        const val EDIT_DEBOUNCE_MILLIS = 120L
    }
}
