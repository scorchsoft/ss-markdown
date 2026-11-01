package com.scorchsoft.ssmarkdown.history

/**
 * One edit to a document, as plain data: at [offset], the text [deleted]
 * was replaced by [inserted].
 *
 * The history module deliberately does not depend on the text engine
 * (`core:text`). An edit is just three values, so the journal can record
 * it, replay it onto a `String` and check it, whatever buffer produced it.
 *
 * Offsets count UTF-16 code units, the same unit as `String` indices,
 * because that is what every JVM text API speaks. Carrying the [deleted]
 * text, rather than only its length, costs a few bytes and buys two
 * things: replay can verify each edit against the text it is applied to
 * (a mismatch means the wrong base or a damaged journal, never silent
 * garbage), and an edit can be inverted for undo without the old buffer.
 *
 * @property offset where the edit starts, in UTF-16 code units. Never negative.
 * @property deleted the exact text that was removed; empty for a pure insert.
 * @property inserted the text that took its place; empty for a pure delete.
 */
public data class TextEdit(
    public val offset: Int,
    public val deleted: String,
    public val inserted: String,
) {
    init {
        require(offset >= 0) { "offset must not be negative: $offset" }
    }

    /** The edit that undoes this one: same place, the two texts swapped. */
    public fun inverted(): TextEdit = TextEdit(offset, deleted = inserted, inserted = deleted)
}
