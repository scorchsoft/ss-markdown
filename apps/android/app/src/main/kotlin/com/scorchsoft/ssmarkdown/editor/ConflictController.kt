package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.conflict.CompareOrigin
import com.scorchsoft.ssmarkdown.conflict.CompareUiState
import com.scorchsoft.ssmarkdown.conflict.CompareView
import com.scorchsoft.ssmarkdown.conflict.MergeUiState
import com.scorchsoft.ssmarkdown.conflict.OverlapChoice
import com.scorchsoft.ssmarkdown.files.document.ConflictState
import com.scorchsoft.ssmarkdown.files.document.ResolveOutcome
import com.scorchsoft.ssmarkdown.files.merge.MergeResult
import com.scorchsoft.ssmarkdown.history.versions.LineDiff
import com.scorchsoft.ssmarkdown.nav.AppScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var cached: CachedMerge? = null

    /** A merge computed for the sheet, valid while [conflict] is pending and the buffer is at [version]. */
    private class CachedMerge(
        val conflict: ConflictState,
        val version: Long,
        val result: MergeResult,
    )

    /** The Compare screen's state, or null. */
    val compare: StateFlow<CompareUiState?> = compareState.asStateFlow()

    /** The MergeResolve screen's state, or null. */
    val merge: StateFlow<MergeUiState?> = mergeState.asStateFlow()

    /** The store found an outside change on a dirty buffer: banner, then the sheet. */
    fun onDetected() {
        host.update { it.copy(banner = EditorBanner.ChangedOutside) }
        review()
    }

    /**
     * "Review": opens the conflict sheet with its numbers. The merge and
     * the two diffs run on [EditorDependencies.io], not the main thread
     * (CC-8); the merge is kept for "Merge my edits" while the buffer
     * stays at the version it was made from.
     */
    fun review() {
        val doc = host.active ?: return
        val conflict = doc.open.conflict ?: return
        val version = doc.text.version
        val mine = doc.text.snapshot()
        host.deps.scope.launch {
            val (summary, merged) = withContext(host.deps.io) { summarise(conflict, mine) }
            if (host.active !== doc || doc.open.conflict !== conflict) return@launch
            cached = merged?.let { CachedMerge(conflict, version, it) }
            host.update { it.copy(overlay = EditorOverlay.Conflict(summary)) }
        }
    }

    /**
     * "Merge my edits into the new file": clean merges apply at once, merged
     * by the store from the buffer as it is then, so typing after the tap is
     * merged too; overlaps go to MergeResolve.
     */
    fun merge() {
        val doc = host.active ?: return
        val conflict = doc.open.conflict ?: return
        host.update { it.copy(overlay = null) }
        val version = doc.text.version
        val reuse = cached?.takeIf { it.conflict === conflict && it.version == version }?.result
        val mine = doc.text.snapshot()
        host.deps.scope.launch {
            val result = reuse ?: withContext(host.deps.io) { conflict.merge(mine) } ?: return@launch
            if (result.cleanText() != null) {
                resolve { uri -> host.deps.store.useCleanMerge(uri) }
            } else {
                pendingMerge = result
                mergeState.value =
                    MergeUiState
                        .of(doc.open.displayName.orEmpty(), result)
                        .copy(historyOn = historyOn(), madeFromVersion = version)
                host.emit(EditorEvent.Open(AppScreen.Merge))
            }
        }
    }

    /** MergeResolve: settles the current overlap. */
    fun chooseOverlap(choice: OverlapChoice) = mergeState.update { it?.choose(choice) }

    /** MergeResolve: moves to overlap [index]. */
    fun showOverlap(index: Int) = mergeState.update { it?.copy(current = index, editing = false) }

    /** MergeResolve: "Edit by hand" opens a field for the current overlap. */
    fun editOverlap(editing: Boolean) = mergeState.update { it?.copy(editing = editing) }

    /**
     * MergeResolve: Done. Undecided overlaps keep the file's lines; the
     * result is unsaved until Save. The buffer as it was is checkpointed in
     * History first. If it was edited while MergeResolve was open, nothing
     * is applied and the sheet comes back with fresh numbers (DS-7).
     */
    fun finishMerge() {
        val state = mergeState.value ?: return
        val result = pendingMerge ?: return
        val text = state.mergedText(result)
        mergeState.value = null
        pendingMerge = null
        resolve { uri -> host.deps.store.useText(uri, text, state.madeFromVersion) }
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

    /**
     * "Use my version…", after its confirmation: the buffer as it is when
     * the store applies it, every keystroke since the tap included, becomes
     * the new text to save.
     */
    fun useMine() = resolve { uri -> host.deps.store.useMine(uri) }

    private fun historyOn(): Boolean = host.deps.settings.settings.value.history.recording

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

                ResolveOutcome.ChangedAgain, ResolveOutcome.BufferChanged -> {
                    review()
                }

                is ResolveOutcome.Unavailable -> {
                    host.message(MessageKind.ActionFailed)
                }
            }
        }
    }

    private companion object {
        /** The sheet's numbers and the merge they came from. Pure; runs off the main thread. */
        fun summarise(
            conflict: ConflictState,
            mineText: CharSequence,
        ): Pair<ConflictSummary, MergeResult?> {
            val mine = mineText.toString()
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
            return summary to merged
        }
    }
}
