package com.scorchsoft.ssmarkdown.files.saf

import java.util.Locale

/**
 * Which files are ours (R2): `.md`, `.markdown`, `.mdx` and `.txt`, plus
 * `text/markdown` and `text/plain`, matched without regard to case so an
 * upper-case `.MD` or a `text/plain` `README` opens normally.
 */
object MarkdownFiles {
    /** Extensions shown in folder mode, lower case, without the dot. */
    val EXTENSIONS: Set<String> = setOf("md", "markdown", "mdx", "txt")

    /** MIME types for the system pickers and intent filters. */
    val MIME_TYPES: Array<String> =
        arrayOf("text/markdown", "text/x-markdown", "text/plain", "application/octet-stream")

    /** The MIME type new files are created with. */
    const val CREATE_MIME_TYPE: String = "text/markdown"

    /** True when [name]'s extension is one of [EXTENSIONS]. */
    fun hasMarkdownExtension(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        return dot in 1 until name.length - 1 && name.substring(dot + 1).lowercase(Locale.ROOT) in EXTENSIONS
    }

    /** True when [mimeType] is a text type we edit. */
    fun isTextMime(mimeType: String?): Boolean {
        val type = mimeType?.lowercase(Locale.ROOT) ?: return false
        return type == "text/markdown" || type == "text/x-markdown" || type == "text/plain"
    }
}
