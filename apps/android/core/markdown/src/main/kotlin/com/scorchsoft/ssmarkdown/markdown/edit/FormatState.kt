package com.scorchsoft.ssmarkdown.markdown.edit

/**
 * What the text at the selection already is, so the strip can show B as
 * pressed inside bold text (docs/ux-spec.md, "Editing in Preview":
 * "Toggles show their state"). Read from the selection's first line,
 * using the same rules the commands use, so a pressed key always means
 * "pressing this takes it off".
 */
public data class FormatState(
    /** The inline formats enclosing the selection. */
    public val inline: Set<InlineFormat> = emptySet(),
    /** The ATX heading level, or 0 for none. */
    public val headingLevel: Int = 0,
    /** The list type, or null outside a list. */
    public val list: ListKind? = null,
    /** True inside a blockquote. */
    public val quote: Boolean = false,
    /** The block type for the ¶ menu's tick, or null for none of its types. */
    public val block: BlockType? = BlockType.Paragraph,
) {
    /** Reading the state. */
    public companion object {
        /** The state at [selection] in [text]; [fences] says which lines are code, for [block]. */
        public fun at(
            text: CharSequence,
            selection: TextSelection,
            fences: FenceStates = FenceStates.NONE,
        ): FormatState {
            require(selection.end <= text.length) { "selection $selection outside text of ${text.length}" }
            val line = TextLines.lineAt(text, selection.start)
            val from = selection.start - line.start
            val to = minOf(selection.end, line.end) - line.start
            val p = LinePrefix.parse(line.content)
            val inline =
                InlineFormat.entries.filterTo(LinkedHashSet()) {
                    InlineSpans.enclosing(line.content, it, from, to) != null
                }
            val list =
                when {
                    p.isTask -> ListKind.Task
                    p.marker is ListMarker.Ordered -> ListKind.Numbered
                    p.marker is ListMarker.Bullet -> ListKind.Bullet
                    else -> null
                }
            val (level, _) = HeadingEdit.parse(line.content.substring(p.quoteEnd))
            return FormatState(inline, level, list, p.quoteEnd > 0, BlockType.at(text, selection, fences))
        }
    }
}
