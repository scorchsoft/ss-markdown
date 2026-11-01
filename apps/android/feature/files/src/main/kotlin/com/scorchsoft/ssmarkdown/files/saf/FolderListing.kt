package com.scorchsoft.ssmarkdown.files.saf

import com.scorchsoft.ssmarkdown.files.document.DocumentStore
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.provider.FileMetadata
import com.scorchsoft.ssmarkdown.files.provider.ProviderResult
import java.util.Locale

/**
 * One child of a folder as the provider lists it.
 *
 * @property uri the child's document URI (built from the tree).
 * @property documentId the provider's id, for listing a subfolder.
 * @property name the display name.
 * @property isDirectory true for a folder.
 * @property size bytes, or [FileMetadata.UNKNOWN_SIZE].
 * @property lastModified epoch millis, or 0.
 * @property mimeType the provider's type.
 */
data class TreeEntry(
    val uri: DocumentUri,
    val documentId: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val mimeType: String?,
)

/** Lists one folder's children. [ContentResolverTreeSource] is the real one. */
fun interface TreeSource {
    /** The children of [parentDocumentId] in the tree [treeUri]. Blocking. */
    fun children(
        treeUri: DocumentUri,
        parentDocumentId: String,
    ): ProviderResult<List<TreeEntry>>
}

/**
 * A row of the folder drawer (ux-spec "Folder mode").
 *
 * @property entry what the provider said.
 * @property depth nesting below the opened folder, for indentation.
 * @property dirty the file is open with unsaved edits: the dot.
 * @property changedOutside the file is open and the provider's metadata no longer matches what we
 *   loaded: the "changed" marker. A hint only; the document's own check decides.
 */
data class FolderRow(
    val entry: TreeEntry,
    val depth: Int,
    val dirty: Boolean,
    val changedOutside: Boolean,
)

/**
 * Builds the folder drawer: folders first, then Markdown files, each
 * sorted by name ignoring case; hidden entries skipped; everything else
 * only with [showAllFiles]. Walks at most [maxDepth] levels and
 * [maxEntries] rows, so a huge tree cannot stall the drawer.
 */
class FolderLister(
    private val source: TreeSource,
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    /** Lists [treeUri] from [rootDocumentId], marking files [store] has open. Blocking. */
    fun list(
        treeUri: DocumentUri,
        rootDocumentId: String,
        store: DocumentStore?,
        showAllFiles: Boolean = false,
    ): ProviderResult<List<FolderRow>> {
        val walk = Walk(treeUri, showAllFiles, store)
        val failure = walk.folder(rootDocumentId, depth = 0)
        return failure ?: ProviderResult.Ok(walk.rows)
    }

    /** One listing in progress: the settings that do not change while walking, and the rows so far. */
    private inner class Walk(
        val treeUri: DocumentUri,
        val showAllFiles: Boolean,
        val store: DocumentStore?,
    ) {
        val rows = ArrayList<FolderRow>()

        /** Adds [parentId]'s children and, depth allowing, theirs. A failure only matters at the root. */
        fun folder(
            parentId: String,
            depth: Int,
        ): ProviderResult.Failure? {
            val children =
                when (val result = source.children(treeUri, parentId)) {
                    is ProviderResult.Ok -> result.value
                    is ProviderResult.Failure -> return result.takeIf { depth == 0 }
                }
            for (entry in sorted(children.filter { visible(it, showAllFiles) })) {
                if (rows.size >= maxEntries) break
                rows += row(entry, depth, store)
                if (entry.isDirectory && depth + 1 < maxDepth) folder(entry.documentId, depth + 1)
            }
            return null
        }
    }

    private fun row(
        entry: TreeEntry,
        depth: Int,
        store: DocumentStore?,
    ): FolderRow {
        val open = store?.get(entry.uri)
        val base = open?.base
        val changed =
            base != null && !entry.isDirectory &&
                (entry.size != base.size || (entry.lastModified != 0L && entry.lastModified != base.lastModified))
        return FolderRow(entry, depth, dirty = open?.isDirty == true, changedOutside = changed)
    }

    private fun visible(
        entry: TreeEntry,
        showAllFiles: Boolean,
    ): Boolean =
        !entry.name.startsWith(".") &&
            (entry.isDirectory || showAllFiles || MarkdownFiles.hasMarkdownExtension(entry.name))

    private fun sorted(entries: List<TreeEntry>): List<TreeEntry> =
        entries.sortedWith(compareBy<TreeEntry>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))

    /** Limits. */
    companion object {
        /** Folder levels walked. */
        const val DEFAULT_MAX_DEPTH: Int = 8

        /** Rows returned. */
        const val DEFAULT_MAX_ENTRIES: Int = 5_000
    }
}

/** How many unsaved files sit under each folder: the collapsed folder's "n unsaved". */
fun List<FolderRow>.unsavedCountsByFolder(): Map<DocumentUri, Int> {
    val counts = HashMap<DocumentUri, Int>()
    val parents = ArrayList<FolderRow>()
    for (row in this) {
        while (parents.isNotEmpty() && parents.last().depth >= row.depth) parents.removeAt(parents.lastIndex)
        if (row.dirty) parents.forEach { counts.merge(it.entry.uri, 1, Int::plus) }
        if (row.entry.isDirectory) parents += row
    }
    return counts
}
