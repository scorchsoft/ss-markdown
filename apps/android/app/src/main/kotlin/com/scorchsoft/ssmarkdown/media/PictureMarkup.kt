package com.scorchsoft.ssmarkdown.media

/**
 * The pure parts of Insert image (docs/ux-spec.md, "Pictures"): the
 * relative path from a document to a picture, how it is written in
 * Markdown, and a free name for a copy. Tested on their own.
 */
object PictureMarkup {
    /** Characters that would end or confuse a link destination, or be decoded on the way back. */
    private val ESCAPED = setOf(' ', '%', '(', ')', '<', '>', '[', ']', '#', '?', '\\', '"', '\'')

    /**
     * The path from the document whose tree path is [docIds] to the picture
     * whose tree path is [imageIds] (both root first), as segments
     * (`["..", "img", "a.png"]`). [nameOf] gives a child's display name
     * from its parent's listing. Null when the two are not in one tree, or a
     * name is unknown.
     */
    fun between(
        docIds: List<String>,
        imageIds: List<String>,
        nameOf: (parent: String, id: String) -> String?,
    ): List<String>? {
        if (docIds.size < 2 || imageIds.size < 2 || docIds.first() != imageIds.first()) return null
        val docFolders = docIds.dropLast(1)
        var common = 0
        while (common < docFolders.size && common < imageIds.size - 1 &&
            docFolders[common] == imageIds[common]
        ) {
            common++
        }
        val down =
            (common until imageIds.size).map { i ->
                nameOf(imageIds[i - 1], imageIds[i]) ?: return null
            }
        return List(docFolders.size - common) { ".." } + down
    }

    /**
     * [segments] as a Markdown link destination: each name percent-encoded
     * where it would break the link (`my pic.png` is `my%20pic.png`), so
     * it reads back as the same path. Other characters, accented letters
     * and emoji included, stay as typed.
     */
    fun path(segments: List<String>): String =
        segments.joinToString("/") { segment ->
            buildString {
                for (ch in segment) {
                    if (ch in ESCAPED || ch.code < SPACE) append(percent(ch)) else append(ch)
                }
            }
        }

    /** `![alt](path)`, with brackets and backslashes in [alt] escaped. */
    fun image(
        alt: String,
        path: String,
    ): String = "![${escapeAlt(alt)}]($path)"

    /** The alt text a picture gets by default: its file name without the extension. */
    fun altFor(fileName: String): String = fileName.substringBeforeLast('.').ifEmpty { fileName }

    /** [alt] as it is written between `![` and `]`. */
    fun escapeAlt(alt: String): String =
        buildString {
            for (ch in alt) {
                if (ch == '[' || ch == ']' || ch == '\\') append('\\')
                append(if (ch == '\n' || ch == '\r') ' ' else ch)
            }
        }

    /**
     * [name], or `name-2.png`, `name-3.png`… whichever is not in [taken]
     * (compared ignoring case, as some providers do).
     */
    fun freeName(
        name: String,
        taken: Set<String>,
    ): String {
        val lower = taken.mapTo(HashSet()) { it.lowercase() }
        if (name.lowercase() !in lower) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val ext = name.substring(dot)
        var n = 2
        while ("$stem-$n$ext".lowercase() in lower) n++
        return "$stem-$n$ext"
    }

    private fun percent(ch: Char): String =
        String(charArrayOf(ch))
            .toByteArray(Charsets.UTF_8)
            .joinToString("") { "%" + "%02X".format(it.toInt() and BYTE) }

    private const val SPACE = 0x20
    private const val BYTE = 0xFF
}
