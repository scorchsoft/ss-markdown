package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.files.document.CloseOutcome
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
        if (target == null) return
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
        val dirty =
            host.state.isDirty && host.active
                ?.open
                ?.uri
                ?.isDraft == false
        val folderSwitch = leave is PendingLeave.OpenOther && host.state.folder != null
        if (dirty && !folderSwitch) {
            host.update { it.copy(overlay = EditorOverlay.DirtyPrompt(leave)) }
        } else {
            leave(leave, discard = false)
        }
    }

    /** "Save" at the prompt: leave only once the save has verified. */
    fun saveThenLeave(leave: PendingLeave) = save { leave(leave, discard = false) }

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
            if (discard && open != null) {
                val closed = deps.store.close(open.uri, discardUnsaved = true, evenIfNotKept = evenIfNotKept)
                if (closed is CloseOutcome.NotKept) {
                    val ask = EditorOverlay.NotKept(NotKeptStep.DontSave(leave), closed.checkpoint)
                    host.update { it.copy(overlay = ask) }
                    return@launch
                }
            }
            when (leave) {
                PendingLeave.Exit -> {
                    open?.uri?.let(deps.autosave::onLeave)
                    host.emit(EditorEvent.Finish)
                }

                is PendingLeave.OpenOther -> {
                    host.openDocument(leave.uri)
                }

                PendingLeave.NewDocument -> {
                    host.newDraft()
                }

                PendingLeave.CloseFolder -> {
                    host.update { it.copy(folder = null) }
                }
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
