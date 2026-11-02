package com.scorchsoft.ssmarkdown.markdown

/**
 * CommonMark's seven kinds of HTML block (spec §4.6), simplified to what a
 * highlighter needs: how each starts and how each ends. Types 1 to 5 end on
 * the line that contains their end marker; types 6 and 7 end at a blank
 * line; type 7 cannot interrupt a paragraph.
 */
internal object HtmlBlocks {
    private const val TYPE_RAW = 1
    private const val TYPE_COMMENT = 2
    private const val TYPE_PROCESSING = 3
    private const val TYPE_DECLARATION = 4
    private const val TYPE_CDATA = 5
    const val TYPE_BLOCK_TAG = 6
    const val TYPE_ANY_TAG = 7

    private val RAW_TAGS = arrayOf("script", "pre", "style", "textarea")

    private val BLOCK_TAGS =
        hashSetOf(
            "address",
            "article",
            "aside",
            "base",
            "basefont",
            "blockquote",
            "body",
            "caption",
            "center",
            "col",
            "colgroup",
            "dd",
            "details",
            "dialog",
            "dir",
            "div",
            "dl",
            "dt",
            "fieldset",
            "figcaption",
            "figure",
            "footer",
            "form",
            "frame",
            "frameset",
            "h1",
            "h2",
            "h3",
            "h4",
            "h5",
            "h6",
            "head",
            "header",
            "hr",
            "html",
            "iframe",
            "legend",
            "li",
            "link",
            "main",
            "menu",
            "menuitem",
            "nav",
            "noframes",
            "ol",
            "optgroup",
            "option",
            "p",
            "param",
            "search",
            "section",
            "summary",
            "table",
            "tbody",
            "td",
            "tfoot",
            "th",
            "thead",
            "title",
            "tr",
            "track",
            "ul",
        )

    /** The HTML block type starting at [from] (1..7), or 0. */
    fun startType(
        text: CharSequence,
        from: Int,
        end: Int,
    ): Int {
        if (from >= end || text[from] != '<') return 0
        return when {
            RAW_TAGS.any { tagAt(text, from + 1, end, it) } -> TYPE_RAW
            BlockSyntax.regionMatches(text, from, "<!--") -> TYPE_COMMENT
            BlockSyntax.regionMatches(text, from, "<?") -> TYPE_PROCESSING
            BlockSyntax.regionMatches(text, from, "<![cdata[") -> TYPE_CDATA
            from + 2 < end && text[from + 1] == '!' && text[from + 2].isLetter() -> TYPE_DECLARATION
            isBlockTag(text, from, end) -> TYPE_BLOCK_TAG
            isLoneTag(text, from, end) -> TYPE_ANY_TAG
            else -> 0
        }
    }

    /** True if a block of [type] ends on this line, looking from [from]. Types 6 and 7 end at a blank line instead. */
    fun endsOnLine(
        type: Int,
        text: CharSequence,
        from: Int,
        end: Int,
    ): Boolean =
        when (type) {
            TYPE_RAW -> RAW_TAGS.any { BlockSyntax.containsIgnoreCase(text, "</$it>", from, end) }
            TYPE_COMMENT -> BlockSyntax.containsIgnoreCase(text, "-->", from, end)
            TYPE_PROCESSING -> BlockSyntax.containsIgnoreCase(text, "?>", from, end)
            TYPE_DECLARATION -> BlockSyntax.indexOf(text, '>', from, end) >= 0
            TYPE_CDATA -> BlockSyntax.containsIgnoreCase(text, "]]>", from, end)
            else -> false
        }

    /** Where to look for the end marker on the opening line: past the opener, so `<!--` is not its own `-->`. */
    fun endSearchStart(
        type: Int,
        from: Int,
    ): Int = if (type == TYPE_COMMENT) from + "<!--".length else from + 2

    private fun tagAt(
        text: CharSequence,
        at: Int,
        end: Int,
        name: String,
    ): Boolean {
        if (!BlockSyntax.regionMatches(text, at, name)) return false
        val after = at + name.length
        return after >= end || text[after] == '>' || BlockSyntax.isSpace(text[after])
    }

    private fun isBlockTag(
        text: CharSequence,
        from: Int,
        end: Int,
    ): Boolean {
        var i = from + 1
        if (i < end && text[i] == '/') i++
        val nameStart = i
        while (i < end && text[i].isLetterOrDigit()) i++
        if (i == nameStart || !BLOCK_TAGS.contains(text.substring(nameStart, i).lowercase())) return false
        return i == end || text[i] == '>' || BlockSyntax.isSpace(text[i]) || BlockSyntax.regionMatches(text, i, "/>")
    }

    private fun isLoneTag(
        text: CharSequence,
        from: Int,
        end: Int,
    ): Boolean {
        val tagEnd = InlineSyntax.htmlTagEnd(text, from, end)
        return tagEnd > 0 && !BlockSyntax.regionMatches(text, from, "<!--") && BlockSyntax.isBlank(text, tagEnd, end)
    }
}
