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

    /**
     * Which document a step belongs to: its URI and the binding it was
     * shown in. Every switch is a new binding, so a flow begun in a file the
     * user left (even one they have since come back to) never reaches the
     * one on screen (edge-cases "Insert image across a file switch").
     */
    private data class Origin(
        val document: String,
        val binding: Int,
    )

    /** Where the picture goes, in which document, while the picker is open. */
    private class Pending(
        val origin: Origin,
        val selection: TextSelection?,
    )

    private var pending: Pending? = null

    /** Counts the documents shown; see [Origin]. */
    private var binding = 0

    /** A copy waiting for the folder grant it needs. */
    private var copyAfterGrant: Pair<Origin, String>? = null

    /** The document the folder picker was opened for. */
    private var folderFor: Origin? = null

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
        val origin = origin() ?: return
        copyAfterGrant = null
        pending = Pending(origin, selection)
        host.emit(EditorEvent.PickImage(access.initialFolder(origin.document)))
    }

    /**
     * Another document is now on screen (or the same one shown afresh).
     * Anything Insert image was doing belongs to the one before: a prompt
     * still showing is taken away with a word, and every later step finds
     * its [Origin] out of date and stops.
     */
    fun onDocumentSwitched() {
        binding++
        if (host.state.overlay is EditorOverlay.CopyPicture) {
            pending = null
            host.update { it.copy(overlay = null) }
            host.message(MessageKind.PictureInsertStopped)
        }
    }

    /** The picker answered; null when cancelled. */
    fun onImagePicked(picked: String?) {
        val wanted = pending ?: return
        if (picked == null) {
            pending = null
            return
        }
        if (!isCurrent(wanted.origin)) return stopped()
        host.deps.scope.launch {
            val placement = withContext(host.deps.io) { access.place(wanted.origin.document, picked) }
            // Linking an existing picture writes nothing, so a switch while it was looked up just stops.
            if (!isCurrent(wanted.origin)) return@launch stopped()
            onPlacement(wanted.origin, placement, picked)
        }
    }

    /** "Copy into this file's folder" on the prompt. */
    fun copyIn(picked: String) {
        val origin = pending?.origin ?: return
        if (!isCurrent(origin)) return stopped()
        host.deps.scope.launch {
            val placement = withContext(host.deps.io) { access.copyIn(origin.document, picked) }
            if (isCurrent(origin)) {
                onPlacement(origin, placement, picked)
            } else if (placement is Placement.Ready) {
                // The copy is on disk but its link has nowhere to go: say where the picture went.
                pending = null
                host.message(MessageKind.PictureCopiedNotLinked, placement.path)
            } else {
                stopped()
            }
        }
    }

    /** "Allow access" on a placeholder, or ⋮ → Show pictures: the folder picker, in the document's folder. */
    fun askForFolder() {
        val origin = origin() ?: return
        copyAfterGrant = null
        requestFolder(origin)
    }

    private fun requestFolder(origin: Origin) {
        folderFor = origin
        host.emit(EditorEvent.PickPictureFolder(access.initialFolder(origin.document)))
    }

    /**
     * The folder picker answered (null when cancelled). The folder is used
     * only if it holds the document it was asked for; an unrelated one is
     * explained and its grant given back. A copy waiting for it goes ahead
     * only if that document is still the one on screen.
     */
    fun onFolderPicked(tree: String?) {
        // No request on record (the process was recreated while the picker was up): it is for the file on screen.
        val asked = folderFor ?: origin()
        val waiting = copyAfterGrant
        folderFor = null
        copyAfterGrant = null
        if (tree == null || asked == null) {
            if (waiting != null) pending = null
            return
        }
        host.deps.scope.launch {
            // The grant is for the folder of the document it was asked for, wherever the user is now.
            val granted = withContext(host.deps.io) { access.grantFolder(asked.document, tree) }
            if (!isCurrent(asked)) {
                if (waiting != null) stopped()
                return@launch
            }
            if (!granted) {
                if (waiting != null) pending = null
                host.message(MessageKind.PictureFolderUnrelated)
                return@launch
            }
            host.update { it.copy(preview = it.preview.copy(picturesNeedFolder = false)) }
            host.preview.view?.refreshImages()
            if (waiting != null && waiting.first == asked) {
                copyIn(waiting.second)
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
        origin: Origin,
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
                copyAfterGrant = origin to picked
                requestFolder(origin)
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

    /** Insert image gave up because another file opened; nothing is written into either document. */
    private fun stopped() = done(MessageKind.PictureInsertStopped)

    private fun done(kind: MessageKind) {
        pending = null
        host.message(kind)
    }

    /** `![alt](path)` over the remembered selection, one undo step, with the alt text selected. */
    private fun insertMarkup(placement: Placement.Ready) {
        val doc = host.active ?: return
        val wanted = pending ?: return
        if (!isCurrent(wanted.origin)) return
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

    private fun origin(): Origin? =
        host.active
            ?.open
            ?.uri
            ?.value
            ?.let { Origin(it, binding) }

    private fun isCurrent(origin: Origin): Boolean = origin == origin()
}
