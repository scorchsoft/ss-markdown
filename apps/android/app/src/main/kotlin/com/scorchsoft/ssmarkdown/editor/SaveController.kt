package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.files.document.CloseOutcome
import com.scorchsoft.ssmarkdown.files.document.OpenDocument
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.saf.SuggestedFileName
import com.scorchsoft.ssmarkdown.files.save.SaveOutcome
import com.scorchsoft.ssmarkdown.media.MediaScanner
import com.scorchsoft.ssmarkdown.media.MediaType
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Saving, Save as, Copy and Share, and leaving with unsaved edits: every
 * row of the Save-state table that needs an effect (docs/ux-spec.md,
 * "Top bar"; robustness §2, §3a, §6).
 */
@Suppress("TooManyFunctions") // The Save-state table's effects plus Copy, Share and leaving: one area.
class SaveController(
    private val host: EditorHost,
) {
    private val deps get() = host.deps

    /** The Save slot was tapped: what happens depends on the state it shows. */
    fun tap() {
        when (host.state.save.tap) {
            SaveTap.ShowAllSaved -> host.message(MessageKind.AllSaved)
            SaveTap.Save, SaveTap.Retry -> save()
            SaveTap.SaveAs -> saveAs()
            SaveTap.OpenReason -> openReason()
            SaveTap.Nothing -> Unit
        }
    }

    /** Long-press Save: Save as… · Autosave this file. */
    fun openSaveMenu() {
        val open = host.active?.open ?: return
        val autosaves =
            deps.settings.settings.value
                .autosaves(open.uri.value, open.uri.isDraft)
        host.update { it.copy(overlay = EditorOverlay.SaveMenu(autosaves)) }
    }

    /** Flips this file's autosave override; the twin of the toggle at the top of Version history. */
    fun toggleAutosaveThisFile() {
        val open = host.active?.open ?: return
        if (open.uri.isDraft) return
        val uri = open.uri.value
        val now =
            deps.settings.settings.value
                .autosaves(uri, isDraft = false)
        deps.settings.update { it.copy(autosaveOverrides = it.autosaveOverrides + (uri to !now)) }
        if (!now) deps.autosave.onLeave(open.uri)
        host.update { it.copy(overlay = null) }
        host.message(if (now) MessageKind.AutosaveThisFileOff else MessageKind.AutosaveThisFileOn)
        host.refresh()
    }

    /** Writes the document in place through the safe pipeline. */
    fun save(onSaved: () -> Unit = {}) {
        val open = host.active?.open ?: return
        if (open.uri.isDraft || open.readOnly != null) {
            saveAs()
            return
        }
        host.active?.surface?.flushComposing()
        val uri = open.uri.value
        host.updateMeta(uri) { it.copy(saving = true) }
        host.refresh()
        deps.scope.launch {
            val outcome = deps.store.save(open.uri)
            if (outcome is SaveOutcome.Saved) onSaved()
        }
    }

    /** "Save as…": the system picker, the name taken from the first heading. */
    fun saveAs() {
        val doc = host.active ?: return
        doc.surface.flushComposing()
        host.emit(EditorEvent.PickSaveLocation(SuggestedFileName.forText(doc.text.snapshot())))
    }

    /** The picker answered; [target] is null when it was cancelled. */
    fun onSaveLocation(target: String?) {
        val open = host.active?.open ?: return
        if (target == null) {
            afterSaveAs = null
            return
        }
        deps.permissions(target, true)
        val from = open.uri
        host.updateMeta(from.value) { it.copy(saving = true, savingAs = true) }
        host.refresh()
        deps.scope.launch {
            val outcome = deps.store.saveAs(from, DocumentUri(target))
            if (outcome is SaveOutcome.Saved) {
                // Only after the verified save, and off the main thread (StrictMode); the UI waits for it.
                if (from.isDraft) withContext(deps.io) { deps.drafts.delete(from) }
                deps.autosave.resume(open.uri)
                host.updateMeta(target) { it.copy(readOnlySource = null) }
                host.refresh()
                // Pictures inserted into a draft live in the drafts folder; they do not move with the
                // text (ux-spec "Pictures": Save as of a draft warns rather than copying silently).
                if (from.isDraft &&
                    hasRelativePictures(host.active?.text?.snapshot())
                ) {
                    host.message(MessageKind.PicturesStayedWithDraft)
                }
                afterSaveAs?.let {
                    afterSaveAs = null
                    leave(it, discard = false)
                }
            } else {
                afterSaveAs = null
            }
        }
    }

    /** The store reports a finished save, ours or autosave's. */
    fun onSaveFinished(
        uri: String,
        outcome: SaveOutcome,
    ) {
        val wasManual = host.meta(uri).saving
        val wasSaveAs = host.meta(uri).savingAs
        val problem = SaveProblem.of(outcome)
        host.updateMeta(uri) {
            if (problem == null) {
                it.copy(
                    saving = false,
                    savingAs = false,
                    lastSavedAtMillis = deps.clock(),
                    lastSaveWasAutosave = !wasManual,
                    lastFailure = null,
                )
            } else {
                it.copy(
                    saving = false,
                    savingAs = false,
                    lastFailure = problem.takeIf { wasManual } ?: it.lastFailure,
                )
            }
        }
        if (uri !=
            host.active
                ?.open
                ?.uri
                ?.value
        ) {
            return
        }
        when {
            problem == null -> onSaved(wasManual)

            // The picked target changed or vanished: this document did neither, so no conflict or missing banner.
            wasSaveAs -> failed(problem)

            problem == SaveProblem.ChangedOutside -> host.update { it.copy(banner = EditorBanner.ChangedOutside) }

            problem == SaveProblem.FileMissing -> host.update { it.copy(banner = EditorBanner.FileMissing) }

            wasManual -> failed(problem)
        }
        host.refresh()
    }

    private fun onSaved(manual: Boolean) {
        val banner = host.state.banner
        if (banner is EditorBanner.SaveFailed ||
            banner == EditorBanner.FileMissing
        ) {
            host.update { it.copy(banner = null) }
        }
        if (!manual) return
        host.emit(EditorEvent.Tick)
        host.message(
            MessageKind.SavedTo,
            host.active
                ?.open
                ?.displayName
                .orEmpty(),
        )
    }

    private fun failed(problem: SaveProblem) {
        host.update { it.copy(banner = EditorBanner.SaveFailed(problem)) }
        host.emit(EditorEvent.ErrorTick)
    }

    private fun openReason() {
        val open = host.active?.open ?: return
        if (open.conflict != null) {
            host.update { it.copy(banner = EditorBanner.ChangedOutside) }
            (host as? EditorViewModel)?.conflicts?.review()
        } else {
            saveAs()
        }
    }

    /** The name a large copy or share is offered under. */
    internal fun shareName(): String = fileName()

    /** ⋮ → Share. Large documents go as a file, never as `EXTRA_TEXT` (robustness §5). */
    fun share(richText: Boolean) {
        val doc = host.active ?: return
        val text = doc.text.snapshot()
        if (text.length > CLIPBOARD_LIMIT_CHARS) {
            host.emit(EditorEvent.ShareAsFile(fileName(), text))
        } else {
            host.emit(EditorEvent.ShareText(text, richText))
        }
    }

    /** Something would take a dirty buffer out of memory: ask first; otherwise just do it. */
    fun requestLeave(leave: PendingLeave) {
        val uri = host.active?.open?.uri
        val dirty = host.state.isDirty && uri?.isDraft == false
        // New or Open from a draft with words in it: the draft is kept, but say so and offer Save as… or Delete.
        val leavesDraft =
            leave == PendingLeave.NewDocument || leave == PendingLeave.PickFile || leave is PendingLeave.OpenOther
        val draftWithText =
            leavesDraft && uri?.isDraft == true &&
                host.active
                    ?.text
                    ?.snapshot()
                    ?.isNotBlank() == true
        // In folder mode every file keeps its own unsaved edits: opening another loses nothing.
        val folderSwitch =
            (leave is PendingLeave.OpenOther || leave == PendingLeave.PickFile) && host.state.folder != null
        if (draftWithText) {
            host.update { it.copy(overlay = EditorOverlay.DirtyPrompt(leave, draft = true)) }
        } else if (dirty && !folderSwitch) {
            host.update { it.copy(overlay = EditorOverlay.DirtyPrompt(leave)) }
        } else {
            leave(leave, discard = false)
        }
    }

    /**
     * "Save" at the prompt: leave only once the save has verified. A draft
     * goes through the Save as… picker first, and leaves once that save has
     * verified; cancelling the picker stays on the draft.
     */
    fun saveThenLeave(leave: PendingLeave) {
        host.update { it.copy(overlay = null) }
        if (host.active
                ?.open
                ?.uri
                ?.isDraft == true
        ) {
            afterSaveAs = leave
        }
        save { leave(leave, discard = false) }
    }

    /** True while the picker is up after the leave prompt was answered for it ([PendingLeave.PickFile]). */
    private var answeredBeforePick = false

    /**
     * The picker chose [uri]: it opens, asking first unless the question was
     * already answered before the picker opened.
     */
    fun onFilePicked(uri: String) {
        val answered = answeredBeforePick
        answeredBeforePick = false
        if (answered) leave(PendingLeave.OpenOther(uri), discard = false) else requestLeave(PendingLeave.OpenOther(uri))
    }

    /** The picker was cancelled: nothing opens, and nothing more is asked. */
    fun onPickCancelled() {
        answeredBeforePick = false
    }

    /** Where to go once a Save as… started from the leave prompt has verified. */
    private var afterSaveAs: PendingLeave? = null

    /**
     * Carries out [leave]; with [discard], "Don't save" (a History
     * checkpoint keeps the edits). If History could not keep them, the
     * document stays and the user is asked first, unless [evenIfNotKept]
     * (R2-DS-1).
     */
    fun leave(
        leave: PendingLeave,
        discard: Boolean,
        evenIfNotKept: Boolean = false,
    ) {
        host.update { it.copy(overlay = null) }
        val open = host.active?.open
        deps.scope.launch {
            if (discard && open != null && !dropEdits(open, leave, evenIfNotKept)) return@launch
            go(leave, open, discard)
        }
    }

    /**
     * "Don't save" / "Delete draft" before leaving [open]. A draft's last
     * typing is written first, so Undo brings all of it back, then it goes
     * to the drafts trash with Undo, as the Navigator's delete does (DS-16).
     * False when History could not keep a file's edits and the user is
     * asked first (R2-DS-1).
     */
    private suspend fun dropEdits(
        open: OpenDocument,
        leave: PendingLeave,
        evenIfNotKept: Boolean,
    ): Boolean {
        if (open.uri.isDraft) {
            if (deps.store[open.uri]?.isDirty == true) deps.store.save(open.uri)
            deps.store.close(open.uri)
            if (withContext(deps.io) { deps.drafts.trash(open.uri) }) {
                (host as? EditorViewModel)?.navigator?.trashed(open.uri)
            }
            return true
        }
        val closed = deps.store.close(open.uri, discardUnsaved = true, evenIfNotKept = evenIfNotKept)
        if (closed is CloseOutcome.NotKept) {
            host.update { it.copy(overlay = EditorOverlay.NotKept(NotKeptStep.DontSave(leave), closed.checkpoint)) }
            return false
        }
        return true
    }

    /** Goes where [leave] says, from [open] (whose edits [discarded] or not). */
    private fun go(
        leave: PendingLeave,
        open: OpenDocument?,
        discarded: Boolean,
    ) {
        when (leave) {
            PendingLeave.Exit -> {
                open?.uri?.let(deps.autosave::onLeave)
                host.emit(EditorEvent.Finish)
            }

            is PendingLeave.OpenOther -> {
                host.openDocument(leave.uri, leave.fromAnotherApp, leave.readOnlySource)
            }

            PendingLeave.NewDocument -> {
                host.newDraft()
            }

            PendingLeave.PickFile -> {
                // Don't save took the edits away: the file comes back as it is on disk; a deleted draft
                // leaves a fresh one. Then the picker; what is picked opens without asking again.
                if (discarded && open != null) {
                    if (open.uri.isDraft) host.newDraft() else host.openDocument(open.uri.value)
                }
                answeredBeforePick = true
                host.emit(EditorEvent.PickFileToOpen)
            }

            PendingLeave.CloseFolder -> {
                host.update { it.copy(folder = null) }
            }
        }
    }

    private fun fileName(): String =
        host.active?.open?.displayName ?: SuggestedFileName.forText(host.active?.text?.snapshot() ?: "")

    internal companion object {
        /** True when [text] links a picture beside it (a relative path, not a web address). */
        fun hasRelativePictures(text: CharSequence?): Boolean =
            text != null &&
                MediaScanner.scan(text).any { item ->
                    val source = item.source ?: return@any false
                    item.type == MediaType.Picture && !source.contains(':') && !source.startsWith("/")
                }

        /** About 1 MB of UTF-16 through Binder; larger copies go as a file (robustness §5). */
        const val CLIPBOARD_LIMIT_CHARS = 400_000
    }
}
