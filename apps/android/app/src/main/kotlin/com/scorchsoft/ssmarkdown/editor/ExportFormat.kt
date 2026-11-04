package com.scorchsoft.ssmarkdown.editor

/** The Markdown file type a Save as… or section export creates. */
internal const val MARKDOWN_MIME = "text/markdown"

/** ⋮ → Export…'s three choices, each with the type and extension of the file it makes. */
enum class ExportFormat(
    val mimeType: String,
    val extension: String,
) {
    /** The print dialog, Save as PDF included (A4 print stylesheet). */
    Pdf("application/pdf", "pdf"),

    /** One web page: the formatted copy's HTML, styles inline, pictures embedded. */
    Html("text/html", "html"),

    /** The words without Markdown symbols, as they would read pasted into an email. */
    Text("text/plain", "txt"),
}
