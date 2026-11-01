package com.scorchsoft.ssmarkdown.markdown

/**
 * The tokenizer's answer for one line: its tokens, the state the next line
 * starts from, and the few block facts the outline and editor need without
 * re-reading the tokens.
 */
public class LineResult internal constructor(
    /** Packed tokens; see [Tokens] for the layout and coverage rules. */
    public val tokens: LongArray,
    /** The state the next line starts from. */
    public val endState: LineState,
    /** What kind of block line this is. */
    public val kind: LineKind,
    /** 1 to 6 when [kind] is [LineKind.HEADING]; otherwise 0. */
    public val headingLevel: Int,
    /**
     * How this line restyles the line above it: a setext underline makes it
     * a heading, a table delimiter row makes it a header row. Markdown's one
     * backwards-looking rule, surfaced so the cache can re-tokenise exactly
     * one line (docs/robustness.md, "non-local rules").
     */
    public val previousLineRole: LineRole,
    /**
     * The normalised label this line defines, for [ReferenceIndex]: `foo` for
     * `[Foo]: /url`, `^1` for the footnote `[^1]: text`; null otherwise.
     */
    public val definedLabel: String?,
) {
    /** Token count, for loops that avoid boxing. */
    public val tokenCount: Int get() = tokens.size

    /** Same tokens, state and block facts. Used by tests and by the cache to skip needless updates. */
    public fun sameAs(other: LineResult): Boolean =
        kind == other.kind &&
            headingLevel == other.headingLevel &&
            previousLineRole == other.previousLineRole &&
            definedLabel == other.definedLabel &&
            endState == other.endState &&
            tokens.contentEquals(other.tokens)

    override fun toString(): String =
        "LineResult($kind${if (headingLevel != 0) " h$headingLevel" else ""}, ${Tokens.describe(tokens)}, $endState)"
}

/** The block a line belongs to, coarse enough for a gutter or a font size. */
public enum class LineKind {
    /** Empty or whitespace only (after any container markers). */
    BLANK,

    /** Ordinary text: a paragraph line or a lazy continuation. */
    PARAGRAPH,

    /** An ATX heading, or a paragraph line restyled by the setext underline below it. */
    HEADING,

    /** The `===` / `---` under a setext heading. */
    SETEXT_UNDERLINE,

    /** The opening or closing fence of a fenced code block. */
    CODE_FENCE,

    /** A line inside a fenced or indented code block. */
    CODE,

    /** A front-matter line, delimiters included. */
    FRONT_MATTER,

    /** A `$$` display-math line, delimiters included. */
    MATH,

    /** A line of an HTML block. */
    HTML,

    /** A table's header row (restyled by the delimiter row below it). */
    TABLE_HEADER,

    /** The `|---|---|` row under a table header. */
    TABLE_DELIMITER,

    /** A table body row. */
    TABLE_ROW,

    /** A thematic break. */
    HORIZONTAL_RULE,

    /** `[label]: url "title"`. */
    REFERENCE_DEFINITION,

    /** `[^label]: text`. */
    FOOTNOTE_DEFINITION,
}

/** How a line is to be read because of the line below it. */
public enum class LineRole {
    /** As written. */
    AS_IS,

    /** A level-1 setext heading: the line below is `===`. */
    SETEXT_HEADING_1,

    /** A level-2 setext heading: the line below is `---`. */
    SETEXT_HEADING_2,

    /** A table header: the line below is a matching delimiter row. */
    TABLE_HEADER,
}
