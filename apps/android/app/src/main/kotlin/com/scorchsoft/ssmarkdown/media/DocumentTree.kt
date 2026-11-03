package com.scorchsoft.ssmarkdown.media

import android.content.ContentResolver
import android.provider.DocumentsContract
import androidx.core.net.toUri
import java.io.FileNotFoundException

/**
 * One child of a folder in a granted tree, as one listing query reports it.
 *
 * @property mime the provider's MIME type; [DocumentsContract.Document.MIME_TYPE_DIR] for a folder.
 * @property size in bytes, or null when the provider does not say.
 */
data class TreeEntry(
    val id: String,
    val name: String,
    val mime: String?,
    val size: Long?,
) {
    val isFolder: Boolean get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
}

/**
 * What the picture code asks the Storage Access Framework, as an
 * interface so the path logic, the cache and the copy are tested against
 * an in-memory tree. Every answer is null when the provider cannot or will
 * not give it; nothing here throws.
 */
interface DocumentTree {
    /**
     * Document ids from [tree]'s root down to [document] (root first,
     * document last); null when it is not in [tree].
     */
    fun pathTo(
        tree: String,
        document: String,
    ): List<String>?

    /** Every child of [folder]: one query. */
    fun list(
        tree: String,
        folder: String,
    ): List<TreeEntry>?

    /** A URI for [documentId] that is opened through [tree]'s grant. */
    fun uri(
        tree: String,
        documentId: String,
    ): String

    /** [document]'s id. */
    fun documentId(document: String): String?

    /** The id of [tree]'s root folder. */
    fun treeId(tree: String): String?

    /** Creates a folder [name] in [parent]; its id, or null. */
    fun createFolder(
        tree: String,
        parent: String,
        name: String,
    ): String? = null

    /** Creates an empty file [name] of [mime] in [parent]; its id, or null. */
    fun createFile(
        tree: String,
        parent: String,
        mime: String,
        name: String,
    ): String? = null

    /** Deletes [documentId]; false when it could not. */
    fun delete(
        tree: String,
        documentId: String,
    ): Boolean = false
}

/**
 * [DocumentTree] over [DocumentsContract]. Lookups go through the tree
 * URI, so the provider checks every one against the user's grant. It
 * caches nothing: [TreeListings] does, so the cache is tested on its own.
 */
class SafDocumentTree(
    private val resolver: ContentResolver,
) : DocumentTree {
    override fun pathTo(
        tree: String,
        document: String,
    ): List<String>? =
        guarded {
            val id = documentId(document) ?: return@guarded null
            val inTree = DocumentsContract.buildDocumentUriUsingTree(tree.toUri(), id)
            DocumentsContract.findDocumentPath(resolver, inTree)?.path
        }

    override fun list(
        tree: String,
        folder: String,
    ): List<TreeEntry>? =
        guarded {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree.toUri(), folder)
            resolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(ID)
                        val name = cursor.getString(NAME)
                        val size = if (cursor.isNull(SIZE)) null else cursor.getLong(SIZE)
                        if (id != null && name != null) add(TreeEntry(id, name, cursor.getString(MIME), size))
                    }
                }
            }
        }

    override fun uri(
        tree: String,
        documentId: String,
    ): String = DocumentsContract.buildDocumentUriUsingTree(tree.toUri(), documentId).toString()

    override fun documentId(document: String): String? = guarded { DocumentsContract.getDocumentId(document.toUri()) }

    override fun treeId(tree: String): String? = guarded { DocumentsContract.getTreeDocumentId(tree.toUri()) }

    override fun createFolder(
        tree: String,
        parent: String,
        name: String,
    ): String? = createFile(tree, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)

    override fun createFile(
        tree: String,
        parent: String,
        mime: String,
        name: String,
    ): String? =
        guarded {
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree.toUri(), parent)
            DocumentsContract.createDocument(resolver, parentUri, mime, name)?.let(DocumentsContract::getDocumentId)
        }

    override fun delete(
        tree: String,
        documentId: String,
    ): Boolean =
        guarded {
            DocumentsContract.deleteDocument(
                resolver,
                DocumentsContract.buildDocumentUriUsingTree(tree.toUri(), documentId),
            )
        } == true

    /** Providers report "no" in many ways; every one of them means "not reachable". */
    private inline fun <T> guarded(block: () -> T?): T? =
        try {
            block()
        } catch (_: FileNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        } catch (_: IllegalStateException) {
            null
        }

    private companion object {
        const val ID = 0
        const val NAME = 1
        const val MIME = 2
        const val SIZE = 3
        val PROJECTION =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            )
    }
}
