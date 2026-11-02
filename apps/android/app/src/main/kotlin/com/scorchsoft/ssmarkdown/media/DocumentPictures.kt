package com.scorchsoft.ssmarkdown.media

import android.content.ContentResolver
import android.net.Uri
import androidx.core.net.toUri
import com.scorchsoft.ssmarkdown.platform.BoundedRead
import com.scorchsoft.ssmarkdown.preview.DocumentResourceResolver
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.URLDecoder

/**
 * Opens pictures that sit next to a document (`![](img/diagram.png)`,
 * `../shared/logo.svg`) for the preview, the viewer and Export images.
 * The app is offline, so remote pictures are never fetched.
 *
 * Paths resolve against the document's own folder, and never climb out
 * of the folder the user granted (ux-spec "Small requirements": relative
 * paths resolve within the opened folder):
 *
 * - documents inside a granted tree are found through the Storage Access
 *   Framework ([DocumentTree]): the provider says where the document sits
 *   in the tree, and each path segment is looked up among its folder's
 *   children by name. This works for every provider, including those with
 *   opaque document ids (Drive, OneDrive), not only on-device storage;
 * - a provider that cannot say where a document sits falls back to id
 *   arithmetic for path-like ids (`root:folder/file`), kept strictly inside
 *   the tree (SEC-9);
 * - `file:` documents (drafts) resolve on the file system, never above
 *   their own folder.
 */
class DocumentPictures(
    private val resolver: ContentResolver,
    private val documentUri: () -> String?,
    private val treeUri: () -> String?,
    private val tree: DocumentTree = SafDocumentTree(resolver),
) : DocumentResourceResolver,
    PictureSource {
    override fun open(relativePath: String): InputStream? = resolve(relativePath)?.let(::openStream)

    /** The picture's bytes, or null when it is missing, unreachable or larger than [MAX_PICTURE_BYTES] (SEC-8). */
    override fun bytes(source: String): ByteArray? = open(source)?.use { BoundedRead.bytes(it, MAX_PICTURE_BYTES) }

    private fun resolve(relative: String): Uri? {
        val doc = documentUri()
        val path = PicturePaths.normalise(relative)
        return when {
            doc == null || path == null -> null
            doc.startsWith("file:") -> fileSibling(doc, path)
            else -> treeUri()?.let { tree -> treeSibling(tree, doc, path) }
        }
    }

    /** A sibling of a `file:` document; `..` may not leave the document's own folder. */
    private fun fileSibling(
        doc: String,
        path: List<String>,
    ): Uri? {
        val dir = File(doc.toUri().path ?: return null).parentFile ?: return null
        return PicturePaths.climb(emptyList(), path)?.let { Uri.fromFile(File(dir, it.joinToString("/"))) }
    }

    private fun treeSibling(
        tree: String,
        doc: String,
        path: List<String>,
    ): Uri? {
        val ids = this.tree.pathTo(tree, doc)
        val id =
            if (ids != null) {
                PicturePaths.walk(ids, path) { folder, name -> this.tree.child(tree, folder, name) }
            } else {
                arithmeticSibling(tree, doc, path)
            }
        return id?.let { this.tree.uri(tree, it).toUri() }
    }

    /** Path arithmetic on `root:folder/file` ids, for providers that cannot report a document's path. */
    private fun arithmeticSibling(
        tree: String,
        doc: String,
        path: List<String>,
    ): String? {
        val docId = this.tree.documentId(doc) ?: return null
        val treeId = this.tree.treeId(tree) ?: return null
        return PicturePaths.arithmetic(docId, treeId, path)
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
        /** The largest picture read whole into memory for the viewer or an export (SEC-8). */
        const val MAX_PICTURE_BYTES: Int = 32 * 1024 * 1024
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
