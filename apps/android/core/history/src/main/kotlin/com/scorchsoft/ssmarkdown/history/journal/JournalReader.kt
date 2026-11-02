package com.scorchsoft.ssmarkdown.history.journal

import com.scorchsoft.ssmarkdown.history.TextEdit
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Reads a journal file back into text. Reading never changes the file:
 * deciding what to do with a damaged or foreign journal is the store's
 * job, and it never deletes one it could not parse.
 *
 * @property file the journal to read.
 */
@Suppress("TooManyFunctions") // Replay, salvage, classify and frame-check one file: each public read and its walk.
public class JournalReader(
    public val file: File,
) {
    /**
     * Replays the journal onto [baseText], the document as loaded from the
     * file whose fingerprint is [base].
     *
     * The journal's base (its newest Rebase's, else its header's) must [match][BaseFingerprint.matches] [base] before
     * a single edit is applied. Records before that Rebase rebuild only
     * the undo history: the text they applied to is the file's now. Records
     * are then applied in order until the end of the file, a torn tail
     * (legal: the result is [ReplayResult.Ok]) or a damaged record
     * ([ReplayResult.Corrupt], with the text up to the last good record).
     */
    public fun replay(
        baseText: String,
        base: BaseFingerprint,
    ): ReplayResult {
        val summary =
            try {
                if (file.isFile) summary() else null
            } catch (_: IOException) {
                null // The replay below reads the file again and reports the failure.
            }
        return read { input, length ->
            when (val header = HeaderCodec.read(input)) {
                is HeaderRead.Unsupported -> {
                    ReplayResult.Unsupported(header.formatVersion)
                }

                is HeaderRead.Unreadable -> {
                    ReplayResult.Unreadable(header.reason)
                }

                is HeaderRead.Ok -> {
                    val journalBase = summary?.rebase ?: header.header.base
                    if (journalBase.matches(base)) {
                        replayRecords(
                            RecordScanner(input, length),
                            baseText,
                            keepsHistory = header.header.formatVersion >= 2,
                            textFromSeq = summary?.rebaseSeq ?: 0L,
                        )
                    } else {
                        ReplayResult.BaseMismatch(journalBase, base)
                    }
                }
            }
        }
    }

    /**
     * Rebuilds what the journal can still say without replaying it onto
     * its file (see [Salvage]). [baseTextFor] is asked once, with the
     * header's base, for that file's old text, and may answer null. Never
     * throws: an unreadable file is [Salvage.Unavailable].
     */
    public fun salvage(baseTextFor: (BaseFingerprint) -> String?): Salvage =
        try {
            if (!file.isFile) {
                Salvage.Unavailable("no journal")
            } else {
                readOrFail { input, length ->
                    when (val header = HeaderCodec.read(input)) {
                        is HeaderRead.Unsupported -> {
                            Salvage.Unavailable("format ${header.formatVersion} is newer")
                        }

                        is HeaderRead.Unreadable -> {
                            Salvage.Unavailable(header.reason)
                        }

                        is HeaderRead.Ok -> {
                            val rebase = summary()
                            val from = rebase?.rebase
                            salvageRecords(
                                RecordScanner(input, length),
                                if (from == null) baseTextFor(header.header.base) else null,
                                rebaseSeq = rebase?.rebaseSeq ?: 0L,
                                rebaseText = { from?.let(baseTextFor) },
                            )
                        }
                    }
                }
            }
        } catch (e: IOException) {
            Salvage.Unavailable("cannot read: ${e.message}")
        }

    /**
     * True when the journal holds only undo history (FORMAT.md, "Clean
     * journals"): a supported header followed by History records alone,
     * up to the end or a torn tail. Such a journal has no unsaved edits,
     * so the launch scan does not offer it for recovery. Reads only the
     * record frames, never a payload, so it costs a few reads however
     * large the history. Throws [IOException].
     */
    internal fun holdsHistoryOnly(): Boolean = summary()?.holdsEdits == false

    /** [RecordScanner.summarize] for this file, or null when its header is not a readable one. Throws [IOException]. */
    internal fun summary(): JournalSummary? =
        readOrFail { input, length ->
            if (HeaderCodec.read(input) is HeaderRead.Ok) RecordScanner(input, length).summarize() else null
        }

    /** The header alone: what the startup scan needs for the recovery banner. Throws [IOException]. */
    internal fun header(): HeaderRead = readOrFail { input, _ -> HeaderCodec.read(input) }

    /**
     * Checks the framing of every record without applying edits, so a
     * journal can be judged before deletion without knowing its base.
     * Throws [IOException]; the store turns that into a result.
     */
    internal fun framing(): FramingResult =
        readOrFail { input, length ->
            when (val header = HeaderCodec.read(input)) {
                is HeaderRead.Ok -> scanToEnd(RecordScanner(input, length))
                else -> FramingResult.Header(header)
            }
        }

    private fun scanToEnd(scanner: RecordScanner): FramingResult {
        while (true) {
            when (scanner.next()) {
                is ScanStep.Record -> continue
                ScanStep.End, is ScanStep.TornTail -> return FramingResult.Sound
                is ScanStep.Damaged -> return FramingResult.Damaged
            }
        }
    }

    private fun salvageRecords(
        scanner: RecordScanner,
        baseText: String?,
        rebaseSeq: Long,
        rebaseText: () -> String?,
    ): Salvage {
        val state = SalvageState(baseText)
        var going = true
        while (going) {
            going =
                when (val step = scanner.next()) {
                    is ScanStep.Record -> {
                        // The newest Rebase: the file it names is the text from here on, if it can be had.
                        if (rebaseSeq != 0L && step.seq == rebaseSeq) rebaseText()?.let(state::restartFrom)
                        state.apply(step.record)
                    }

                    ScanStep.End, is ScanStep.TornTail -> {
                        false
                    }

                    is ScanStep.Damaged -> {
                        state.stop()
                    }
                }
        }
        return state.result()
    }

    private fun replayRecords(
        scanner: RecordScanner,
        baseText: String,
        keepsHistory: Boolean,
        textFromSeq: Long,
    ): ReplayResult {
        val state = ReplayState(baseText, keepsHistory, textFromSeq)
        var result: ReplayResult? = null
        while (result == null) {
            result =
                when (val step = scanner.next()) {
                    is ScanStep.Record -> state.apply(step)
                    ScanStep.End -> state.ok(discardedTailBytes = 0)
                    is ScanStep.TornTail -> state.ok(step.bytes)
                    is ScanStep.Damaged -> state.corrupt(step.offset, step.reason)
                }
        }
        return result
    }

    /**
     * The text so far, the undo history so far, and how they got there.
     * Until the record numbered [textFromSeq] (the newest Rebase), records
     * rebuild the history only: the text they applied to was the file's
     * before a save, and the base text is the file after it.
     */
    private class ReplayState(
        baseText: String,
        // Version 1 wrote no step ends or undo markers, so its edits say nothing reliable about steps.
        private val keepsHistory: Boolean,
        private val textFromSeq: Long,
    ) {
        private val text = StringBuilder(baseText)
        private val history = HistoryBuilder()
        private var lastSeq = 0L
        private var count = 0
        private var edited = false
        private var textLive = textFromSeq == 0L

        /** Applies one record; null to carry on, or the Corrupt result that ends replay. */
        fun apply(step: ScanStep.Record): ReplayResult? {
            val record = step.record
            if (textLive && record is JournalRecord.Op && !fits(text, record.edit)) {
                return corrupt(step.offset, "edit at ${record.edit.offset} does not match the text")
            }
            if (record.endsAStep() && history.inStep) {
                return corrupt(step.offset, "an undo or redo is cut short by another record")
            }
            when (record) {
                is JournalRecord.Op -> {
                    applyText(record)
                    history.edit(record.edit)
                }

                is JournalRecord.Snapshot -> {
                    applyText(record)
                    history.reset()
                }

                is JournalRecord.Step -> {
                    history.step(record.kind, record.opCount)
                }

                is JournalRecord.History -> {
                    history.replace(record.history)
                }

                is JournalRecord.Rebase -> {
                    if (step.seq == textFromSeq) textLive = true
                }

                is JournalRecord.Marker -> {
                    Unit
                }
            }
            lastSeq = step.seq
            count++
            return null
        }

        /** An Op or a Snapshot changes the text only after the newest Rebase; before it, history alone. */
        private fun applyText(record: JournalRecord) {
            if (!textLive) return
            when (record) {
                is JournalRecord.Op -> with(record.edit) { text.replace(offset, offset + deleted.length, inserted) }
                is JournalRecord.Snapshot -> text.setLength(0).also { text.append(record.text) }
                else -> return
            }
            edited = true
        }

        fun ok(discardedTailBytes: Long): ReplayResult {
            rollBackUnfinishedStep()
            return ReplayResult.Ok(text.toString(), lastSeq, count, discardedTailBytes, historyResult(), edited)
        }

        fun corrupt(
            offset: Long,
            reason: String,
        ): ReplayResult {
            rollBackUnfinishedStep()
            return ReplayResult.Corrupt(offset, text.toString(), lastSeq, count, reason, historyResult(), edited)
        }

        private fun historyResult() = if (keepsHistory) history.result() else JournalHistory.EMPTY

        private fun JournalRecord.endsAStep() =
            this is JournalRecord.Step || this is JournalRecord.History || this is JournalRecord.Rebase

        // An undo or redo the file ends inside of is taken back, so the text is the
        // one before it and the step is still where it was: text and history agree.
        // Before the newest Rebase there is no text to take it back from, and none is
        // needed: an undo cut short by a Rebase is damage, reported above.
        private fun rollBackUnfinishedStep() {
            if (textLive) {
                history.unfinished.asReversed().forEach { edit ->
                    text.replace(edit.offset, edit.offset + edit.inserted.length, edit.deleted)
                }
            }
            history.dropUnfinished()
        }
    }

    private inline fun read(block: (CountingInput, Long) -> ReplayResult): ReplayResult =
        try {
            if (!file.isFile) {
                ReplayResult.Missing
            } else {
                file.inputStream().buffered().use { block(CountingInput(it), file.length()) }
            }
        } catch (e: FileNotFoundException) {
            if (file.exists()) ReplayResult.IoFailure(e) else ReplayResult.Missing
        } catch (e: IOException) {
            ReplayResult.IoFailure(e)
        }

    private inline fun <T> readOrFail(block: (CountingInput, Long) -> T): T =
        file.inputStream().buffered().use { block(CountingInput(it), file.length()) }
}

// Checking the deleted text, not just the range, is what turns "the wrong
// base" or "a lost record" into an error instead of garbage.
private fun fits(
    text: StringBuilder,
    edit: TextEdit,
): Boolean =
    edit.offset + edit.deleted.length <= text.length &&
        edit.deleted.indices.all { text[edit.offset + it] == edit.deleted[it] }

/** Whether a journal's records frame cleanly, judged without its base. */
internal sealed interface FramingResult {
    /** Every record checks out, up to the end or a torn tail. */
    data object Sound : FramingResult

    /** A complete record is damaged; the file may hold more than replay can reach. */
    data object Damaged : FramingResult

    /** The header itself is not a supported, readable header. */
    data class Header(
        val read: HeaderRead,
    ) : FramingResult
}
