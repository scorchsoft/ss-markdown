package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.conflict.CompareOrigin
import com.scorchsoft.ssmarkdown.conflict.CompareUiState
import com.scorchsoft.ssmarkdown.files.document.Recovery
import com.scorchsoft.ssmarkdown.files.document.ResolveOutcome
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.text.EditOp
import kotlinx.coroutines.launch

/**
 * Getting work back after a crash (ux-spec "Recovery"; quality-bar J4):
 * zero taps to see "Your unsaved edits are back", then Got it, Compare,
 * or Discard edits with an Undo.
 */
class RecoveryController(
    private val host: EditorHost,
) {
    private val deps get() = host.deps
    private var discarded: String? = null

    /**
     * The launch scan. The most recently edited document opens on screen
     * with its banner; any others are restored into memory, journalled,
     * with their unsaved dots in the Navigator. Returns true if anything
     * was recovered.
     */
    suspend fun recoverOnLaunch(): Boolean {
        val offers =
            deps.store
                .scanForRecovery()
                .offers
                .sortedByDescending { it.editedAtMillis }
        val latest = offers.firstOrNull() ?: return false
        offers.drop(1).forEach { offer ->
            deps.store.open(offer.uri)
            // Closing a dirty entry keeps it in memory (CloseOutcome.KeptUnsaved); it is simply not on screen.
            deps.store.close(offer.uri)
        }
        host.openDocument(latest.uri.value)
        return true
    }

    /** What opening found of an earlier session's edits. */
    fun onRecovered(recovery: Recovery) {
        when (recovery) {
            is Recovery.Restored -> {
                host.update {
                    it.copy(
                        banner = EditorBanner.Recovered(recovery.editedAtMillis, deps.lastExitWasSwipe()),
                    )
                }
            }

            is Recovery.Conflicted -> {
                (host as? EditorViewModel)?.conflicts?.onDetected()
            }

            is Recovery.Kept -> {
                // Not a failure toast: the edits exist and are kept; the user decides (DS-3).
                host.update { it.copy(banner = EditorBanner.EditsKeptAside) }
            }
        }
    }

    /** "Compare": the file on disk against the restored edits. */
    fun compare() {
        val doc = host.active ?: return
        val mine = doc.text.snapshot().toString()
        deps.scope.launch {
            val disk = deps.diskReader.read(doc.open.uri.value) ?: return@launch host.message(MessageKind.ActionFailed)
            val compare = CompareUiState.of(doc.open.displayName.orEmpty(), CompareOrigin.Recovery, disk, mine)
            (host as? EditorViewModel)?.conflicts?.showCompare(compare)
        }
    }

    /** "Discard them" for edits an earlier session could not apply: set aside, never deleted outright. */
    fun discardKept() {
        val open = host.active?.open ?: return
        deps.store.discardHeldEdits(open.uri)
    }

    /**
     * "Discard edits": History keeps a checkpoint (when History is on; the
     * message says so only then, DS-9), the file comes back clean, and
     * Undo is offered.
     */
    fun discard() {
        val doc = host.active ?: return
        val kept = doc.text.snapshot()
        host.update { it.copy(banner = null) }
        deps.scope.launch {
            if (deps.store.discardEdits(doc.open.uri) == ResolveOutcome.Resolved) {
                discarded = kept.toString()
                host.rebind()
                val historyOn = deps.settings.settings.value.history.recording
                host.message(
                    if (historyOn) MessageKind.EditsDiscarded else MessageKind.EditsDiscardedNoHistory,
                    undo = true,
                )
            } else {
                host.message(MessageKind.ActionFailed)
            }
        }
    }

    /** Undo on "Edits discarded · kept in History": the discarded text comes back, unsaved. */
    fun undoDiscard() {
        val text = discarded ?: return
        val doc = host.active ?: return
        discarded = null
        val current = doc.text.snapshot()
        doc.surface.apply(listOf(EditOp(0, current, text)), TextSelection.caret(0))
        host.refresh()
    }
}
