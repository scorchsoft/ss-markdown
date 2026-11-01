package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.document.ActiveDocument
import com.scorchsoft.ssmarkdown.viewstate.FileViewState

/**
 * Each file reopens the way it was left (research R7): view mode, zoom,
 * selection and the line at the top, stored per file in app storage by
 * [EditorDependencies.viewStates]. Drafts are remembered too: they are
 * files, just private ones.
 */
class ViewMemory(
    private val vm: EditorViewModel,
) {
    /** The cursor and scroll to apply when the Code view next attaches to [pendingFor]. */
    private var pending: FileViewState? = null
    private var pendingFor: ActiveDocument? = null

    /** What was remembered for [document]; its cursor waits for the Code view. */
    fun restore(document: ActiveDocument): FileViewState? {
        val saved = vm.deps.viewStates.read(document.open.uri.value) ?: return null
        pending = saved
        pendingFor = document
        return saved
    }

    /** Puts the remembered selection on [document] (once) and scrolls to the remembered line; false if none. */
    fun applyPendingTo(document: ActiveDocument): Boolean {
        val saved = pending?.takeIf { pendingFor === document } ?: return false
        pending = null
        pendingFor = null
        val length = document.text.text.length
        val start = saved.selectionStart.coerceIn(0, length)
        val end = saved.selectionEnd.coerceIn(0, length)
        document.surface.select(minOf(start, end), maxOf(start, end))
        document.surface.scrollToLine(saved.anchorLine)
        return true
    }

    /** Stores how the document on screen looks now. */
    fun capture() {
        val doc = vm.active ?: return
        val state = vm.state
        // Before the Code view has shown it, the remembered selection is still the truth.
        val selection = pending?.takeIf { pendingFor === doc }
        val current = doc.surface.selection
        vm.deps.viewStates.write(
            doc.open.uri.value,
            FileViewState(
                mode = state.mode,
                codeZoom = state.codeZoom,
                previewZoom = state.previewZoom,
                selectionStart = selection?.selectionStart ?: current.start,
                selectionEnd = selection?.selectionEnd ?: current.end,
                anchorLine = vm.anchorLine.value,
            ),
        )
    }
}
