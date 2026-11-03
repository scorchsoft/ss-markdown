package com.scorchsoft.ssmarkdown.markdown.paste

/**
 * What a clip pastes as, in Code and in Preview alike (docs/ux-spec.md,
 * "Copy and paste"; docs/editing-behaviours.md EB-CLIP-06, -13, -14). A
 * normal paste of HTML is that HTML as Markdown, unless the HTML is only a
 * plain wrapper round the clip's own plain text (a single span, or
 * paragraphs with no formatting), when the plain text is used as it is.
 * "Paste as plain text" is the words of whatever a normal paste would
 * insert. Preview's twin is `web/src/core/pasteClip.ts`.
 */
public object PasteClip {
    /** Plain text longer than this is judged by its first this-many characters for [isRich]. */
    public const val RICH_SCAN_CHARS: Int = 100_000

    /** What a normal paste inserts: [text] is Markdown; [fromHtml] when it came from converting HTML. */
    public class Content(
        public val text: String,
        public val fromHtml: Boolean,
    )

    /** The Markdown a normal paste of [plain] and/or [html] inserts. */
    public fun content(
        plain: String?,
        html: String?,
    ): Content {
        if (!html.isNullOrBlank()) {
            val converted = HtmlToMarkdown.convert(html)
            if (converted.markdown.isNotBlank() && (converted.formatted || plain.isNullOrEmpty())) {
                return Content(converted.markdown, fromHtml = true)
            }
        }
        return Content(plain.orEmpty(), fromHtml = false)
    }

    /** What "Paste as plain text" inserts: only the words, with no Markdown or HTML. */
    public fun words(
        plain: String?,
        html: String?,
    ): String = MarkdownPlainText.of(content(plain, html).text)

    /**
     * True when "Paste as plain text" would differ from Paste: the clip
     * has HTML, or its plain text holds Markdown syntax.
     */
    public fun isRich(
        plain: String?,
        html: String?,
    ): Boolean = !html.isNullOrBlank() || (plain != null && MarkdownPlainText.hasMarkdown(plain.take(RICH_SCAN_CHARS)))

    /** [html] as Markdown, and whether any of it was formatting. */
    public fun htmlToMarkdown(html: String): ConvertedHtml = HtmlToMarkdown.convert(html)
}
