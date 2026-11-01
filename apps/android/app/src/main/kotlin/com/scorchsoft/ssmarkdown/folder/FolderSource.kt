package com.scorchsoft.ssmarkdown.folder

import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.scorchsoft.ssmarkdown.files.document.DocumentStore
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.provider.ProviderResult
import com.scorchsoft.ssmarkdown.files.saf.DocumentActions
import com.scorchsoft.ssmarkdown.files.saf.FolderLister
import com.scorchsoft.ssmarkdown.files.saf.FolderRow
import com.scorchsoft.ssmarkdown.files.saf.rootDocumentIdOf

/**
 * Everything folder mode asks of the file system. Blocking: call from the
 * I/O dispatcher. An interface so the folder controller is tested with a
 * fake tree instead of a `DocumentsProvider`.
 */
interface FolderSource {
    /** The tree's rows, marking files [store] has open. */
    fun list(
        treeUri: String,
        store: DocumentStore?,
        showAllFiles: Boolean,
    ): ProviderResult<List<FolderRow>>

    /** The tree's root as a document URI, for "New file here". */
    fun rootOf(treeUri: String): String

    /** Creates [name] in [parent]. */
    fun create(
        parent: String,
        name: String,
    ): ProviderResult<DocumentUri>

    /** Renames [uri]; the provider may hand back a new URI. */
    fun rename(
        uri: String,
        name: String,
    ): ProviderResult<DocumentUri>

    /** Copies [uri] into [parent] as [name]. */
    fun duplicate(
        uri: String,
        parent: String,
        name: String,
    ): ProviderResult<DocumentUri>

    /** Deletes [uri]. */
    fun delete(uri: String): ProviderResult<Boolean>
}

/** [FolderSource] over the Storage Access Framework. */
class SafFolderSource(
    private val lister: FolderLister,
    private val actions: DocumentActions,
) : FolderSource {
    override fun list(
        treeUri: String,
        store: DocumentStore?,
        showAllFiles: Boolean,
    ): ProviderResult<List<FolderRow>> {
        val tree = DocumentUri(treeUri)
        return lister.list(tree, rootDocumentIdOf(tree), store, showAllFiles)
    }

    override fun rootOf(treeUri: String): String {
        val tree = treeUri.toUri()
        return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)).toString()
    }

    override fun create(
        parent: String,
        name: String,
    ) = actions.create(DocumentUri(parent), name)

    override fun rename(
        uri: String,
        name: String,
    ) = actions.rename(DocumentUri(uri), name)

    override fun duplicate(
        uri: String,
        parent: String,
        name: String,
    ) = actions.duplicate(DocumentUri(uri), DocumentUri(parent), name)

    override fun delete(uri: String) = actions.delete(DocumentUri(uri))
}
