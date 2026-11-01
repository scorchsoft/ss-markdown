package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.files.provider.ProviderResult
import com.scorchsoft.ssmarkdown.files.provider.valueOrNull
import com.scorchsoft.ssmarkdown.files.save.PendingSaves
import com.scorchsoft.ssmarkdown.files.save.WriteAttempt
import com.scorchsoft.ssmarkdown.history.journal.BaseFingerprint
import com.scorchsoft.ssmarkdown.history.journal.ReplayResult
import com.scorchsoft.ssmarkdown.text.DecodeResult
import com.scorchsoft.ssmarkdown.text.Utf8Decoder
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream

/**
 * The launch scan's first step: settles every save the last process
 * started and never finished ([PendingSaves]), before any journal is
 * read (docs/robustness.md §2, "a file is never left half-written").
 *
 * For each, the file is one of:
 * - **the old bytes**: the write never began, or was put back. Nothing to do.
 * - **the new bytes**: the write completed, but the process died before
 *   the journal moved to them. The journal's edits are replayed onto the
 *   old bytes (still the base copy) and the journal is restarted on the
 *   saved version, or ended if the save holds every edit. Without this,
 *   the next open would see a "change made outside" that was our own save.
 * - **made only of old and new bytes**, position by position: our write
 *   was torn. The old bytes are written back and verified.
 * - **anything else**: another app changed the file after the crash. It
 *   is never touched; the journal and the conflict flow take it from here.
 *
 * A file that cannot be reached now (no permission, provider gone) keeps
 * its record, and the next launch tries again. Blocking: run it on I/O.
 */
internal class InterruptedSaves(
    private val context: StoreContext,
) {
    private val saves = context.baseCopies.pendingSaves

    /** Settles every record it can; returns how many torn files were put back. */
    fun settleAll(): Int = saves.all().count { settle(it) == Settled.Repaired }

    private enum class Settled { Clean, Completed, Repaired, OutsideChange, Later }

    @Suppress("ReturnCount") // The four outcomes of the class description, each decided by one read.
    private fun settle(record: PendingSaves.Record): Settled {
        val disk =
            when (val read = context.probe.digest(record.uri)) {
                is ProviderResult.Ok -> read.value
                ProviderResult.NotFound -> return Settled.Clean.also { saves.clear(record.uri) }
                else -> return Settled.Later
            }
        if (disk.sameBytesAs(record.old)) return Settled.Clean.also { saves.clear(record.uri) }
        if (record.bytes == null) return Settled.OutsideChange.also { saves.clear(record.uri) }
        if (disk.sameBytesAs(record.new)) return completed(record)
        val oldCopy = context.baseCopies.matches(record.uri, record.oldFingerprint)
        if (!oldCopy || !madeOfOldAndNew(record)) return Settled.OutsideChange.also { saves.clear(record.uri) }
        return if (putBack(record)) {
            saves.clear(record.uri)
            Settled.Repaired
        } else {
            Settled.Later
        }
    }

    /** The save landed; bring a journal still on the old bytes over to it, then let the record go. */
    private fun completed(record: PendingSaves.Record): Settled {
        val key = record.uri.value
        if (context.journals.fileFor(key).exists()) {
            val old = context.readBaseText(record.uri, record.oldFingerprint)?.text?.toString()
            val mine =
                old?.let {
                    when (val replay = context.journals.replay(key, it, record.oldFingerprint)) {
                        is ReplayResult.Ok -> replay.text
                        is ReplayResult.Corrupt -> replay.recoveredText
                        else -> null // Already on another base, or unreadable: the open path decides.
                    }
                }
            if (mine != null) rebase(record, mine)
        }
        saves.finish(record.uri, record.new)
        return Settled.Completed
    }

    private fun rebase(
        record: PendingSaves.Record,
        mine: String,
    ) {
        val key = record.uri.value
        val saved = record.openNew().use(::decodedText)
        if (saved == mine) {
            context.journals.markClean(key, record.oldFingerprint)
        } else {
            val lastModified =
                context.provider
                    .metadata(record.uri)
                    .valueOrNull()
                    ?.lastModified ?: 0L
            val base = BaseFingerprint(record.new.size, lastModified, record.new.sha256)
            context.journals.resume(key, base, mine, replacing = record.oldFingerprint)
        }
    }

    /**
     * True when every byte of the file equals the old or the new bytes at
     * the same position, and it is no longer than the longer of them:
     * exactly what a write of new over old, cut short anywhere (including
     * the put-back of old over a torn new), can leave.
     */
    private fun madeOfOldAndNew(record: PendingSaves.Record): Boolean {
        val disk = context.provider.openInput(record.uri).valueOrNull() ?: return false
        val old = context.baseCopies.openInput(record.uri)
        if (old == null) {
            disk.close()
            return false
        }
        return try {
            val streams = listOf(BufferedInputStream(disk), BufferedInputStream(old), record.openNew())
            streams.useAll { (d, o, n) -> sameOrOther(d, o, n) }
        } catch (_: IOException) {
            false
        }
    }

    private inline fun <T> List<InputStream>.useAll(block: (List<InputStream>) -> T): T =
        try {
            block(this)
        } finally {
            forEach { runCatching { it.close() } }
        }

    private fun sameOrOther(
        disk: InputStream,
        old: InputStream,
        new: InputStream,
    ): Boolean {
        while (true) {
            val d = disk.read()
            val o = old.read()
            val n = new.read()
            if (d < 0) return true
            if (d != o && d != n) return false
        }
    }

    /** Writes the base copy (the old bytes) back over the torn file and checks it took. */
    private fun putBack(record: PendingSaves.Record): Boolean {
        val written =
            context.baseCopies.openInput(record.uri)?.use { backup ->
                context.writer.write(record.uri) { backup.copyTo(it) }
            } ?: return false
        val back = context.probe.digest(record.uri).valueOrNull()
        return written is WriteAttempt.Written &&
            written.digest.sameBytesAs(record.old) &&
            back?.sameBytesAs(record.old) == true
    }

    private fun decodedText(input: InputStream): String? =
        (Utf8Decoder.decode(input) as? DecodeResult.Decoded)?.text?.toString()
}
