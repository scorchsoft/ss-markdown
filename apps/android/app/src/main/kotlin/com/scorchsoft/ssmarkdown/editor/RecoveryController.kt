package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.conflict.CompareOrigin
import com.scorchsoft.ssmarkdown.conflict.CompareUiState
import com.scorchsoft.ssmarkdown.files.document.CheckpointResult
import com.scorchsoft.ssmarkdown.files.document.CopyOutcome
import com.scorchsoft.ssmarkdown.files.document.Recovery
import com.scorchsoft.ssmarkdown.files.document.ResolveOutcome
import com.scorchsoft.ssmarkdown.files.document.SetAsideEdits
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.text.EditOp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Getting work back after a crash (ux-spec "Recovery"; quality-bar J4):
 * zero taps to see "Your unsaved edits are back", then Got it, Compare,
 * or Discard edits with an Undo.
 */
@Suppress("TooManyFunctions") // The banner's actions plus the three ways a copy is made: one small function each.
class RecoveryController(
    private val host: EditorHost,
) {
    private val deps get() = host.deps

    /** The text "Discard edits" took away, and the document it belongs to, for the message's Undo. */
    private var discarded: Pair<String, String>? = null

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
        val mine = doc.text.snapshot()
        val name = doc.open.displayName.orEmpty()
        deps.scope.launch {
            val disk = deps.diskReader.read(doc.open.uri.value) ?: return@launch host.message(MessageKind.ActionFailed)
            // The copy and the diff run off the main thread; the snapshot is immutable (PERF-22).
            val compare =
                withContext(deps.compute) { CompareUiState.of(name, CompareOrigin.Recovery, disk, mine.toString()) }
            (host as? EditorViewModel)?.conflicts?.showCompare(compare)
        }
    }

    /** "Discard them" for edits an earlier session could not apply: set aside, never deleted outright. */
    fun discardKept() {
        val open = host.active?.open ?: return
        deps.store.discardHeldEdits(open.uri)
    }

    /**
     * "Open as a copy" for edits an earlier session could not apply
     * (R2-DS-3): rebuilt into a new draft, shown with its banner; the
     * file on screen before stays as it is on disk.
     */
    fun openKeptAsCopy() {
        val open = host.active?.open ?: return
        val name = open.displayName
        deps.scope.launch {
            val shown = copy(CopyReason.NotApplied, name) { draft -> deps.store.recoverAsCopy(open.uri, draft) }
            if (!shown) host.message(MessageKind.ActionFailed)
        }
    }

    /**
     * Edits to [uri] whose file cannot be reached after a restart (grant
     * gone, moved, deleted, provider gone): rebuilt into a new draft and
     * shown as "Recovered from <name>" (R2-DS-2). False if nothing could be
     * read; the journal then stays where it is.
     */
    suspend fun openUnreachableAsCopy(uri: DocumentUri): Boolean {
        val name =
            deps.settings.settings.value.recentFiles
                .firstOrNull { it.uri == uri.value }
                ?.name
        return copy(CopyReason.Unreachable, name) { draft -> deps.store.recoverAsCopy(uri, draft) }
    }

    /** Storage → Set-aside edits → "Open as a copy" (R2-DS-3). */
    fun openSetAsideAsCopy(edits: SetAsideEdits) {
        val name =
            deps.settings.settings.value.recentFiles
                .firstOrNull { it.uri == edits.uri?.value }
                ?.name
        deps.scope.launch {
            val shown = copy(CopyReason.SetAside, name) { draft -> deps.store.recoverSetAsideAsCopy(edits, draft) }
            if (!shown) host.message(MessageKind.ActionFailed)
        }
    }

    private suspend fun copy(
        reason: CopyReason,
        sourceName: String?,
        make: suspend (DocumentUri) -> CopyOutcome,
    ): Boolean {
        val draft = deps.drafts.create() ?: return false
        return when (val copy = make(draft)) {
            is CopyOutcome.Copied -> {
                (host as? EditorViewModel)?.show(
                    copy.document,
                    EditorBanner.RecoveredCopy(sourceName, reason, copy.complete),
                )
                true
            }

            is CopyOutcome.Unavailable -> {
                deps.drafts.delete(draft) // Still empty: nothing was written to it.
                false
            }
        }
    }

    /**
     * "Discard edits": History keeps a checkpoint, the file comes back
     * clean, and Undo is offered. The message is worded from History's
     * answer, never from the setting (DS-9, R2-DS-1); if History kept
     * nothing, nothing is discarded until the user says so.
     */
    fun discard(evenIfNotKept: Boolean = !host.state.historyOn) {
        val doc = host.active ?: return
        val kept = doc.text.snapshot()
        val banner = host.state.banner
        host.update { it.copy(banner = null) }
        deps.scope.launch {
            when (val outcome = deps.store.discardEdits(doc.open.uri, evenIfNotKept)) {
                is ResolveOutcome.Resolved -> {
                    discarded = doc.open.uri.value to withContext(deps.compute) { kept.toString() }
                    host.rebind()
                    host.message(discardedMessage(outcome.checkpoint), undo = true)
                }

                is ResolveOutcome.NotKept -> {
                    host.update {
                        it.copy(
                            banner = banner,
                            overlay = EditorOverlay.NotKept(NotKeptStep.DiscardRecovered, outcome.checkpoint),
                        )
                    }
                }

                else -> {
                    host.update { it.copy(banner = banner) }
                    host.message(MessageKind.ActionFailed)
                }
            }
        }
    }

    private fun discardedMessage(checkpoint: CheckpointResult): MessageKind =
        when (checkpoint) {
            CheckpointResult.Recorded -> MessageKind.EditsDiscarded
            CheckpointResult.Off -> MessageKind.EditsDiscardedNoHistory
            CheckpointResult.Failed -> MessageKind.EditsDiscardedNotKept
        }

    /** The discard's message timed out: its Undo is gone, and so is the text it would have brought back. */
    fun forgetDiscard() {
        discarded = null
    }

    /** Undo on "Edits discarded · kept in History": the discarded text comes back, unsaved. */
    fun undoDiscard() {
        val (uri, text) = discarded ?: return
        discarded = null
        // Only into the document it came from: the message can outlive a switch to another file.
        val doc = host.active?.takeIf { it.open.uri.value == uri } ?: return
        val current = doc.text.snapshot()
        doc.surface.apply(listOf(EditOp(0, current, text)), TextSelection.caret(0))
        host.refresh()
    }
}
