package com.scorchsoft.ssmarkdown.share

import com.scorchsoft.ssmarkdown.document.VersionHistoryHook
import com.scorchsoft.ssmarkdown.editor.EditorViewModel
import com.scorchsoft.ssmarkdown.files.document.CheckReason
import com.scorchsoft.ssmarkdown.files.document.DocumentStore
import com.scorchsoft.ssmarkdown.files.document.OpenDocument
import com.scorchsoft.ssmarkdown.files.document.OpenOutcome
import com.scorchsoft.ssmarkdown.files.document.OutsideChange
import com.scorchsoft.ssmarkdown.files.document.Recovery
import com.scorchsoft.ssmarkdown.files.drafts.DraftStore
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.save.SaveOutcome
import com.scorchsoft.ssmarkdown.history.versions.VersionKind
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.settings.AppendPosition
import com.scorchsoft.ssmarkdown.settings.RecentFile
import com.scorchsoft.ssmarkdown.settings.SettingsRepository
import com.scorchsoft.ssmarkdown.text.EditOp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Share into SS Markdown (ux-spec "Share into SS Markdown"; J9). Appending
 * runs through the normal save pipeline: fingerprint check, backup,
 * verify and a history entry. If the target is open with unsaved edits,
 * the text is added to that buffer instead and left unsaved, so it can
 * never race the editor.
 *
 * @param onAppended told the URI of each file the text reached, so its Direct Share target ranks higher.
 * @param dateHeading "### <date time>" for the date prefix, from the caller's locale.
 */
@Suppress("LongParameterList", "TooManyFunctions") // Constructor injection; one append step per function.
class ShareViewModel(
    private val store: DocumentStore,
    private val drafts: DraftStore,
    private val history: VersionHistoryHook,
    private val settings: SettingsRepository,
    private val editor: EditorViewModel?,
    private val scope: CoroutineScope,
    private val onAppended: (String) -> Unit = {},
    private val dateHeading: () -> String,
) {
    private val ui = MutableStateFlow(ShareUiState())

    /** An append that was writing when the sheet was last saved, checked once the text is read again. */
    private var inFlight: InFlight? = null

    private data class InFlight(
        val target: String?,
        val name: String,
        val position: AppendPosition,
    )

    /** The sheet's state. */
    val state: StateFlow<ShareUiState> = ui.asStateFlow()

    /**
     * Starts the sheet for [text], remembering the last target and options.
     *
     * @param preselect a recent file the user chose in Android's share sheet
     *   (a Direct Share target, already resolved through our recents): it is
     *   chosen and listed first, and Append still has to be tapped. Ignored
     *   unless it is one of the recent files.
     */
    fun begin(
        text: String,
        source: String?,
        preselect: String? = null,
    ) {
        // A sheet rebuilt after its append finished keeps "Added to …": reading the text again must not reopen
        // Choosing, or a second Append writes the same text twice.
        if (ui.value.phase is SharePhase.Done) {
            ui.update { it.copy(text = text, source = source) }
            return
        }
        val s = settings.settings.value
        val ranked = ShareTargets.ranked(s)
        // A file picked with "Another file…" before the text was read (the sheet rebuilt while the picker
        // was open) stays chosen.
        val picked = ui.value.target?.let { uri -> ui.value.targets.firstOrNull { it.uri == uri } }
        val chosen = picked?.uri ?: preselect?.takeIf { p -> ranked.any { it.uri == p } }
        val known = if (picked == null) ranked else listOf(picked) + ranked.filterNot { it.uri == picked.uri }
        val targets = if (chosen == null) known else known.sortedByDescending { it.uri == chosen }
        ui.value =
            ShareUiState(
                text = text,
                source = source,
                targets = targets,
                target = chosen ?: s.share.lastTarget?.takeIf { last -> targets.any { it.uri == last } },
                position = s.share.position,
                prefix = s.share.prefix,
            )
        inFlight?.let {
            inFlight = null
            resume(it)
        }
    }

    /**
     * A rebuilt sheet whose append to [target] (null: a new note) was still writing when Android closed the
     * app. Once the text is read again ([begin]), the file is checked: if the text is there, the append had
     * landed and the sheet shows it added; if not, Append is offered again with a note that nothing was added.
     */
    fun restoreInterrupted(
        target: String?,
        name: String,
        position: AppendPosition,
    ) {
        inFlight = InFlight(target, name, position)
    }

    private fun resume(was: InFlight) {
        val target = was.target
        if (target == null) {
            // A new note that was being written: drafts are not searched, so it is offered again.
            ui.update { it.copy(target = null, phase = SharePhase.Choosing, interrupted = true) }
            return
        }
        val known = ui.value.targets.any { it.uri == target }
        ui.update { state ->
            state.copy(
                targets = if (known) state.targets else listOf(RecentFile(target, was.name, "", 0)) + state.targets,
                target = target,
                position = was.position,
                phase = SharePhase.Working,
            )
        }
        val state = ui.value
        scope.launch {
            val landed = landed(DocumentUri(target), state)
            ui.update {
                if (landed) {
                    it.copy(phase = SharePhase.Done(nameOf(target, it), target))
                } else {
                    it.copy(phase = SharePhase.Choosing, interrupted = true)
                }
            }
        }
    }

    /** True when [state]'s text already sits where its append would put it in [uri]. */
    private suspend fun landed(
        uri: DocumentUri,
        state: ShareUiState,
    ): Boolean {
        store[uri]?.let { return alreadyThere(it, state) }
        val opened = (store.open(uri) as? OpenOutcome.Opened)?.document ?: return false
        return try {
            alreadyThere(opened, state)
        } finally {
            store.close(uri)
        }
    }

    /** A rebuilt sheet whose append had already finished: it shows [done] and can't append again. */
    fun restoreDone(done: SharePhase.Done) = ui.update { it.copy(phase = done) }

    /** The shared content could not be used. */
    fun unusable(tooLargeBytes: Long?) = ui.update { it.copy(phase = SharePhase.Unusable(tooLargeBytes)) }

    /** A file picked with "Another file…" becomes the target. */
    fun onPicked(
        uri: String,
        name: String,
    ) {
        ui.update { state ->
            val targets = listOf(RecentFile(uri, name, "", 0)) + state.targets.filterNot { it.uri == uri }
            state.copy(targets = targets, target = uri)
        }
    }

    /** The single entry point for the sheet's actions (Cancel and Open are the Activity's). */
    fun onAction(action: ShareAction) {
        // Append is offered only while choosing: never a second time once it is working or done. Add again
        // only answers "already there".
        if (action == ShareAction.Confirm && ui.value.phase != SharePhase.Choosing) return
        if (action == ShareAction.AddAgain && ui.value.phase !is SharePhase.AlreadyThere) return
        ui.update { ShareReducer.reduce(it, action) }
        when (action) {
            ShareAction.Confirm -> confirm(force = false)
            ShareAction.AddAgain -> confirm(force = true)
            else -> Unit
        }
    }

    /** Appends or creates the note; [force] skips the duplicate check (Add again). */
    private fun confirm(force: Boolean) {
        val state = ui.value
        // Nothing was shared into this sheet (it never began): Append would write only a heading or blank lines.
        if (state.text.isEmpty()) {
            ui.update { it.copy(phase = SharePhase.Unusable(null)) }
            return
        }
        settings.update {
            it.copy(share = it.share.copy(lastTarget = state.target, position = state.position, prefix = state.prefix))
        }
        ui.update { it.copy(phase = SharePhase.Working, interrupted = false) }
        scope.launch {
            val target = state.target
            if (target == null) newNote(state) else append(target, state, force)
        }
    }

    private suspend fun newNote(state: ShareUiState) {
        val uri = drafts.create() ?: return ui.update { it.copy(phase = SharePhase.Failed("")) }
        val outcome = store.open(uri)
        val doc =
            (outcome as? OpenOutcome.Opened)?.document ?: return ui.update { it.copy(phase = SharePhase.Failed("")) }
        doc.replace(0, 0, state.text)
        val saved = store.save(uri) is SaveOutcome.Saved
        // A failed save leaves the note in memory, journalled: closing keeps a dirty buffer (DS-15).
        store.close(uri)
        ui.update { it.copy(phase = if (saved) SharePhase.Done("", uri.value) else SharePhase.Failed("")) }
    }

    private suspend fun append(
        target: String,
        state: ShareUiState,
        force: Boolean,
    ) {
        val uri = DocumentUri(target)
        val name = nameOf(target, state)
        val alreadyOpen = store[uri]
        val doc =
            if (alreadyOpen != null) {
                readyOpen(alreadyOpen, uri, name, target, state, force)
            } else {
                readyClosed(uri, name, target, state, force)
            } ?: return
        if (!force && alreadyThere(doc, state)) {
            if (alreadyOpen == null) store.close(uri)
            ui.update { it.copy(phase = SharePhase.AlreadyThere(name, target, state.position)) }
            return
        }
        insert(doc, state)
        // Recorded before the save, so "Appended from <source>" is the version History shows:
        // the save's own record of the same text adds nothing after it.
        history.record(uri, doc.document.snapshot(), VersionKind.Appended, state.source)
        val outcome = store.save(uri)
        // A failed save leaves the appended text in memory, journalled: closing keeps a dirty buffer.
        if (alreadyOpen == null) store.close(uri)
        ui.update {
            it.copy(
                phase =
                    when (outcome) {
                        is SaveOutcome.Saved -> appended(name, target)
                        is SaveOutcome.Conflict -> SharePhase.Blocked(name, conflict = true)
                        else -> SharePhase.Failed(name)
                    },
            )
        }
    }

    /**
     * The target is open here. Read-only or in conflict: refused, with New
     * note on offer. Unsaved: the text joins that buffer and stays unsaved,
     * so it never races the editor. Clean: the disk is the truth, so a
     * buffer another app changed is reloaded first and the append lands on
     * that version, not on a stale copy. Null when this ended the flow.
     */
    @Suppress("LongParameterList") // The append's context, passed down rather than kept as mutable state.
    private suspend fun readyOpen(
        open: OpenDocument,
        uri: DocumentUri,
        name: String,
        target: String,
        state: ShareUiState,
        force: Boolean,
    ): OpenDocument? {
        val phase =
            when {
                open.readOnly != null || open.conflict != null -> {
                    SharePhase.Blocked(name, conflict = open.conflict != null)
                }

                open.isDirty && !force && alreadyThere(open, state) -> {
                    SharePhase.AlreadyThere(name, target, state.position)
                }

                open.isDirty -> {
                    insert(open, state)
                    appended(name, target)
                }

                store.checkOutside(uri, CheckReason.BeforeSave) == OutsideChange.Missing -> {
                    SharePhase.Failed(name)
                }

                else -> {
                    return open
                }
            }
        ui.update { it.copy(phase = phase) }
        return null
    }

    /**
     * Opens a target that is not open here, and refuses one that is
     * read-only or conflicted. Null when this ended the flow.
     */
    @Suppress("ReturnCount") // Each refusal (failed, recovered, read-only or conflicted) ends the flow its own way.
    private suspend fun readyClosed(
        uri: DocumentUri,
        name: String,
        target: String,
        state: ShareUiState,
        force: Boolean,
    ): OpenDocument? {
        val opened = store.open(uri) as? OpenOutcome.Opened
        if (opened == null) {
            ui.update { it.copy(phase = SharePhase.Failed(name)) }
            return null
        }
        if (recovered(opened, uri, name, target, state, force)) return null
        val doc = opened.document
        if (doc.readOnly != null || doc.conflict != null) {
            store.close(uri)
            ui.update { it.copy(phase = SharePhase.Blocked(name, conflict = doc.conflict != null)) }
            return null
        }
        return doc
    }

    /**
     * Unsaved edits from an earlier session came back with this open. The
     * user has not seen them yet, so they are never saved from here
     * (DS-15): restored edits take the append unsaved and journalled, and
     * stay in memory with their unsaved dot; edits that could not be
     * applied stay held, untouched, since appending would set them aside.
     * Returns true when this ended the flow.
     */
    @Suppress("LongParameterList") // The append's context, passed down rather than kept as mutable state.
    private suspend fun recovered(
        opened: OpenOutcome.Opened,
        uri: DocumentUri,
        name: String,
        target: String,
        state: ShareUiState,
        force: Boolean,
    ): Boolean =
        when (opened.recovery) {
            is Recovery.Restored -> {
                val there = !force && alreadyThere(opened.document, state)
                if (!there) insert(opened.document, state)
                store.close(uri) // Dirty, so it stays in memory: KeptUnsaved.
                val phase = if (there) SharePhase.AlreadyThere(name, target, state.position) else appended(name, target)
                ui.update { it.copy(phase = phase) }
                true
            }

            is Recovery.Kept -> {
                store.close(uri)
                ui.update { it.copy(phase = SharePhase.Blocked(name, conflict = true)) }
                true
            }

            is Recovery.Conflicted, null -> {
                false
            }
        }

    /** The name the sheet shows for [target]: its recent-file name, else the last part of its URI. */
    private fun nameOf(
        target: String,
        state: ShareUiState,
    ): String = state.targets.firstOrNull { it.uri == target }?.name ?: target.substringAfterLast('/')

    /** True when [state]'s text already sits where its append would go in [doc] (blank text never does). */
    private fun alreadyThere(
        doc: OpenDocument,
        state: ShareUiState,
    ): Boolean = AppendPlanner.alreadyThere(doc.document.snapshot(), state.text, state.position)

    /** The text reached [target]: Done, and [onAppended] is told. */
    private fun appended(
        name: String,
        target: String,
    ): SharePhase {
        onAppended(target)
        return SharePhase.Done(name, target)
    }

    /** Inserts through the editor's surface when it shows this buffer, so its view sees the change. */
    private fun insert(
        doc: OpenDocument,
        state: ShareUiState,
    ) {
        val plan = AppendPlanner.plan(doc.document.snapshot(), state.text, state.position, state.prefix, dateHeading())
        val active = editor?.active
        if (active != null && active.open === doc) {
            active.surface.apply(
                listOf(EditOp(plan.offset, "", plan.text)),
                TextSelection.caret(plan.offset + plan.text.length),
            )
        } else {
            doc.replace(plan.offset, 0, plan.text)
        }
    }
}
