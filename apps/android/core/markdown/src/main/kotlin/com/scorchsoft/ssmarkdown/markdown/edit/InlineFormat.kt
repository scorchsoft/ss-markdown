package com.scorchsoft.ssmarkdown.markdown.edit

/** A span style the strip toggles: each wraps text in a delimiter on both sides. */
public enum class InlineFormat {
    /** `**strong**` or `__strong__`, per [DocumentStyle.strong]. */
    Bold,

    /** `*emphasis*` or `_emphasis_`, per [DocumentStyle.emphasis]. */
    Italic,

    /** GFM `~~strikethrough~~`. */
    Strikethrough,

    /** `` `code` ``. */
    Code,
    ;

    /** The delimiter a new span gets in a document of [style]. */
    internal fun marker(style: DocumentStyle): String =
        when (this) {
            Bold -> "${style.strong}${style.strong}"
            Italic -> "${style.emphasis}"
            Strikethrough -> "~~"
            Code -> "`"
        }

    /** The chars an existing span may use: either emphasis char, whatever the document's habit. */
    internal val delimiterChars: String
        get() =
            when (this) {
                Bold, Italic -> DocumentStyle.EMPHASIS_CHARS
                Strikethrough -> "~"
                Code -> "`"
            }

    /** The width of the delimiter, in chars. */
    internal val width: Int get() = if (this == Bold || this == Strikethrough) 2 else 1

    /** True when a delimiter run of [length] can carry this format (`***` is both bold and italic). */
    internal fun fitsRun(length: Int): Boolean =
        when (this) {
            Bold -> length == 2 || length == BOTH
            Italic -> length == 1 || length == BOTH
            Strikethrough -> length == 2
            Code -> length == 1
        }

    private companion object {
        const val BOTH = 3
    }
}
