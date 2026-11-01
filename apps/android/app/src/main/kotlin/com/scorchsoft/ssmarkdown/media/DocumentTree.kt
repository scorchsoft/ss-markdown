package com.scorchsoft.ssmarkdown.media

import android.content.ContentResolver
import android.provider.DocumentsContract
import androidx.core.net.toUri
import java.io.FileNotFoundException

/**
 * What [DocumentPictures] asks the Storage Access Framework, as an
 * interface so the path logic is tested against an in-memory tree.
 * Every answer is null when the provider cannot or will not give it.
 */
interface DocumentTree {
    /** Document ids from [tree]'s root down to [document] (root first, document last). */
    fun pathTo(
        tree: String,
        document: String,
    ): List<String>?

    /** The id of the child of [folder] whose display name is [name]. */
    fun child(
        tree: String,
        folder: String,
        name: String,
    ): String?

    /** A URI for [documentId] that is opened through [tree]'s grant. */
    fun uri(
        tree: String,
        documentId: String,
    ): String

    /** [document]'s id. */
    fun documentId(document: String): String?

    /** The id of [tree]'s root folder. */
    fun treeId(tree: String): String?
}

/**
 * [DocumentTree] over [DocumentsContract]. Lookups go through the tree
 * URI, so the provider checks every one against the user's grant.
 * Folder listings are cached for a few seconds, because a page of
 * pictures asks for the same folder many times in a burst, while a picture
 * added a moment ago still turns up on the next render.
 */
class SafDocumentTree(
    private val resolver: ContentResolver,
) : DocumentTree {
    private class Listing(
        val at: Long,
        val names: Map<String, String>,
    )

    private val children =
        object : LinkedHashMap<String, Listing>(CACHE_FOLDERS, LOAD, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Listing>): Boolean =
                size > CACHE_FOLDERS
        }

    override fun pathTo(
        tree: String,
        document: String,
    ): List<String>? =
        guarded {
            val id = documentId(document) ?: return@guarded null
            val inTree = DocumentsContract.buildDocumentUriUsingTree(tree.toUri(), id)
            DocumentsContract.findDocumentPath(resolver, inTree)?.path
        }

    override fun child(
        tree: String,
        folder: String,
        name: String,
    ): String? {
        val key = "$tree\n$folder"
        val now = System.nanoTime()
        val cached = synchronized(children) { children[key] }?.takeIf { now - it.at < LISTING_TTL_NANOS }
        val listing =
            cached
                ?: list(tree, folder)?.let { Listing(now, it) }?.also { synchronized(children) { children[key] = it } }
        return listing?.names?.get(name)
    }

    override fun uri(
        tree: String,
        documentId: String,
    ): String = DocumentsContract.buildDocumentUriUsingTree(tree.toUri(), documentId).toString()

    override fun documentId(document: String): String? = guarded { DocumentsContract.getDocumentId(document.toUri()) }

    override fun treeId(tree: String): String? = guarded { DocumentsContract.getTreeDocumentId(tree.toUri()) }

    /** Display name to document id for [folder]'s children. */
    private fun list(
        tree: String,
        folder: String,
    ): Map<String, String>? =
        guarded {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree.toUri(), folder)
            val projection =
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0)
                        val name = cursor.getString(1)
                        if (id != null && name != null) putIfAbsent(name, id)
                    }
                }
            }
        }

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
        const val CACHE_FOLDERS = 32
        const val LOAD = 0.75f
        const val LISTING_TTL_NANOS = 5_000_000_000L
    }
}
