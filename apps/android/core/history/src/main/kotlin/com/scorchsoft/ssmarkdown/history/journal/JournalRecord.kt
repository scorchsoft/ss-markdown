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

/**
 * The undo stack's decisions, written as Marker records (FORMAT.md,
 * "Undo history"). Kept apart from [MarkerKind] because their payload is
 * a count, not a label, and no caller writes them by hand.
 *
 * @property code the marker kind byte on disk. Never reuse or renumber one.
 */
@Suppress("MagicNumber") // The numbers are the on-disk codes, listed in FORMAT.md.
internal enum class StepKind(
    val code: Int,
) {
    /** The newest undo step is closed; the next edit starts a new one. */
    End(3),

    /** The next `opCount` edits are an undo of the newest step. */
    Undo(4),

    /** The next `opCount` edits are a redo of the next undone step. */
    Redo(5),
    ;

    companion object {
        fun fromCode(code: Int): StepKind? = entries.firstOrNull { it.code == code }
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

    /** An undo-stack decision: a step end, or how many of the next edits are an undo or redo. */
    data class Step(
        val kind: StepKind,
        val opCount: Int = 0,
    ) : JournalRecord

    /** The undo and redo steps, replacing what came before; changes no text. */
    data class History(
        val history: JournalHistory,
    ) : JournalRecord
}
