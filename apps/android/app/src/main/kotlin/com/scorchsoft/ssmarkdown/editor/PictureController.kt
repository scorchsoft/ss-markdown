package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.folder.FolderNames
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.media.PictureMarkup
import com.scorchsoft.ssmarkdown.media.Placement
import com.scorchsoft.ssmarkdown.preview.editing.FormatAction
import com.scorchsoft.ssmarkdown.text.EditOp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pictures beside the document (docs/ux-spec.md, "Pictures"): Insert image
 * from the strip or ⋮, in Code and Preview, and "Allow access" for a file
 * opened on its own, whose folder the app cannot see yet (ADR 0018).
 *
 * Insert image opens the system picker in the document's folder. A picture
 * already inside the document's granted tree is linked by its relative
 * path; one outside it (or with no tree yet) is offered "Copy into this
 * file's folder", which copies it into `images/` beside the document,
 * asking for the folder first when the app cannot write there. A draft
 * copies into the drafts folder's own `images/`. The text change is one
 * undo step, with the alt text selected so typing replaces it. A raw
 * `content://` URI is never written into a document.
 */
@Suppress("TooManyFunctions") // One small function per step of two flows (insert, allow access).
class PictureController(
    private val host: EditorHost,
) {
    private val access get() = host.deps.pictureAccess

    /** Where the picture goes, in which document, while the picker is open. */
    private class Pending(
        val document: String,
        val selection: TextSelection?,
    )

    private var pending: Pending? = null

    /** A copy waiting for the folder grant it needs. */
    private var copyAfterGrant: String? = null

    /** ⋮ → Insert image…: at the page's selection while Preview has the caret, else the editor's. */
    fun insertHere() {
        if (host.state.previewTyping) {
            host.preview.format(FormatAction.IMAGE)
        } else {
            insert(host.active?.surface?.selection)
        }
    }

    /** Opens the picker; the picture goes at [selection] (host offsets) when it comes back. */
    fun insert(selection: TextSelection?) {
        val doc = host.active ?: return
        doc.surface.flushComposing()
        val uri = doc.open.uri.value
        pending = Pending(uri, selection)
        host.emit(EditorEvent.PickImage(access.initialFolder(uri)))
    }

    /** The picker answered; null when cancelled. */
    fun onImagePicked(picked: String?) {
        val wanted = pending
        if (picked == null || wanted == null || wanted.document != documentUri()) {
            if (picked == null) pending = null
            return
        }
        host.deps.scope.launch {
            val placement = withContext(host.deps.io) { access.place(wanted.document, picked) }
            onPlacement(placement, picked)
        }
    }

    /** "Copy into this file's folder" on the prompt. */
    fun copyIn(picked: String) {
        val document = pending?.document ?: return
        if (document != documentUri()) return
        host.deps.scope.launch {
            val placement = withContext(host.deps.io) { access.copyIn(document, picked) }
            onPlacement(placement, picked)
        }
    }

    /** "Allow access" on a placeholder, or ⋮ → Show pictures: the folder picker, in the document's folder. */
    fun askForFolder() {
        val uri = documentUri() ?: return
        host.emit(EditorEvent.PickPictureFolder(access.initialFolder(uri)))
    }

    /**
     * The folder picker answered (null when cancelled). The folder is used
     * only if it holds the document; an unrelated one is explained and its
     * grant given back.
     */
    fun onFolderPicked(tree: String?) {
        val document = documentUri()
        val waiting = copyAfterGrant
        copyAfterGrant = null
        if (tree == null || document == null) return
        host.deps.scope.launch {
            val granted = withContext(host.deps.io) { access.grantFolder(document, tree) }
            if (document != documentUri()) return@launch
            if (!granted) {
                host.message(MessageKind.PictureFolderUnrelated)
                return@launch
            }
            host.update { it.copy(preview = it.preview.copy(picturesNeedFolder = false)) }
            host.preview.view?.refreshImages()
            if (waiting !=
                null
            ) {
                copyIn(waiting)
            } else {
                host.message(MessageKind.PictureFolderGranted, FolderNames.nameOf(tree))
            }
        }
    }

    /** The page said whether any picture needs the document's folder. */
    fun onStatuses(needFolder: Boolean) {
        if (needFolder != host.state.preview.picturesNeedFolder) {
            host.update { it.copy(preview = it.preview.copy(picturesNeedFolder = needFolder)) }
        }
    }

    /** The folder panel refreshed: pictures are looked up afresh. */
    fun onFolderRefreshed() {
        host.deps.scope.launch {
            withContext(host.deps.io) { access.invalidate() }
            host.preview.view?.refreshImages()
        }
    }

    private fun onPlacement(
        placement: Placement,
        picked: String,
    ) {
        when (placement) {
            is Placement.Ready -> {
                insertMarkup(placement)
                pending = null
            }

            Placement.Outside -> {
                host.update { it.copy(overlay = EditorOverlay.CopyPicture(picked)) }
            }

            Placement.NeedsFolder -> {
                copyAfterGrant = picked
                askForFolder()
            }

            Placement.TooLarge -> {
                done(MessageKind.PictureTooLarge)
            }

            Placement.NotImage -> {
                done(MessageKind.PictureNotImage)
            }

            Placement.Failed -> {
                done(MessageKind.PictureFailed)
            }
        }
    }

    private fun done(kind: MessageKind) {
        pending = null
        host.message(kind)
    }

    /** `![alt](path)` over the remembered selection, one undo step, with the alt text selected. */
    private fun insertMarkup(placement: Placement.Ready) {
        val doc = host.active ?: return
        val wanted = pending ?: return
        if (doc.open.uri.value != wanted.document) return
        val surface = doc.surface
        surface.flushComposing()
        val text = doc.text.snapshot()
        val chosen = wanted.selection ?: surface.selection
        val start = chosen.start.coerceIn(0, text.length)
        val end = chosen.end.coerceIn(start, text.length)
        val markup = PictureMarkup.image(placement.alt, placement.path)
        val altStart = start + 2
        val altEnd = altStart + PictureMarkup.escapeAlt(placement.alt).length
        surface.apply(listOf(EditOp(start, text.subSequence(start, end), markup)), TextSelection(altStart, altEnd))
        host.refresh()
    }

    private fun documentUri(): String? =
        host.active
            ?.open
            ?.uri
            ?.value
}
