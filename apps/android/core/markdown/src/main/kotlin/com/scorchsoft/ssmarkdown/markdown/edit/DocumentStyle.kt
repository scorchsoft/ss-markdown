package com.scorchsoft.ssmarkdown.markdown.edit

/**
 * The Markdown dialect a file is already written in, so formatting adds
 * markers the way its author would have (docs/ux-spec.md, "Editing in
 * Preview": `*` vs `_`, `-` vs `+` vs `*` bullets, `1.` vs `1)`).
 *
 * Detect it once per file with [detect] and pass it to every command; it
 * only changes when the author's habits do, so there is no need to
 * re-detect on each keystroke.
 */
public data class DocumentStyle(
    /** The emphasis (italic) delimiter: `*` or `_`. */
    public val emphasis: Char = '*',
    /** The strong (bold) delimiter char, doubled when written: `*` or `_`. */
    public val strong: Char = '*',
    /** The bullet list marker: `-`, `+` or `*`. */
    public val bullet: Char = '-',
    /** The ordered list delimiter: `.` or `)`. */
    public val orderedDelimiter: Char = '.',
    /** One level of indentation outside lists: a tab or some spaces. Lists nest by their marker's width. */
    public val indentUnit: String = "    ",
    /** The fence for new code blocks: three backticks or three tildes. */
    public val codeFence: String = "```",
    /** True for `| a | b |` tables, false for compact `|a|b|` ones. */
    public val paddedTables: Boolean = true,
) {
    init {
        require(emphasis in EMPHASIS_CHARS && strong in EMPHASIS_CHARS) { "emphasis must be * or _" }
        require(bullet in BULLET_CHARS) { "bullet must be one of $BULLET_CHARS" }
        require(orderedDelimiter in ORDERED_DELIMITERS) { "ordered delimiter must be . or )" }
        require(indentUnit == "\t" || (indentUnit.isNotEmpty() && indentUnit.all { it == ' ' })) {
            "indent unit must be a tab or spaces"
        }
        require(codeFence == "```" || codeFence == "~~~") { "code fence must be ``` or ~~~" }
    }

    /** Detection and the default dialect. */
    public companion object {
        /** CommonMark's most common habits, used for a new file or one with no evidence either way. */
        public val DEFAULT: DocumentStyle = DocumentStyle()

        /**
         * How many chars [detect] reads by default: enough to see a
         * writer's habits, small enough to stay well under a frame even on
         * a 20 MB file (docs/quality-bar.md §4).
         */
        public const val DEFAULT_SCAN_CHARS: Int = 64 * 1024

        internal const val EMPHASIS_CHARS = "*_"
        internal const val BULLET_CHARS = "-+*"
        internal const val ORDERED_DELIMITERS = ".)"

        /**
         * The style of [text], from its first [scanChars] chars. Each
         * choice is a majority vote over what the file already uses, with
         * [DEFAULT]'s choice winning a tie, so an empty file gets the
         * defaults and a file written with `_italics_` keeps them.
         */
        public fun detect(
            text: CharSequence,
            scanChars: Int = DEFAULT_SCAN_CHARS,
        ): DocumentStyle = StyleDetector().apply { scan(text, minOf(scanChars, text.length)) }.result()
    }
}
