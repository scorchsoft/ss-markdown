package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.conflict.CompareOrigin
import com.scorchsoft.ssmarkdown.conflict.CompareUiState
import com.scorchsoft.ssmarkdown.conflict.CompareView
import com.scorchsoft.ssmarkdown.conflict.MergeUiState
import com.scorchsoft.ssmarkdown.conflict.OverlapChoice
import com.scorchsoft.ssmarkdown.files.document.ResolveOutcome
import com.scorchsoft.ssmarkdown.files.merge.MergeResult
import com.scorchsoft.ssmarkdown.history.versions.LineDiff
import com.scorchsoft.ssmarkdown.nav.AppScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The "changed in another app" flow (ux-spec "File changed outside the
 * app"; robustness §2a): the sheet, Merge, MergeResolve, Compare, Keep
 * the file's version and Use my version. Nothing is written until the
 * user chooses; the store re-checks the disk before every resolution.
 */
@Suppress("TooManyFunctions") // The sheet, MergeResolve and Compare: one flow, one small function per step.
class ConflictController(
    private val host: EditorHost,
) {
    private val compareState = MutableStateFlow<CompareUiState?>(null)
    private val mergeState = MutableStateFlow<MergeUiState?>(null)
    private var pendingMerge: MergeResult? = null

    /** The Compare screen's state, or null. */
    val compare: StateFlow<CompareUiState?> = compareState.asStateFlow()

    /** The MergeResolve screen's state, or null. */
    val merge: StateFlow<MergeUiState?> = mergeState.asStateFlow()

    /** The store found an outside change on a dirty buffer: banner, then the sheet. */
    fun onDetected() {
        host.update { it.copy(banner = EditorBanner.ChangedOutside) }
        review()
    }

    /** "Review": opens the conflict sheet with its numbers. */
    fun review() {
        val doc = host.active ?: return
        val conflict = doc.open.conflict ?: return
        val mine = doc.text.snapshot().toString()
        val merged = conflict.merge(mine)
        val reference = conflict.base ?: mine
        val theirs = LineDiff.summarize(reference, conflict.theirs)
        val mineDiff = conflict.base?.let { LineDiff.compute(it, mine).hunks.size } ?: 0
        val summary =
            ConflictSummary(
                theirsAdded = theirs.added,
                theirsRemoved = theirs.removed,
                mineChanges = mineDiff,
                cleanMerges = merged?.cleanChangeCount ?: 0,
                overlaps = merged?.conflictCount ?: 0,
                canMerge = merged != null,
            )
        host.update { it.copy(overlay = EditorOverlay.Conflict(summary)) }
    }

    /** "Merge my edits into the new file": clean merges apply at once; overlaps go to MergeResolve. */
    @Suppress("ReturnCount") // No document, no conflict and no base each end it.
    fun merge() {
        val doc = host.active ?: return
        val conflict = doc.open.conflict ?: return
        val result = conflict.merge(doc.text.snapshot()) ?: return
        host.update { it.copy(overlay = null) }
        val clean = result.cleanText()
        if (clean != null) {
            resolveWith(clean)
        } else {
            pendingMerge = result
            mergeState.value = MergeUiState.of(doc.open.displayName.orEmpty(), result)
            host.emit(EditorEvent.Open(AppScreen.Merge))
        }
    }

    /** MergeResolve: settles the current overlap. */
    fun chooseOverlap(choice: OverlapChoice) = mergeState.update { it?.choose(choice) }

    /** MergeResolve: moves to overlap [index]. */
    fun showOverlap(index: Int) = mergeState.update { it?.copy(current = index, editing = false) }

    /** MergeResolve: "Edit by hand" opens a field for the current overlap. */
    fun editOverlap(editing: Boolean) = mergeState.update { it?.copy(editing = editing) }

    /** MergeResolve: Done. Undecided overlaps keep the file's lines; the result is unsaved until Save. */
    fun finishMerge() {
        val state = mergeState.value ?: return
        val result = pendingMerge ?: return
        resolveWith(state.mergedText(result))
        mergeState.value = null
        pendingMerge = null
    }

    /** "Compare side by side" (one scrolling diff on a phone). */
    fun compare() {
        val doc = host.active ?: return
        val conflict = doc.open.conflict ?: return
        host.update { it.copy(overlay = null) }
        showCompare(
            CompareUiState.of(
                doc.open.displayName.orEmpty(),
                CompareOrigin.Conflict,
                conflict.theirs,
                doc.text.snapshot().toString(),
            ),
        )
    }

    /** Shows [state] on the Compare screen (also used by recovery and History). */
    fun showCompare(state: CompareUiState) {
        compareState.value = state
        host.emit(EditorEvent.Open(AppScreen.Compare))
    }

    /** The Compare screen's Changes / File's version / My version toggle. */
    fun setCompareView(view: CompareView) = compareState.update { it?.copy(view = view) }

    /** "Keep the file's version": your edits stay in History. */
    fun keepTheirs() = resolve { uri -> host.deps.store.keepTheirs(uri) }

    /** "Use my version…", after its confirmation: the buffer as it is becomes the new text to save. */
    fun useMine() {
        val text =
            host.active
                ?.text
                ?.snapshot()
                ?.toString() ?: return
        resolveWith(text)
    }

    private fun resolveWith(text: String) = resolve { uri -> host.deps.store.useText(uri, text) }

    private fun resolve(block: suspend (com.scorchsoft.ssmarkdown.files.provider.DocumentUri) -> ResolveOutcome) {
        val open = host.active?.open ?: return
        host.update { it.copy(overlay = null) }
        host.deps.scope.launch {
            when (block(open.uri)) {
                ResolveOutcome.Resolved, ResolveOutcome.NoConflict -> {
                    host.deps.autosave.resume(open.uri)
                    host.update { if (it.banner == EditorBanner.ChangedOutside) it.copy(banner = null) else it }
                    host.rebind()
                    host.refresh()
                }

                ResolveOutcome.ChangedAgain -> {
                    review()
                }

                is ResolveOutcome.Unavailable -> {
                    host.message(MessageKind.ActionFailed)
                }
            }
        }
    }
}
