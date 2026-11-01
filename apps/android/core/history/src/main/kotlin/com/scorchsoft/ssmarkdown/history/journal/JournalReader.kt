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
public class JournalReader(
    public val file: File,
) {
    /**
     * Replays the journal onto [baseText], the document as loaded from the
     * file whose fingerprint is [base].
     *
     * The header's base must [match][BaseFingerprint.matches] [base]
     * before a single edit is applied. Records are then applied in order
     * until the end of the file, a torn tail (legal: the result is
     * [ReplayResult.Ok]) or a damaged record ([ReplayResult.Corrupt], with
     * the text up to the last good record).
     */
    public fun replay(
        baseText: String,
        base: BaseFingerprint,
    ): ReplayResult =
        read { input, length ->
            when (val header = HeaderCodec.read(input)) {
                is HeaderRead.Unsupported -> {
                    ReplayResult.Unsupported(header.formatVersion)
                }

                is HeaderRead.Unreadable -> {
                    ReplayResult.Unreadable(header.reason)
                }

                is HeaderRead.Ok -> {
                    if (header.header.base.matches(base)) {
                        replayRecords(RecordScanner(input, length), baseText)
                    } else {
                        ReplayResult.BaseMismatch(header.header.base, base)
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
                            salvageRecords(
                                RecordScanner(input, length),
                                baseTextFor(header.header.base),
                            )
                        }
                    }
                }
            }
        } catch (e: IOException) {
            Salvage.Unavailable("cannot read: ${e.message}")
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
    ): Salvage {
        val state = SalvageState(baseText)
        var going = true
        while (going) {
            going =
                when (val step = scanner.next()) {
                    is ScanStep.Record -> state.apply(step.record)
                    ScanStep.End, is ScanStep.TornTail -> false
                    is ScanStep.Damaged -> state.stop()
                }
        }
        return state.result()
    }

    private fun replayRecords(
        scanner: RecordScanner,
        baseText: String,
    ): ReplayResult {
        val state = ReplayState(baseText)
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

    /** The text so far and how it got there. */
    private class ReplayState(
        baseText: String,
    ) {
        private val text = StringBuilder(baseText)
        private var lastSeq = 0L
        private var count = 0

        /** Applies one record; null to carry on, or the Corrupt result that ends replay. */
        fun apply(step: ScanStep.Record): ReplayResult? {
            val record = step.record
            if (record is JournalRecord.Op && !fits(text, record.edit)) {
                return corrupt(step.offset, "edit at ${record.edit.offset} does not match the text")
            }
            when (record) {
                is JournalRecord.Op -> with(record.edit) { text.replace(offset, offset + deleted.length, inserted) }
                is JournalRecord.Snapshot -> text.setLength(0).also { text.append(record.text) }
                is JournalRecord.Marker -> Unit
            }
            lastSeq = step.seq
            count++
            return null
        }

        fun ok(discardedTailBytes: Long) = ReplayResult.Ok(text.toString(), lastSeq, count, discardedTailBytes)

        fun corrupt(
            offset: Long,
            reason: String,
        ) = ReplayResult.Corrupt(offset, text.toString(), lastSeq, count, reason)
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
