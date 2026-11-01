package com.scorchsoft.ssmarkdown.share

import com.scorchsoft.ssmarkdown.document.VersionHistoryHook
import com.scorchsoft.ssmarkdown.editor.EditorViewModel
import com.scorchsoft.ssmarkdown.files.document.DocumentStore
import com.scorchsoft.ssmarkdown.files.document.OpenDocument
import com.scorchsoft.ssmarkdown.files.document.OpenOutcome
import com.scorchsoft.ssmarkdown.files.document.Recovery
import com.scorchsoft.ssmarkdown.files.drafts.DraftStore
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.save.SaveOutcome
import com.scorchsoft.ssmarkdown.history.versions.VersionKind
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
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
 * @param dateHeading "### <date time>" for the date prefix, from the caller's locale.
 */
@Suppress("LongParameterList") // Constructor injection without a DI framework.
class ShareViewModel(
    private val store: DocumentStore,
    private val drafts: DraftStore,
    private val history: VersionHistoryHook,
    private val settings: SettingsRepository,
    private val editor: EditorViewModel?,
    private val scope: CoroutineScope,
    private val dateHeading: () -> String,
) {
    private val ui = MutableStateFlow(ShareUiState())

    /** The sheet's state. */
    val state: StateFlow<ShareUiState> = ui.asStateFlow()

    /** Starts the sheet for [text], remembering the last target and options. */
    fun begin(
        text: String,
        source: String?,
    ) {
        val s = settings.settings.value
        val targets = s.recentFiles.sortedByDescending { it.uri == s.share.lastTarget || it.pinned }
        ui.value =
            ShareUiState(
                text = text,
                source = source,
                targets = targets,
                target = s.share.lastTarget?.takeIf { last -> targets.any { it.uri == last } },
                position = s.share.position,
                prefix = s.share.prefix,
            )
    }

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
        ui.update { ShareReducer.reduce(it, action) }
        if (action == ShareAction.Confirm) confirm()
    }

    private fun confirm() {
        val state = ui.value
        settings.update {
            it.copy(share = it.share.copy(lastTarget = state.target, position = state.position, prefix = state.prefix))
        }
        ui.update { it.copy(phase = SharePhase.Working) }
        scope.launch {
            val target = state.target
            if (target == null) newNote(state) else append(target, state)
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

    @Suppress("ReturnCount") // Each refusal (not open, read-only, conflicted, failed) ends the flow its own way.
    private suspend fun append(
        target: String,
        state: ShareUiState,
    ) {
        val uri = DocumentUri(target)
        val name = state.targets.firstOrNull { it.uri == target }?.name ?: target.substringAfterLast('/')
        val alreadyOpen = store[uri]
        if (alreadyOpen != null && alreadyOpen.isDirty) {
            insert(alreadyOpen, state)
            return ui.update { it.copy(phase = SharePhase.Done(name, target)) }
        }
        val opened =
            store.open(uri) as? OpenOutcome.Opened ?: return ui.update { it.copy(phase = SharePhase.Failed(name)) }
        val doc = opened.document
        if (recovered(opened, uri, name, target, state)) return
        if (doc.readOnly != null || doc.conflict != null) {
            if (alreadyOpen == null) store.close(uri)
            return ui.update { it.copy(phase = SharePhase.Blocked(name, conflict = doc.conflict != null)) }
        }
        insert(doc, state)
        val outcome = store.save(uri)
        if (outcome is SaveOutcome.Saved) {
            history.record(
                uri,
                doc.document.snapshot(),
                VersionKind.Appended,
                state.source,
            )
        }
        // A failed save leaves the appended text in memory, journalled: closing keeps a dirty buffer.
        if (alreadyOpen == null) store.close(uri)
        ui.update {
            it.copy(
                phase =
                    when (outcome) {
                        is SaveOutcome.Saved -> SharePhase.Done(name, target)
                        is SaveOutcome.Conflict -> SharePhase.Blocked(name, conflict = true)
                        else -> SharePhase.Failed(name)
                    },
            )
        }
    }

    /**
     * Unsaved edits from an earlier session came back with this open. The
     * user has not seen them yet, so they are never saved from here
     * (DS-15): restored edits take the append unsaved and journalled, and
     * stay in memory with their unsaved dot; edits that could not be
     * applied stay held, untouched, since appending would set them aside.
     * Returns true when this ended the flow.
     */
    private suspend fun recovered(
        opened: OpenOutcome.Opened,
        uri: DocumentUri,
        name: String,
        target: String,
        state: ShareUiState,
    ): Boolean =
        when (opened.recovery) {
            is Recovery.Restored -> {
                insert(opened.document, state)
                store.close(uri) // Dirty, so it stays in memory: KeptUnsaved.
                ui.update { it.copy(phase = SharePhase.Done(name, target)) }
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
