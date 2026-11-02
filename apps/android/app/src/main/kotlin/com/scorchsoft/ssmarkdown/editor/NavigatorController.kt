package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.files.document.CloseOutcome
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.saf.SuggestedFileName
import com.scorchsoft.ssmarkdown.navigator.FileRow
import com.scorchsoft.ssmarkdown.navigator.FileRowKind
import com.scorchsoft.ssmarkdown.navigator.NavigatorAction
import com.scorchsoft.ssmarkdown.navigator.NavigatorTab
import com.scorchsoft.ssmarkdown.navigator.NavigatorUiState
import com.scorchsoft.ssmarkdown.navigator.OutlineRow
import com.scorchsoft.ssmarkdown.settings.LocationLabel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Navigator (ux-spec "Navigator"): where am I, and where can I go?
 * Outline, recent files, drafts and the open folder, behind one
 * word-start search, plus the section menu (Copy section, J10).
 */
@Suppress("TooManyFunctions") // The sheet plus the section menu's four actions.
class NavigatorController(
    private val host: EditorHost,
) {
    private val vm get() = host as? EditorViewModel
    private var lastDeleted: DocumentUri? = null

    /** Opens the sheet: tapping the title, Ctrl+K or Ctrl+P. */
    fun open() {
        val outline = outlineRows()
        host.update {
            it.copy(
                navigator =
                    NavigatorUiState(
                        tab = if (outline.isEmpty()) NavigatorTab.Files else NavigatorTab.Outline,
                        outline = outline,
                        recent = recentRows(),
                        folder = folderRows(),
                        folderName = it.folder?.name,
                    ),
            )
        }
        loadDrafts()
    }

    /** The outline finished a pass: keep an open sheet current. */
    fun onOutlineChanged() {
        if (host.state.navigator == null) return
        val rows = outlineRows()
        host.update { it.copy(navigator = it.navigator?.copy(outline = rows)) }
    }

    /** A Navigator action; the reducer has already applied its pure part. */
    @Suppress("CyclomaticComplexMethod") // One branch per action.
    fun onAction(action: NavigatorAction) {
        when (action) {
            NavigatorAction.TopOfDocument -> vm?.jumpToLine(0)
            is NavigatorAction.JumpToHeading -> vm?.jumpToLine(action.line)
            is NavigatorAction.HeadingMenu -> sectionMenu(action.line)
            is NavigatorAction.OpenFile -> openFile(action.uri)
            NavigatorAction.OpenFirstResult -> openFirstResult()
            NavigatorAction.OpenPicker -> host.emit(EditorEvent.PickFileToOpen)
            is NavigatorAction.DeleteDraft -> deleteDraft(action.uri)
            is NavigatorAction.CopySection -> copySection(action.line, action.richText)
            is NavigatorAction.SelectSection -> selectSection(action.line)
            is NavigatorAction.ExportSection -> exportSection(action.line)
            else -> Unit
        }
    }

    private fun openFile(uri: String) {
        if (uri ==
            host.active
                ?.open
                ?.uri
                ?.value
        ) {
            return
        }
        vm?.saves?.requestLeave(PendingLeave.OpenOther(uri))
    }

    private fun openFirstResult() {
        val results = host.state.navigator?.results ?: return
        host.update { it.copy(navigator = null) }
        val heading = results.headings.firstOrNull()
        val file = (results.recent + results.folder).firstOrNull()
        when {
            heading != null -> vm?.jumpToLine(heading.line)
            file != null -> openFile(file.uri)
        }
    }

    @Suppress("ReturnCount") // No document, no heading and no range each end it.
    private fun sectionMenu(line: Int) {
        val doc = host.active ?: return
        val headings = doc.outline.headings()
        val index = headings.indexOfFirst { it.line == line }
        if (index < 0) return
        val range = doc.outline.sectionRange(line) ?: return
        val next = headings.drop(index + 1).firstOrNull { it.line > range.last }?.text
        host.update {
            it.copy(
                navigator = null,
                overlay = EditorOverlay.SectionMenu(line, headings[index].text, range.last - range.first + 1, next),
            )
        }
    }

    @Suppress("ReturnCount") // No document and no section each end it.
    private fun sectionText(line: Int): String? {
        val doc = host.active ?: return null
        val range = doc.outline.sectionRange(line) ?: return null
        val tree = doc.text.text
        return tree.getText(tree.lineStartOffset(range.first), tree.lineEndOffset(range.last))
    }

    private fun copySection(
        line: Int,
        richText: Boolean,
    ) {
        val text = sectionText(line) ?: return
        host.update { it.copy(overlay = null) }
        if (richText) {
            val range = host.active?.outline?.sectionRange(line) ?: return
            host.emit(EditorEvent.CopyRichText(text, range.first, range.last + 1))
            host.message(MessageKind.CopiedRichText)
        } else {
            host.emit(EditorEvent.CopyText(text))
            host.message(MessageKind.SectionCopied)
        }
    }

    private fun selectSection(line: Int) {
        val doc = host.active ?: return
        val range = doc.outline.sectionRange(line) ?: return
        val tree = doc.text.text
        host.update { it.copy(overlay = null, mode = if (it.mode == ViewMode.Preview) ViewMode.Code else it.mode) }
        doc.surface.select(tree.lineStartOffset(range.first), tree.lineEndOffset(range.last))
    }

    private fun exportSection(line: Int) {
        val text = sectionText(line) ?: return
        host.update { it.copy(overlay = null) }
        host.emit(EditorEvent.PickExportLocation(SuggestedFileName.forText(text), text))
    }

    /**
     * The Drafts list's delete. A draft is often the only copy of its text,
     * so this is never instant and permanent (DS-16, UX-1): the draft moves
     * to a trash and "Draft deleted · Undo" brings it back. A draft open
     * in memory but not on screen is saved and closed first, so its last
     * edits go with it and no orphan entry is left behind.
     */
    private fun deleteDraft(uri: String) {
        val target = DocumentUri(uri)
        if (target == host.active?.open?.uri) return
        host.deps.scope.launch {
            val store = host.deps.store
            if (store[target]?.isDirty == true) store.save(target)
            val closed = store[target]?.let { store.close(target) } ?: CloseOutcome.NotOpen
            val canDelete = closed == CloseOutcome.Closed || closed == CloseOutcome.NotOpen
            if (canDelete && withContext(host.deps.io) { host.deps.drafts.trash(target) }) {
                lastDeleted = target
                host.message(MessageKind.DraftDeleted, undo = true)
            } else {
                host.message(MessageKind.ActionFailed)
            }
            loadDrafts()
        }
    }

    /** Undo on "Draft deleted": the draft comes back as it was. */
    fun undoDeleteDraft() {
        val target = lastDeleted ?: return
        lastDeleted = null
        host.deps.scope.launch {
            if (!withContext(host.deps.io) { host.deps.drafts.restore(target) }) host.message(MessageKind.ActionFailed)
            loadDrafts()
        }
    }

    private fun outlineRows(): List<OutlineRow> {
        val doc = host.active ?: return emptyList()
        val currentLine = vm?.anchorLine?.value ?: 0
        val here =
            doc.outline
                .breadcrumbAt(currentLine)
                .lastOrNull()
                ?.line
        return doc.outline.headings().map { OutlineRow(it.line, it.level, it.text, current = it.line == here) }
    }

    private fun recentRows(): List<FileRow> {
        val now = host.deps.clock()
        val activeUri =
            host.active
                ?.open
                ?.uri
                ?.value
        val dirty =
            host.deps.store.dirtyDocuments
                .map { it.uri.value }
                .toSet()
        return host.deps.settings.settings.value.recentFiles
            .filter { it.uri != activeUri }
            .sortedByDescending { it.pinned }
            .map {
                FileRow(
                    it.uri,
                    it.name,
                    it.location.ifEmpty { LocationLabel.of(it.uri) },
                    FileRowKind.Recent,
                    now - it.openedAtMillis,
                    it.uri in dirty,
                )
            }
    }

    private fun folderRows(): List<FileRow> =
        host.state.folder
            ?.rows
            .orEmpty()
            .filter { !it.isDirectory }
            .map { FileRow(it.uri, it.name, "", FileRowKind.Folder, dirty = it.dirty, muted = it.muted) }

    private fun loadDrafts() {
        host.deps.scope.launch {
            val drafts = withContext(host.deps.io) { host.deps.drafts.list() }
            val rows =
                drafts.map {
                    FileRow(
                        it.uri.value,
                        it.title,
                        it.title,
                        FileRowKind.Draft,
                        it.ageMillis,
                        oldDraft =
                            it.ageMillis > OLD_DRAFT_MILLIS,
                    )
                }
            host.update { it.copy(navigator = it.navigator?.copy(drafts = rows)) }
        }
    }

    private companion object {
        /** Drafts untouched for 30 days get "Old draft"; they are never auto-deleted. */
        const val OLD_DRAFT_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}
