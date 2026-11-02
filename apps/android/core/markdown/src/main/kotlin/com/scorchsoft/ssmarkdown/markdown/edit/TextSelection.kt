package com.scorchsoft.ssmarkdown.markdown.edit

/**
 * A selection in the editor's text, as char offsets with [start] ≤ [end].
 * A caret is the empty selection. Direction (which end the user dragged)
 * belongs to the view, not to an edit, so it is not modelled here.
 */
public data class TextSelection(
    /** The first selected char, or the caret. */
    public val start: Int,
    /** Just past the last selected char; equal to [start] for a caret. */
    public val end: Int,
) {
    init {
        require(start in 0..end) { "selection [$start, $end) is not ordered or is negative" }
    }

    /** True for an empty selection: a blinking caret. */
    public val isCaret: Boolean get() = start == end

    /** Ways to make a selection. */
    public companion object {
        /** A caret at [offset]. */
        public fun caret(offset: Int): TextSelection = TextSelection(offset, offset)
    }
}
