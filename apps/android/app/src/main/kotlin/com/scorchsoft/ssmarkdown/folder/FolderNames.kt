package com.scorchsoft.ssmarkdown.folder

import java.net.URLDecoder

/** Names in folder mode, as pure functions. */
object FolderNames {
    private val MARKDOWN = setOf("md", "markdown", "mdx")

    /**
     * A tree URI's folder name: the last segment of its document id
     * (`primary:Documents/product-docs` → `product-docs`).
     */
    fun nameOf(treeUri: String): String {
        val id = URLDecoder.decode(treeUri.substringAfterLast("/tree/"), Charsets.UTF_8.name())
        return id.substringAfterLast('/').substringAfterLast(':').ifEmpty { id }
    }

    /** True for Markdown files; `.txt` and others are muted and open in Code only. */
    fun isMarkdown(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in MARKDOWN

    /** "notes.md" → "notes copy.md". */
    fun copyName(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)} copy${name.substring(dot)}" else "$name copy"
    }
}
