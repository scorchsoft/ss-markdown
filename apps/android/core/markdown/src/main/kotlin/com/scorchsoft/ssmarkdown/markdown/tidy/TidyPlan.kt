package com.scorchsoft.ssmarkdown.markdown.tidy

/** The kinds of fix Tidy makes. Each one leaves the rendered document as it was (see [MarkdownTidy]). */
public enum class TidyRule {
    /** A run of blank lines becomes one; blank lines at the start of the file go. */
    ExtraBlankLines,

    /** Spaces and tabs at the end of a line go, except a hard line break's. */
    TrailingSpaces,

    /** A heading gets a blank line above and below it. */
    BlankLineAroundHeading,

    /** A fenced code block gets a blank line above and below it. */
    BlankLineAroundFence,

    /** `#   Title` becomes `# Title`. */
    HeadingSpacing,

    /** A numbered list counts up from its first number. */
    ListNumbering,

    /** The file ends with exactly one line break. */
    FinalNewline,

    /** A code block left open at the end of the file gets its closing fence. */
    UnclosedFence,
}

/**
 * Replace characters `[start, end)` of the original text with [replacement].
 * Offsets are in the text [MarkdownTidy.plan] was given, never in a
 * partly-tidied one.
 */
public data class TidyEdit(
    val start: Int,
    val end: Int,
    val replacement: String,
)

/**
 * What Tidy would change: small local [edits], sorted by [TidyEdit.start]
 * and never overlapping, so the caller can apply them as one undoable step
 * and the caret stays where it was. [counts] says how many places each rule
 * fixed, for the "Tidied 3 things" message.
 */
public data class TidyPlan(
    val edits: List<TidyEdit>,
    val counts: Map<TidyRule, Int>,
) {
    /** True when the document is already tidy. */
    val isEmpty: Boolean get() = edits.isEmpty()

    /** [text] with every edit applied; [text] must be the text the plan was made for. */
    public fun applyTo(text: String): String {
        if (edits.isEmpty()) return text
        val out = StringBuilder(text.length + edits.sumOf { it.replacement.length })
        var at = 0
        for (edit in edits) {
            out.append(text, at, edit.start).append(edit.replacement)
            at = edit.end
        }
        return out.append(text, at, text.length).toString()
    }

    /** The plan that changes nothing. */
    public companion object {
        /** No edits, no counts. */
        public val EMPTY: TidyPlan = TidyPlan(emptyList(), emptyMap())
    }
}
