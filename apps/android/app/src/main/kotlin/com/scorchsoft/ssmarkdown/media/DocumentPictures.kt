package com.scorchsoft.ssmarkdown.media

import android.content.ContentResolver
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import com.scorchsoft.ssmarkdown.platform.BoundedRead
import com.scorchsoft.ssmarkdown.preview.DocumentResourceResolver
import com.scorchsoft.ssmarkdown.preview.ImageStatus
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.URLDecoder

/**
 * Opens pictures that sit next to a document (`![](img/diagram.png)`,
 * `../shared/logo.svg`) for the preview, the viewer and Export images, and
 * says why one cannot be shown ([status]). The app is offline, so remote
 * pictures are never fetched.
 *
 * Paths resolve against the document's own folder, and never climb out of
 * the folder the user granted (docs/ux-spec.md, "Pictures"; SEC-9):
 *
 * - a document inside a granted tree ([PictureFolders.treeFor]: folder
 *   mode, the folder granted for this file, or any folder grant that holds
 *   it) is found through the Storage Access Framework: the provider says
 *   where the document sits, and each path segment is looked up in its
 *   folder's listing by name ([TreeListings], one listing per folder). This
 *   works for providers with opaque ids (Drive, OneDrive) too;
 * - a provider that cannot say where a document sits falls back to id
 *   arithmetic for path-like ids (`root:folder/file`), kept strictly inside
 *   the tree;
 * - a document with no tree is [ImageStatus.NO_ACCESS]: the user can grant
 *   its folder from the placeholder or ⋮;
 * - drafts and `file:` documents resolve on the file system, never above
 *   their own folder.
 */
class DocumentPictures(
    private val resolver: ContentResolver,
    private val documentUri: () -> String?,
    private val folders: PictureFolders,
    private val listings: TreeListings,
    /** The file behind a draft URI, or null for anything else. */
    private val draftFile: (String) -> File? = { null },
) : DocumentResourceResolver,
    PictureSource {
    /** Where a path led: a picture to open, or why not. */
    private class Found(
        val uri: Uri?,
        val status: ImageStatus,
    )

    override fun open(relativePath: String): InputStream? =
        resolve(relativePath).takeIf { it.status == ImageStatus.OK }?.uri?.let(::openStream)

    override fun status(relativePath: String): ImageStatus = resolve(relativePath).status

    /** The picture's bytes, or null when it is missing, unreachable or larger than [MAX_PICTURE_BYTES] (SEC-8). */
    override fun bytes(source: String): ByteArray? = open(source)?.use { BoundedRead.bytes(it, MAX_PICTURE_BYTES) }

    private fun resolve(relative: String): Found {
        val doc = documentUri()
        val path = PicturePaths.normalise(relative)
        if (doc == null || path == null) return MISSING
        val local = draftFile(doc) ?: doc.takeIf { it.startsWith("file:") }?.let { File(it.toUri().path ?: "") }
        if (local != null) return fileSibling(local, path)
        val tree = folders.treeFor(doc) ?: return Found(null, ImageStatus.NO_ACCESS)
        return treeSibling(tree, doc, path)
    }

    /** A sibling of a file on disk; `..` may not leave the document's own folder. */
    private fun fileSibling(
        doc: File,
        path: List<String>,
    ): Found {
        val dir = doc.parentFile ?: return MISSING
        val file = PicturePaths.climb(emptyList(), path)?.let { File(dir, it.joinToString("/")) } ?: return MISSING
        if (!file.isFile) return MISSING
        return Found(Uri.fromFile(file), judge(mimeOf(file.name), file.length()))
    }

    private fun treeSibling(
        tree: String,
        doc: String,
        path: List<String>,
    ): Found {
        val ids = listings.pathTo(tree, doc)
        val entry =
            if (ids != null) {
                walk(tree, ids, path)
            } else {
                arithmeticSibling(tree, doc, path)
            }
        return entry?.let { Found(listings.tree.uri(tree, it.id).toUri(), judge(it.mime, it.size, it.name)) } ?: MISSING
    }

    /** Walks [path] from the document's folder through cached listings: one listing per folder. */
    private fun walk(
        tree: String,
        ids: List<String>,
        path: List<String>,
    ): TreeEntry? {
        var last: TreeEntry? = null
        val id =
            PicturePaths.walk(ids, path) { folder, name ->
                listings.child(tree, folder, name)?.also { last = it }?.id
            }
        return last?.takeIf { it.id == id }
    }

    /** Path arithmetic on `root:folder/file` ids, for providers that cannot report a document's path. */
    private fun arithmeticSibling(
        tree: String,
        doc: String,
        path: List<String>,
    ): TreeEntry? {
        val docId = listings.tree.documentId(doc) ?: return null
        val treeId = listings.tree.treeId(tree) ?: return null
        val id = PicturePaths.arithmetic(docId, treeId, path) ?: return null
        val parent = id.substringBeforeLast('/', "").ifEmpty { treeId }
        // The listing gives its type and size when the provider lists by id; otherwise judge by name.
        return listings.byId(tree, parent, id) ?: TreeEntry(id, id.substringAfterLast('/'), null, null)
    }

    private fun judge(
        mime: String?,
        size: Long?,
        name: String = "",
    ): ImageStatus {
        val type = mime?.takeUnless { it == "application/octet-stream" } ?: mimeOf(name)
        return when {
            type == null || !type.startsWith("image/") -> ImageStatus.NOT_IMAGE
            size != null && size > MAX_PICTURE_BYTES -> ImageStatus.TOO_LARGE
            else -> ImageStatus.OK
        }
    }

    private fun openStream(uri: Uri): InputStream? =
        try {
            resolver.openInputStream(uri)
        } catch (_: FileNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    companion object {
        /** The largest picture read whole into memory for the viewer or an export, or shown in Preview (SEC-8). */
        const val MAX_PICTURE_BYTES: Int = 32 * 1024 * 1024

        private val MISSING = Found(null, ImageStatus.NOT_FOUND)

        /** The MIME type a file name implies, or null. */
        fun mimeOf(name: String): String? =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
    }
}

/**
 * The pure arithmetic of relative picture paths, shared by every way of
 * resolving them and tested on its own.
 */
internal object PicturePaths {
    private val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    /** `a/./b/../c.png` as segments (`..` kept); null for remote, absolute, data or empty paths. */
    fun normalise(relative: String): List<String>? {
        val decoded = decode(relative.substringBefore('#').substringBefore('?'))
        if (decoded.isEmpty() || decoded.startsWith("/") || scheme.containsMatchIn(decoded)) return null
        return decoded.split('/').filter { it.isNotEmpty() && it != "." }.takeIf { it.isNotEmpty() }
    }

    /** Applies [path] (which may hold `..`) to [folder]; null if it climbs above it. */
    fun climb(
        folder: List<String>,
        path: List<String>,
    ): List<String>? {
        val out = ArrayList(folder)
        for (part in path) {
            if (part == "..") {
                if (out.isEmpty()) return null
                out.removeAt(out.lastIndex)
            } else {
                out.add(part)
            }
        }
        return out
    }

    /**
     * Walks [path] from the folder holding the document whose tree path is
     * [ids] (tree root first, the document last), looking each name up with
     * [child]. `..` may climb to the tree root and no further. Returns the
     * id the path names, or null.
     */
    fun walk(
        ids: List<String>,
        path: List<String>,
        child: (folder: String, name: String) -> String?,
    ): String? {
        if (ids.size < 2) return null
        val folders = ArrayList(ids.dropLast(1))
        for (part in path) {
            if (part == "..") {
                if (folders.size <= 1) return null
                folders.removeAt(folders.lastIndex)
            } else {
                folders.add(child(folders.last(), part) ?: return null)
            }
        }
        return folders.last().takeIf { folders.size > 1 }
    }

    /**
     * The id [path] names beside [docId] when ids are `root:folder/file`,
     * or null if it would leave the tree [treeId]. The check is strict: tree
     * `primary:Notes` holds `primary:Notes/a.png` but not `primary:Notes2/a.png`
     * (SEC-9); a root tree (`primary:`) holds everything on that root.
     */
    @Suppress("ReturnCount") // Each step of the id arithmetic can fail, and each failure means "not reachable".
    fun arithmetic(
        docId: String,
        treeId: String,
        path: List<String>,
    ): String? {
        val root = docId.substringBefore(':', "")
        if (root.isEmpty() || !isWithin(docId, treeId)) return null
        val folder =
            docId
                .substringAfter(':')
                .substringBeforeLast('/', "")
                .split('/')
                .filter(String::isNotEmpty)
        val resolved = climb(folder, path) ?: return null
        val id = "$root:" + resolved.joinToString("/")
        return id.takeIf { isWithin(it, treeId) && it != treeId }
    }

    /** True when [id] is [treeId] itself or below it, by whole path segments. */
    fun isWithin(
        id: String,
        treeId: String,
    ): Boolean = id == treeId || id.startsWith(if (treeId.endsWith(':') || treeId.endsWith('/')) treeId else "$treeId/")

    private fun decode(text: String): String =
        try {
            URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")
        } catch (_: IllegalArgumentException) {
            text
        }
}
