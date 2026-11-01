package com.scorchsoft.ssmarkdown.history.journal

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The app's journals: one file per document with unsaved edits, in one
 * app-private directory (docs/robustness.md §1).
 *
 * The store's promises:
 * - it never starts a journal over an existing one ([OpenResult.AlreadyExists]);
 * - it never deletes a journal it cannot parse: those go to `quarantine/`;
 * - it never touches a journal from a newer format at all;
 * - every file it creates or replaces appears whole or not at all ([AtomicFile]).
 *
 * File names are a hash of the document key, because keys are URIs full
 * of characters no file system wants. The key itself is in the header,
 * which is how [scan] can name the document again.
 *
 * One writer per document at a time, and close it before [markClean].
 *
 * @property root the directory that holds the journals, created on first use.
 */
public class JournalStore internal constructor(
    public val root: File,
    private val timing: FlushTiming,
    private val sinkFactory: JournalSink.Factory,
) {
    /**
     * A store in [root] whose writers sync per [policy], timed by [clock]
     * and [scheduler].
     */
    public constructor(
        root: File,
        scheduler: FlushScheduler,
        clock: JournalClock = JournalClock.SYSTEM,
        policy: FlushPolicy = IdleFlushPolicy(),
    ) : this(root, FlushTiming(clock, scheduler, policy), FileChannelSink)

    private val quarantine = Quarantine(File(root, Quarantine.DIRECTORY_NAME), timing.clock)

    /**
     * Starts a journal for a document just opened from the file
     * fingerprinted as [base]. Refuses if one already exists, because that
     * one holds edits nobody has seen yet.
     */
    public fun start(
        documentKey: String,
        base: BaseFingerprint,
    ): OpenResult =
        guarded {
            val atomic = AtomicFile(fileFor(documentKey))
            quarantine.settleLeftover(atomic.newFile)
            when (val existing = existingHeader(atomic.baseFile)) {
                null -> {
                    val header = JournalHeader(documentKey, base, timing.clock.nowMillis())
                    atomic.write(HeaderCodec.encode(header))
                    OpenResult.Opened(writer(atomic.baseFile, header, firstSeq = 1))
                }

                is HeaderRead.Unsupported -> {
                    OpenResult.Unsupported(existing.formatVersion)
                }

                else -> {
                    OpenResult.AlreadyExists(documentKey)
                }
            }
        }

    /**
     * Carries on journalling a document after recovery, starting from
     * [currentText] (the replayed text, as now shown) on top of [base].
     *
     * The existing journal is replaced by a Snapshot of [currentText] in
     * one atomic step. If that journal was damaged, unreadable or written
     * against a different base, it is quarantined first rather than
     * overwritten, since it may hold edits [currentText] lacks. A journal
     * from a newer format is never replaced.
     */
    public fun resume(
        documentKey: String,
        base: BaseFingerprint,
        currentText: String,
    ): OpenResult =
        guarded {
            val atomic = AtomicFile(fileFor(documentKey))
            quarantine.settleLeftover(atomic.newFile)
            val existing = existingHeader(atomic.baseFile)
            if (existing is HeaderRead.Unsupported) return@guarded OpenResult.Unsupported(existing.formatVersion)
            val replaceable =
                existing is HeaderRead.Ok && existing.header.base.matches(base) &&
                    JournalReader(atomic.baseFile).framing() == FramingResult.Sound
            if (existing != null && !replaceable) quarantine.admit(atomic.baseFile)
            val snapshot =
                RecordCodec.encode(JournalRecord.Snapshot(currentText), 1)
                    ?: return@guarded OpenResult.Failed(IOException("text is too large to journal"))
            val header = JournalHeader(documentKey, base, timing.clock.nowMillis())
            atomic.write(HeaderCodec.encode(header), snapshot)
            OpenResult.Opened(writer(atomic.baseFile, header, firstSeq = 2))
        }

    /** Replays this document's journal, if it has one; see [JournalReader.replay]. */
    public fun replay(
        documentKey: String,
        baseText: String,
        base: BaseFingerprint,
    ): ReplayResult = JournalReader(fileFor(documentKey)).replay(baseText, base)

    /**
     * Finds every journal left by an earlier session: run once at launch,
     * before any document opens. Unparseable files are quarantined on the
     * way, and an interrupted compaction's leftover is cleared.
     */
    public fun scan(): StartupScan = StartupScanner(root, quarantine).scan()

    /**
     * Ends a document's journal after a verified save, or after the user
     * chose to discard the edits (and History has recorded them). Only a
     * journal whose every record checks out is deleted.
     */
    public fun markClean(documentKey: String): CleanResult {
        val file = fileFor(documentKey)
        return try {
            quarantine.settleLeftover(AtomicFile(file).newFile)
            when (val framing = if (file.exists()) JournalReader(file).framing() else null) {
                null -> {
                    CleanResult.NothingToClean
                }

                FramingResult.Sound -> {
                    deleteSound(file)
                }

                FramingResult.Damaged -> {
                    CleanResult.Quarantined(quarantine.admit(file))
                }

                is FramingResult.Header -> {
                    when (val read = framing.read) {
                        is HeaderRead.Unsupported -> CleanResult.KeptUnsupported(read.formatVersion)
                        else -> CleanResult.Quarantined(quarantine.admit(file))
                    }
                }
            }
        } catch (e: IOException) {
            CleanResult.Failed(e)
        }
    }

    /** The journal file for [documentKey], whether or not it exists. */
    public fun fileFor(documentKey: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(Wtf8.encode(documentKey))
        val name = digest.take(NAME_HASH_BYTES).joinToString("") { "%02x".format(it) }
        return File(root, "$name.${JournalFormat.FILE_EXTENSION}")
    }

    private fun deleteSound(file: File): CleanResult {
        if (!file.delete()) return CleanResult.Failed(IOException("cannot delete $file"))
        AtomicFile.syncDirectory(root)
        return CleanResult.Deleted
    }

    private fun existingHeader(file: File): HeaderRead? = if (file.exists()) JournalReader(file).header() else null

    private fun writer(
        file: File,
        header: JournalHeader,
        firstSeq: Long,
    ) = JournalWriter(
        file = file,
        header = header,
        appender = JournalAppender(file, sinkFactory),
        firstSeq = firstSeq,
        timing = timing,
    )

    private inline fun guarded(block: () -> OpenResult): OpenResult =
        try {
            root.mkdirs()
            block()
        } catch (e: IOException) {
            OpenResult.Failed(e)
        }

    private companion object {
        // 128 bits of SHA-256: collisions between a phone's documents are not a real risk.
        const val NAME_HASH_BYTES = 16
    }
}
