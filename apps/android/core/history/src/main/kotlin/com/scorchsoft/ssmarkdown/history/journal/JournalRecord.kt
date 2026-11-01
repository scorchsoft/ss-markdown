package com.scorchsoft.ssmarkdown.history.journal

import com.scorchsoft.ssmarkdown.history.TextEdit

/**
 * Why a [JournalWriter.mark] marker was written. Markers change no text;
 * they let recovery and history say what happened around the edits.
 *
 * @property code the byte stored on disk. Never reuse or renumber one.
 */
public enum class MarkerKind(
    internal val code: Int,
) {
    /** The document was saved and verified; edits after this are unsaved. */
    Saved(1),

    /** A History checkpoint was taken (backgrounded, a risky step, five minutes of editing). */
    Checkpoint(2),
    ;

    internal companion object {
        fun fromCode(code: Int): MarkerKind? = entries.firstOrNull { it.code == code }
    }
}

/** What a record carries, decoded. Internal: callers see edits and text, not frames. */
internal sealed interface JournalRecord {
    /** One edit, applied to the text so far. */
    data class Op(
        val edit: TextEdit,
    ) : JournalRecord

    /** The whole text, replacing everything before it. Written by compaction. */
    data class Snapshot(
        val text: String,
    ) : JournalRecord

    /** A labelled moment that changes no text. */
    data class Marker(
        val kind: MarkerKind,
        val label: String,
    ) : JournalRecord
}
