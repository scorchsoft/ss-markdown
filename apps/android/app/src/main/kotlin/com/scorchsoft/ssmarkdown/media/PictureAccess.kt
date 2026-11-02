package com.scorchsoft.ssmarkdown.media

import android.content.ContentResolver
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.net.toUri
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Where a picture picked for Insert image ended up, and what the editor does next. */
sealed interface Placement {
    /** Write `![alt](path)`: [path] is relative to the document and already encoded for Markdown. */
    data class Ready(
        val path: String,
        val alt: String,
    ) : Placement

    /** The picture is not in the document's folder tree: offer to copy it there. */
    data object Outside : Placement

    /** Copying needs the document's folder, which the app cannot write yet: ask for it. */
    data object NeedsFolder : Placement

    /** Larger than a picture the app copies (SEC-8). */
    data object TooLarge : Placement

    /** Not a picture. */
    data object NotImage : Placement

    /** The provider refused or the copy failed; nothing was left half-written. */
    data object Failed : Placement
}

/**
 * Everything Insert image and "Allow access" need from storage, behind one
 * interface so the editor's flow is tested without a provider. Every call
 * may block: the editor runs them on its I/O dispatcher.
 */
interface PictureAccess {
    /**
     * Where a system picker should start for [document]: its folder when the
     * URI says (`primary:Notes/a.md` starts in `primary:Notes`), otherwise
     * the document itself, which most pickers open in its folder.
     */
    fun initialFolder(document: String): String? = null

    /**
     * The user picked [tree] for [document]'s pictures: keeps the grant and
     * remembers the folder if it holds the document. False when it does not
     * (an unrelated folder), and its grant is given back.
     */
    fun grantFolder(
        document: String,
        tree: String,
    ): Boolean = false

    /** [picked], if it already sits in [document]'s tree: its relative path. */
    fun place(
        document: String,
        picked: String,
    ): Placement = Placement.Outside

    /** Copies [picked] into an `images/` folder beside [document], under a name that is free. */
    fun copyIn(
        document: String,
        picked: String,
    ): Placement = Placement.Failed

    /** Storage may have changed (the folder panel refreshed): forget cached listings. */
    fun invalidate() = Unit
}

/** A picked picture as its provider describes it. */
data class PickedFile(
    val name: String,
    val mime: String,
    val size: Long?,
) {
    val isImage: Boolean get() = mime.startsWith("image/")
}

/** No storage at all: tests that do not insert pictures. */
object NoPictureAccess : PictureAccess

/**
 * [PictureAccess] over the Storage Access Framework, the drafts folder and
 * the user's persisted grants. Reads are bounded (SEC-8) and paths never
 * climb out of the granted tree (SEC-9); a raw `content://` URI is never
 * written into a document.
 */
@Suppress("LongParameterList", "TooManyFunctions") // Storage collaborators injected for tests; one function per step.
class SafPictureAccess(
    private val resolver: ContentResolver,
    private val folders: PictureFolders,
    private val listings: TreeListings,
    private val draftFile: (String) -> File?,
    private val takeGrant: (tree: String) -> Boolean,
    private val releaseGrant: (tree: String) -> Unit,
    /** The picked file's name, type and size; the provider's answer by default (a test swaps it). */
    private val describe: ((String) -> PickedFile?)? = null,
) : PictureAccess {
    private val tree get() = listings.tree

    override fun initialFolder(document: String): String? = InitialFolder.of(document)

    override fun grantFolder(
        document: String,
        tree: String,
    ): Boolean {
        if (!takeGrant(tree)) return false
        val linked = folders.link(document, tree)
        if (!linked) releaseGrant(tree)
        return linked
    }

    override fun place(
        document: String,
        picked: String,
    ): Placement {
        val info = picked(picked) ?: return Placement.Failed
        if (!info.isImage) return Placement.NotImage
        if (draftFile(document) != null || document.startsWith("file:")) return Placement.Outside
        val tree = folders.treeFor(document)?.takeIf { authority(it) == authority(picked) } ?: return Placement.Outside
        val docIds = listings.pathTo(tree, document) ?: return Placement.Outside
        val imageIds = listings.pathTo(tree, picked) ?: return Placement.Outside
        val segments =
            PictureMarkup.between(docIds, imageIds) { parent, id -> listings.byId(tree, parent, id)?.name }
                ?: return Placement.Outside
        return Placement.Ready(PictureMarkup.path(segments), PictureMarkup.altFor(segments.last()))
    }

    override fun copyIn(
        document: String,
        picked: String,
    ): Placement {
        val info = picked(picked) ?: return Placement.Failed
        return when {
            !info.isImage -> Placement.NotImage
            info.size != null && info.size > DocumentPictures.MAX_PICTURE_BYTES -> Placement.TooLarge
            else -> copyChecked(document, picked, info)
        }
    }

    private fun copyChecked(
        document: String,
        picked: String,
        info: PickedFile,
    ): Placement {
        val local =
            draftFile(document) ?: document.takeIf { it.startsWith("file:") }?.let { File(it.toUri().path ?: "") }
        if (local != null) return copyToFile(local, picked, info)
        val tree = folders.treeFor(document) ?: return Placement.NeedsFolder
        val docIds = listings.pathTo(tree, document)?.takeIf { it.size >= 2 } ?: return Placement.NeedsFolder
        return copyToTree(tree, docIds[docIds.size - 2], picked, info)
    }

    override fun invalidate() = folders.invalidate()

    private fun copyToFile(
        doc: File,
        picked: String,
        info: PickedFile,
    ): Placement {
        val dir = File(doc.parentFile ?: return Placement.Failed, IMAGES)
        if (!dir.isDirectory && !dir.mkdirs()) return Placement.Failed
        val name = PictureMarkup.freeName(safeName(info.name), dir.list().orEmpty().toSet())
        val target = File(dir, name)
        val copied =
            try {
                copy(picked) { FileOutputStream(target) }
            } catch (_: IOException) {
                false
            }
        if (copied != true) {
            target.delete()
            return if (copied == null) Placement.TooLarge else Placement.Failed
        }
        return Placement.Ready(PictureMarkup.path(listOf(IMAGES, name)), PictureMarkup.altFor(name))
    }

    private fun copyToTree(
        tree: String,
        folder: String,
        picked: String,
        info: PickedFile,
    ): Placement {
        val existing = listings.child(tree, folder, IMAGES)
        val images =
            existing?.takeIf { it.isFolder }?.id
                ?: this.tree.createFolder(tree, folder, IMAGES)
                ?: return Placement.NeedsFolder
        listings.invalidate()
        val taken = listings.children(tree, images)?.keys.orEmpty()
        val wanted = PictureMarkup.freeName(safeName(info.name), taken)
        val id = this.tree.createFile(tree, images, info.mime, wanted) ?: return Placement.NeedsFolder
        val copied = copy(picked) { resolver.openOutputStream(this.tree.uri(tree, id).toUri(), "w") }
        if (copied != true) {
            this.tree.delete(tree, id)
            listings.invalidate()
            return if (copied == null) Placement.TooLarge else Placement.Failed
        }
        listings.invalidate()
        // The provider may have adjusted the name; the link uses the one it kept.
        val name = listings.byId(tree, images, id)?.name ?: wanted
        val folderName = listings.byId(tree, folder, images)?.name ?: IMAGES
        return Placement.Ready(PictureMarkup.path(listOf(folderName, name)), PictureMarkup.altFor(name))
    }

    /**
     * Streams [picked] into [target]: true when copied, false when it
     * failed, null when it passed [DocumentPictures.MAX_PICTURE_BYTES] (a
     * provider that reported no size, or the wrong one; SEC-8).
     */
    private fun copy(
        picked: String,
        target: () -> OutputStream?,
    ): Boolean? =
        try {
            // Not `?: false` after `use`: null from the pump means "too large", not "no stream".
            val input = resolver.openInputStream(picked.toUri())
            if (input == null) false else input.use { pumpTo(it, target) }
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }

    /** [pump] into [target]'s stream; false when there is none. */
    private fun pumpTo(
        input: InputStream,
        target: () -> OutputStream?,
    ): Boolean? {
        val output = target() ?: return false
        return output.use { pump(input, it) }
    }

    private fun pump(
        input: InputStream,
        output: OutputStream,
    ): Boolean? {
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return true
            total += n
            if (total > DocumentPictures.MAX_PICTURE_BYTES) return null
            output.write(buffer, 0, n)
        }
    }

    private fun picked(uri: String): PickedFile? = describe?.invoke(uri) ?: query(uri)

    /** The picked file's name, type and size, as its provider reports them. */
    private fun query(uri: String): PickedFile? =
        try {
            val parsed = uri.toUri()
            val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            resolver.query(parsed, projection, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val name = c.getString(0) ?: parsed.lastPathSegment?.substringAfterLast('/') ?: DEFAULT_NAME
                val size = if (c.isNull(1)) null else c.getLong(1)
                val mime =
                    resolver.getType(parsed)?.takeUnless { it == "application/octet-stream" }
                        ?: DocumentPictures.mimeOf(name)
                        ?: ""
                PickedFile(name, mime, size)
            }
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: FileNotFoundException) {
            null
        }

    private companion object {
        const val IMAGES = "images"
        const val DEFAULT_NAME = "picture.png"
        const val BUFFER = 64 * 1024

        /** A provider's display name made safe as one file name: no separators, no leading dots. */
        fun safeName(name: String): String =
            name
                .replace(Regex("[/\\\\\\u0000-\\u001f]"), "_")
                .trimStart('.')
                .take(MAX_NAME)
                .ifBlank { DEFAULT_NAME }

        const val MAX_NAME = 120

        fun authority(uri: String): String = uri.removePrefix("content://").substringBefore('/')
    }
}

/**
 * Where a system picker starts for a document (`EXTRA_INITIAL_URI`). For a
 * provider whose ids are paths (`primary:Notes/a.md`, on-device storage and
 * SD cards), the document's folder; otherwise the document itself, which
 * most pickers open in its folder. Pure string work: no provider call.
 */
object InitialFolder {
    /** The URI to start in for [document], or null for a draft or a file. */
    fun of(document: String): String? {
        if (!document.startsWith("content://")) return null
        val uri = document.toUri()
        val id =
            try {
                DocumentsContract.getDocumentId(uri)
            } catch (_: IllegalArgumentException) {
                return document
            }
        val parent = id.substringBeforeLast('/', "")
        if (!id.contains(':') || parent.isEmpty() || parent.endsWith(':')) return document
        val authority = uri.authority ?: return document
        return DocumentsContract.buildDocumentUri(authority, parent).toString()
    }
}
