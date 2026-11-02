package com.scorchsoft.ssmarkdown.files.drafts

import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * A draft as the Navigator's Drafts list shows it.
 *
 * @property uri the draft's address.
 * @property title its first non-blank line, without heading marks; empty for an empty draft.
 * @property modifiedMillis when it was last written.
 * @property ageMillis how long ago that was, from the store's clock.
 * @property sizeBytes its size.
 */
data class DraftSummary(
    val uri: DocumentUri,
    val title: String,
    val modifiedMillis: Long,
    val ageMillis: Long,
    val sizeBytes: Long,
)

/**
 * Untitled documents, kept as plain UTF-8 files in app storage
 * (ux-spec "Saving and safety": untitled documents autosave to drafts).
 *
 * The store only names, lists and deletes them. Reading and writing go
 * through the normal pipeline via [com.scorchsoft.ssmarkdown.files.provider.FileDocumentProvider]
 * with [resolve], so a draft is backed up, verified and journalled
 * exactly like a file. Drafts are never touched by any clean-up.
 */
class DraftStore(
    val root: File,
    private val nowMillis: () -> Long,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** Creates an empty draft and returns its URI, or null if app storage refused. */
    fun create(): DocumentUri? {
        val uri = DocumentUri("${DocumentUri.DRAFT_SCHEME}:${newId()}")
        val file = resolve(uri) ?: return null
        return try {
            root.mkdirs()
            if (file.createNewFile()) uri else null
        } catch (_: IOException) {
            null
        }
    }

    /** The file behind [uri], or null if [uri] is not a well-formed draft address. */
    fun resolve(uri: DocumentUri): File? {
        if (!uri.isDraft) return null
        val id = uri.value.substringAfter(':')
        return if (ID_PATTERN.matches(id)) File(root, "$id$EXTENSION") else null
    }

    /** Every draft, newest first. */
    fun list(): List<DraftSummary> {
        val now = nowMillis()
        return root
            .listFiles { file -> file.isFile && file.name.endsWith(EXTENSION) }
            .orEmpty()
            .map { file ->
                DraftSummary(
                    uri = DocumentUri("${DocumentUri.DRAFT_SCHEME}:${file.name.removeSuffix(EXTENSION)}"),
                    title = titleOf(file),
                    modifiedMillis = file.lastModified(),
                    ageMillis = (now - file.lastModified()).coerceAtLeast(0),
                    sizeBytes = file.length(),
                )
            }.sortedByDescending { it.modifiedMillis }
    }

    /** Deletes a draft (the Drafts list's own delete; no clean-up ever calls this). */
    fun delete(uri: DocumentUri): Boolean = resolve(uri)?.delete() == true

    /**
     * Deletes a draft so that [restore] can bring it back: the file moves
     * into a trash folder the list never shows (DS-16, UX-1). Only the most
     * recent deletion is undoable; an older one is emptied now. Returns
     * false if the draft could not be moved.
     */
    fun trash(uri: DocumentUri): Boolean {
        val file = resolve(uri)?.takeIf { it.isFile } ?: return false
        val trash = File(root, TRASH)
        trash.listFiles()?.forEach { it.delete() }
        if (!trash.isDirectory && !trash.mkdirs()) return false
        return file.renameTo(File(trash, file.name))
    }

    /** Undoes [trash] for [uri], if it was the most recent deletion. */
    fun restore(uri: DocumentUri): Boolean {
        val target = resolve(uri) ?: return false
        val trashed = File(File(root, TRASH), target.name)
        return trashed.isFile && !target.exists() && trashed.renameTo(target)
    }

    /** Reads at most the first [HEAD_CHARS] chars, so a draft that is one huge line costs nothing to list. */
    private fun titleOf(file: File): String =
        try {
            val head = CharArray(HEAD_CHARS)
            val read = file.bufferedReader().use { it.read(head) }.coerceAtLeast(0)
            String(head, 0, read)
                .lineSequence()
                .map { it.trim().trimStart('#').trim() }
                .firstOrNull { it.isNotEmpty() }
                ?.take(MAX_TITLE_CHARS)
                .orEmpty()
        } catch (_: IOException) {
            ""
        }

    private companion object {
        const val EXTENSION = ".md"
        const val TRASH = ".trash"
        const val HEAD_CHARS = 4096
        const val MAX_TITLE_CHARS = 80
        val ID_PATTERN = Regex("[A-Za-z0-9-]{1,64}")
    }
}
