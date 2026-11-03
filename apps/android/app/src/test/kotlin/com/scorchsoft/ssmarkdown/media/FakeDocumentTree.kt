package com.scorchsoft.ssmarkdown.media

import java.io.File

/**
 * An in-memory provider with opaque ids, like Drive, whose files are real
 * files under [dir] so they open through the ContentResolver (`file://`).
 * Every provider call is counted, so a test can say how many listings a
 * page of pictures cost.
 *
 * Documents are addressed as `content://fake/document/<id>`; the tree is
 * `content://fake/tree/R`. [canSayPath] false is a provider without
 * `findDocumentPath`.
 */
class FakeDocumentTree(
    private val dir: File,
    var canSayPath: Boolean = true,
) : DocumentTree {
    private class Node(
        val id: String,
        val parent: String?,
        val name: String,
        val folder: Boolean,
        val file: File?,
        val mime: String?,
    )

    private val nodes = LinkedHashMap<String, Node>()
    private var next = 0
    var listCalls = 0
    var pathCalls = 0

    init {
        nodes[ROOT] = Node(ROOT, null, "", true, null, null)
    }

    /** Adds a folder under [parent]; its id. */
    fun folder(
        parent: String,
        name: String,
    ): String = add(parent, name, folder = true, bytes = null, mime = null)

    /** Adds a file under [parent] holding [bytes]; its id. */
    fun file(
        parent: String,
        name: String,
        bytes: ByteArray = name.toByteArray(),
        mime: String? = DocumentPictures.mimeOf(name),
    ): String = add(parent, name, folder = false, bytes = bytes, mime = mime)

    /** The bytes of [id]. */
    fun bytes(id: String): ByteArray? = nodes[id]?.file?.readBytes()

    /** The names in folder [id]. */
    fun names(id: String): List<String> = nodes.values.filter { it.parent == id }.map { it.name }

    private fun add(
        parent: String,
        name: String,
        folder: Boolean,
        bytes: ByteArray?,
        mime: String?,
    ): String {
        val id = "n${next++}"
        val file =
            if (folder) {
                null
            } else {
                File(dir, id).apply { writeBytes(bytes ?: ByteArray(0)) }
            }
        nodes[id] = Node(id, parent, name, folder, file, if (folder) DIR else mime)
        return id
    }

    @Suppress("ReturnCount") // A fake provider: each way of not knowing a path is its own early return.
    override fun pathTo(
        tree: String,
        document: String,
    ): List<String>? {
        pathCalls++
        if (!canSayPath || tree != TREE) return null
        var node = nodes[documentId(document) ?: return null] ?: return null
        val out = arrayListOf(node.id)
        while (node.parent != null) {
            node = nodes[node.parent] ?: return null
            out.add(node.id)
        }
        return out.reversed()
    }

    override fun list(
        tree: String,
        folder: String,
    ): List<TreeEntry>? {
        listCalls++
        if (tree != TREE || nodes[folder]?.folder != true) return null
        return nodes.values
            .filter { it.parent == folder }
            .map { TreeEntry(it.id, it.name, it.mime, it.file?.length()) }
    }

    override fun uri(
        tree: String,
        documentId: String,
    ): String = nodes[documentId]?.file?.let { "file://${it.absolutePath}" } ?: "content://fake/document/$documentId"

    override fun documentId(document: String): String? =
        document.substringAfter("/document/", "").takeIf { it.isNotEmpty() }

    override fun treeId(tree: String): String? = ROOT.takeIf { tree == TREE }

    override fun createFolder(
        tree: String,
        parent: String,
        name: String,
    ): String? = if (tree == TREE) folder(parent, name) else null

    override fun createFile(
        tree: String,
        parent: String,
        mime: String,
        name: String,
    ): String? = if (tree == TREE) file(parent, name, ByteArray(0), mime) else null

    override fun delete(
        tree: String,
        documentId: String,
    ): Boolean = nodes.remove(documentId)?.file?.delete() == true

    companion object {
        const val ROOT = "R"
        const val TREE = "content://fake/tree/R"
        const val OTHER_TREE = "content://fake/tree/Q"
        private const val DIR = "vnd.android.document/directory"

        fun doc(id: String): String = "content://fake/document/$id"
    }
}
