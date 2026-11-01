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
@Suppress("TooManyFunctions") // Start, resume, replay, scan, clean and set aside: one store, one file per document.
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
    ): OpenResult = resume(documentKey, base, currentText, replacing = null)

    /**
     * [resume], for a caller that knows the journal it is replacing: its
     * own, written on [replacing] (the base before a save, a reload or a
     * resolved conflict). A sound journal on that base is replaced in the
     * same atomic step, because [currentText] carries every edit it held;
     * anything else is still quarantined. One atomic replace is what keeps
     * a crash at any moment from finding no journal at all.
     */
    public fun resume(
        documentKey: String,
        base: BaseFingerprint,
        currentText: String,
        replacing: BaseFingerprint?,
    ): OpenResult =
        guarded {
            val atomic = AtomicFile(fileFor(documentKey))
            quarantine.settleLeftover(atomic.newFile)
            val existing = existingHeader(atomic.baseFile)
            if (existing is HeaderRead.Unsupported) return@guarded OpenResult.Unsupported(existing.formatVersion)
            val replaceable =
                existing is HeaderRead.Ok &&
                    (existing.header.base.matches(base) || replacing?.matches(existing.header.base) == true) &&
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
     *
     * Prefer the overload that names the base: this one deletes any sound
     * journal, including one an earlier session left on another base.
     */
    public fun markClean(documentKey: String): CleanResult = markClean(documentKey, expectedBase = null)

    /**
     * [markClean], but only for the journal the caller wrote: one whose
     * header names [expectedBase]. A sound journal on any other base is a
     * different run of edits (an earlier session's, waiting to be
     * recovered), so it is quarantined rather than deleted. A null
     * [expectedBase] skips the check.
     */
    public fun markClean(
        documentKey: String,
        expectedBase: BaseFingerprint?,
    ): CleanResult {
        val file = fileFor(documentKey)
        return try {
            quarantine.settleLeftover(AtomicFile(file).newFile)
            when (val framing = if (file.exists()) JournalReader(file).framing() else null) {
                null -> {
                    CleanResult.NothingToClean
                }

                FramingResult.Sound -> {
                    if (expectedBase == null || headerBase(file)?.matches(expectedBase) == true) {
                        deleteSound(file)
                    } else {
                        CleanResult.Quarantined(quarantine.admit(file))
                    }
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

    /**
     * Moves this document's journal, if any, into `quarantine/` without
     * reading it: for a journal that is not ours to replace or delete (one
     * an earlier session left that could not be applied), when the user
     * starts a new run of edits. Returns where it went, or null if there
     * was none. Nothing is ever deleted here. A journal from a newer
     * format stays where it is, and null is returned.
     *
     * @throws IOException if the journal exists but cannot be moved.
     */
    public fun setAside(documentKey: String): File? {
        val file = fileFor(documentKey)
        quarantine.settleLeftover(AtomicFile(file).newFile)
        val movable = file.exists() && JournalReader(file).header() !is HeaderRead.Unsupported
        return if (movable) quarantine.admit(file) else null
    }

    /**
     * What can be rebuilt from this document's journal without its file
     * (see [Salvage]): for edits whose file cannot be reached or changed
     * with no copy of the old bytes. Reads only; the journal stays.
     * [baseTextFor] is asked for the text of the journal's base.
     */
    public fun salvage(
        documentKey: String,
        baseTextFor: (BaseFingerprint) -> String?,
    ): Salvage = JournalReader(fileFor(documentKey)).salvage(baseTextFor)

    /**
     * Every journal in `quarantine/`, newest first, so set-aside edits are
     * never out of the user's reach (robustness §1). Unreadable headers
     * are listed too, with no document named.
     */
    public fun listSetAside(): List<SetAsideJournal> =
        File(root, Quarantine.DIRECTORY_NAME)
            .listFiles()
            .orEmpty()
            .filter { it.isFile }
            .map { file ->
                val header = runCatchingIo { JournalReader(file).header() } as? HeaderRead.Ok
                SetAsideJournal(
                    file = file,
                    documentKey = header?.header?.documentKey,
                    createdAtMillis = header?.header?.createdAtMillis,
                    setAsideAtMillis = file.lastModified(),
                    sizeBytes = file.length(),
                )
            }.sortedByDescending { it.setAsideAtMillis }

    /**
     * [salvage] for a journal [listSetAside] listed. Refuses (as
     * [Salvage.Unavailable]) any file that is not directly inside this
     * store's quarantine, so a caller can never be steered to read
     * elsewhere.
     */
    public fun salvageSetAside(
        file: File,
        baseTextFor: (BaseFingerprint) -> String?,
    ): Salvage {
        val quarantineDir = File(root, Quarantine.DIRECTORY_NAME).canonicalFile
        if (file.canonicalFile.parentFile != quarantineDir) return Salvage.Unavailable("not a set-aside journal")
        return JournalReader(file).salvage(baseTextFor)
    }

    /** The journal file for [documentKey], whether or not it exists. */
    public fun fileFor(documentKey: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(Wtf8.encode(documentKey))
        val name = digest.take(NAME_HASH_BYTES).joinToString("") { "%02x".format(it) }
        return File(root, "$name.${JournalFormat.FILE_EXTENSION}")
    }

    private inline fun <T> runCatchingIo(block: () -> T): T? =
        try {
            block()
        } catch (_: IOException) {
            null
        }

    private fun deleteSound(file: File): CleanResult {
        if (!file.delete()) return CleanResult.Failed(IOException("cannot delete $file"))
        AtomicFile.syncDirectory(root)
        return CleanResult.Deleted
    }

    private fun headerBase(file: File): BaseFingerprint? =
        (JournalReader(file).header() as? HeaderRead.Ok)?.header?.base

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
