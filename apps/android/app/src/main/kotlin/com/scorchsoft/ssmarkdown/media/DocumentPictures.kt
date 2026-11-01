package com.scorchsoft.ssmarkdown.media

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
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
 * - `file:` documents (drafts, tests) resolve on the file system;
 * - documents inside a granted tree resolve by document id, which for
 *   path-based providers (on-device storage) is `root:folder/file`.
 *
 * Other providers give opaque ids that cannot be walked by path; their
 * pictures stay unresolved and the preview shows its broken-image box.
 */
class DocumentPictures(
    private val resolver: ContentResolver,
    private val documentUri: () -> String?,
    private val treeUri: () -> String?,
) : DocumentResourceResolver,
    PictureSource {
    override fun open(relativePath: String): InputStream? = resolve(relativePath)?.let(::openStream)

    override fun bytes(source: String): ByteArray? = open(source)?.use { it.readBytes() }

    private fun resolve(relative: String): Uri? {
        val doc = documentUri()
        val path = normalise(relative)
        return when {
            doc == null || path == null -> null
            doc.startsWith("file:") -> fileSibling(doc, path)
            else -> treeSibling(doc, path)
        }
    }

    /** `a/./b/../c.png` → `a/c.png`; null for remote, absolute, data or escaping paths. */
    private fun normalise(relative: String): List<String>? {
        val decoded = decode(relative.substringBefore('#').substringBefore('?'))
        if (decoded.isEmpty() || decoded.startsWith("/") || SCHEME.containsMatchIn(decoded)) return null
        val parts = ArrayList<String>()
        decoded.split('/').filterTo(parts) { it.isNotEmpty() && it != "." }
        return parts
    }

    private fun fileSibling(
        doc: String,
        path: List<String>,
    ): Uri? {
        val dir =
            doc
                .toUri()
                .path
                ?.substringBeforeLast('/')
                .orEmpty()
        return climb(dir.split('/').filter(String::isNotEmpty), path)?.let {
            Uri.fromFile(
                File("/" + it.joinToString("/")),
            )
        }
    }

    @Suppress("ReturnCount") // Each step of the id arithmetic can fail, and each failure means "not reachable".
    private fun treeSibling(
        doc: String,
        path: List<String>,
    ): Uri? {
        val tree = treeUri() ?: return null
        val docId = runCatching { DocumentsContract.getDocumentId(doc.toUri()) }.getOrNull() ?: return null
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(tree.toUri()) }.getOrNull() ?: return null
        val root = docId.substringBefore(':', "")
        if (root.isEmpty() || !docId.startsWith(treeId)) return null
        val folder =
            docId
                .substringAfter(':')
                .substringBeforeLast('/', "")
                .split('/')
                .filter(String::isNotEmpty)
        val resolved = climb(folder, path) ?: return null
        val id = "$root:" + resolved.joinToString("/")
        // Never outside the granted tree.
        if (!id.startsWith(treeId)) return null
        return DocumentsContract.buildDocumentUriUsingTree(tree.toUri(), id)
    }

    /** Applies [path] (which may hold `..`) to [folder]; null if it climbs above it. */
    private fun climb(
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

    private fun decode(text: String): String =
        try {
            URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")
        } catch (_: IllegalArgumentException) {
            text
        }

    private companion object {
        val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    }
}
