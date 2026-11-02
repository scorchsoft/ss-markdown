package com.scorchsoft.ssmarkdown.files.saf

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.provider.FileMetadata
import com.scorchsoft.ssmarkdown.files.provider.ProviderResult
import com.scorchsoft.ssmarkdown.files.provider.providerCall

/** [TreeSource] over a folder the user picked with `ACTION_OPEN_DOCUMENT_TREE`. */
class ContentResolverTreeSource(
    private val resolver: ContentResolver,
) : TreeSource {
    override fun children(
        treeUri: DocumentUri,
        parentDocumentId: String,
    ): ProviderResult<List<TreeEntry>> =
        providerCall {
            val tree = Uri.parse(treeUri.value)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocumentId)
            resolver.query(childrenUri, COLUMNS, null, null, null)?.use { cursor -> rows(cursor, tree) }.orEmpty()
        }

    private fun rows(
        cursor: Cursor,
        tree: Uri,
    ): List<TreeEntry> {
        val entries = ArrayList<TreeEntry>(cursor.count.coerceAtLeast(0))
        while (cursor.moveToNext()) {
            val id = cursor.getString(ID) ?: continue
            val mime = cursor.getString(MIME)
            entries +=
                TreeEntry(
                    uri = DocumentUri(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()),
                    documentId = id,
                    name = cursor.getString(NAME).orEmpty(),
                    isDirectory = mime == Document.MIME_TYPE_DIR,
                    size = if (cursor.isNull(SIZE)) FileMetadata.UNKNOWN_SIZE else cursor.getLong(SIZE),
                    lastModified = if (cursor.isNull(MODIFIED)) 0 else cursor.getLong(MODIFIED),
                    mimeType = mime,
                )
        }
        return entries
    }

    private companion object {
        val COLUMNS =
            arrayOf(
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE,
                Document.COLUMN_LAST_MODIFIED,
            )
        const val ID = 0
        const val NAME = 1
        const val MIME = 2
        const val SIZE = 3
        const val MODIFIED = 4
    }
}

/** The document id of a picked tree's root, for [FolderLister.list]. */
fun rootDocumentIdOf(treeUri: DocumentUri): String = DocumentsContract.getTreeDocumentId(Uri.parse(treeUri.value))
