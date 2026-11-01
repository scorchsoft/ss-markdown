package com.scorchsoft.ssmarkdown.files.save

import com.scorchsoft.ssmarkdown.files.fingerprint.ByteDigest
import com.scorchsoft.ssmarkdown.files.fingerprint.DiskProbe
import com.scorchsoft.ssmarkdown.files.fingerprint.DiskState
import com.scorchsoft.ssmarkdown.files.provider.DocumentProvider
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.provider.FileMetadata
import com.scorchsoft.ssmarkdown.files.provider.ProviderFailures
import com.scorchsoft.ssmarkdown.files.provider.ProviderResult
import com.scorchsoft.ssmarkdown.files.provider.valueOrNull
import com.scorchsoft.ssmarkdown.history.journal.BaseFingerprint
import com.scorchsoft.ssmarkdown.text.Utf8Encoder
import java.io.IOException
import java.io.OutputStream

/**
 * The safe save (docs/robustness.md §2), in this order and no other:
 *
 * 1. **Precondition.** Hash the disk; it must equal the base, or the
 *    outcome is [SaveOutcome.Conflict] and nothing is written. The same
 *    pass takes the backup if there is no valid one yet.
 * 2. **Backup.** The last-saved bytes are in [BaseCopyStore] before any
 *    write starts. If they cannot be, nothing is written.
 * 3. **Record the intent.** The new bytes and the digests of old and new
 *    go to [PendingSaves], durably, before the file is opened, so a kill
 *    in the middle of the write is repaired at the next launch
 *    ([com.scorchsoft.ssmarkdown.files.document.DocumentStore.scanForRecovery]).
 * 4. **Write** those bytes in `"wt"` mode, falling back to `"rwt"` + truncate.
 * 5. **Verify.** Read the file back; length and SHA-256 must equal what
 *    was written. A longer read-back is a provider that ignored
 *    truncation: rewrite once with an explicit truncate.
 * 6. Only then is the result [SaveOutcome.Saved]. Any failure after the
 *    file was opened writes the backup back, so the file ends as the old
 *    version or the new one, never a mixture.
 *
 * Blocking: run it on the I/O dispatcher. The caller marks the document
 * clean, records history and ends the journal, and only on [SaveOutcome.Saved];
 * then, once the journal has moved on, it calls [finish], which makes the
 * new bytes the base copy. Until then the base copy is the old version.
 * A save that runs past [timeout] ends as [SaveOutcome.TimedOut] with the
 * file untouched or restored; see [SaveTimeout] for where it is enforced.
 */
@Suppress("TooManyFunctions") // One function per step of the class description.
class SavePipeline(
    private val provider: DocumentProvider,
    private val baseCopies: BaseCopyStore,
    private val timeout: SaveTimeout = SaveTimeout(),
) {
    private val probe = DiskProbe(provider)
    private val writer = ProviderWriter(provider)
    private val pendingSaves = baseCopies.pendingSaves

    /** Saves [request]; see the class description for the steps. */
    @Suppress("ReturnCount") // The steps of the class description, each able to end the save.
    fun save(request: SaveRequest): SaveOutcome {
        if (request.readOnly) return SaveOutcome.ReadOnly
        val deadline = timeout.start()
        checkDiskAndBackup(request.uri, request.base)?.let { return it }
        if (deadline.expired) return SaveOutcome.TimedOut(FileIntegrity.Untouched)
        lastLook(request.uri, request.base)?.let { return it }
        val pending =
            try {
                pendingSaves.begin(request.uri, request.base) { Utf8Encoder.write(request.text, request.format, it) }
            } catch (e: IOException) {
                pendingSaves.clear(request.uri)
                return SaveOutcome.BackupFailed(isStorageFull(e))
            }
        val first = writer.write(request.uri) { out -> copyPending(pending, out, deadline) }
        val written =
            when (first) {
                is WriteAttempt.Written -> first.digest
                is WriteAttempt.NotOpened -> return settled(request, failure(first.failure, FileIntegrity.Untouched))
                is WriteAttempt.FailedAfterOpen -> return settled(request, failure(first.failure, restore(request)))
            }
        return settled(request, verifyOrRepair(request, pending, written, deadline))
    }

    /**
     * The save of [saved] to [uri] has settled (the caller's journal has
     * moved to it): the new bytes become the base copy and the intent
     * record goes. Does nothing if a later save has begun since.
     */
    fun finish(
        uri: DocumentUri,
        saved: BaseFingerprint,
    ) {
        pendingSaves.finish(uri, ByteDigest(saved.size, saved.sha256))
    }

    /** Streams the pending bytes into [out], stopping at the deadline, including right after the last byte. */
    private fun copyPending(
        pending: PendingSaves.Record,
        out: OutputStream,
        deadline: SaveDeadline,
    ) {
        pending.openNew().use { it.copyTo(deadline.guard(out)) }
        deadline.checkpoint()
    }

    /**
     * A failed save whose file holds its old bytes again needs no repair,
     * so its record goes; one whose file may be damaged
     * ([FileIntegrity.Unknown]) keeps it for the next launch to repair.
     */
    private fun settled(
        request: SaveRequest,
        outcome: SaveOutcome,
    ): SaveOutcome {
        if (outcome !is SaveOutcome.Saved &&
            outcome.fileAfter() != FileIntegrity.Unknown
        ) {
            pendingSaves.clear(request.uri)
        }
        return outcome
    }

    private fun SaveOutcome.fileAfter(): FileIntegrity? =
        when (this) {
            is SaveOutcome.Missing -> file
            is SaveOutcome.PermissionLost -> file
            is SaveOutcome.StorageFull -> file
            is SaveOutcome.VerifyFailed -> file
            is SaveOutcome.ProviderError -> file
            else -> null
        }

    /** Steps 1 and 2. Null means "carry on"; anything else ends the save. */
    @Suppress("ReturnCount") // Valid backup, blocked precondition and backup result are three endings.
    private fun checkDiskAndBackup(
        uri: DocumentUri,
        base: BaseFingerprint,
    ): SaveOutcome? {
        if (baseCopies.matches(uri, base)) return precondition(probe.check(uri, base, thorough = true))
        val staged = baseCopies.stage(uri)
        val disk = probe.check(uri, base, thorough = true, copyTo = staged.stream)
        val blocked = precondition(disk)
        if (blocked != null) {
            staged.abandon()
            return blocked
        }
        return if (staged.commit()) null else SaveOutcome.BackupFailed(isStorageFull(staged.failure))
    }

    /**
     * The precondition hashes the whole file, which takes seconds on a
     * cloud provider, so another app may write in that time. SAF has no
     * compare-and-swap, but a metadata query right before opening the file
     * shrinks the window from seconds to milliseconds (DS-17): if size or
     * time moved, hash again before writing over anything. Null means
     * "carry on".
     */
    private fun lastLook(
        uri: DocumentUri,
        base: BaseFingerprint,
    ): SaveOutcome? {
        val now = provider.metadata(uri).valueOrNull() ?: return null // The write itself will report it.
        val sizeMoved = now.size != FileMetadata.UNKNOWN_SIZE && now.size != base.size
        val timeMoved = base.lastModified != 0L && now.lastModified != base.lastModified
        return if (sizeMoved || timeMoved) precondition(probe.check(uri, base, thorough = true)) else null
    }

    private fun precondition(disk: DiskState): SaveOutcome? =
        when (disk) {
            is DiskState.Unchanged -> null
            is DiskState.Changed -> SaveOutcome.Conflict(disk.fingerprint)
            DiskState.Missing -> SaveOutcome.Missing(FileIntegrity.Untouched)
            DiskState.PermissionLost -> SaveOutcome.PermissionLost(FileIntegrity.Untouched)
            is DiskState.Unreadable -> failure(disk.failure, FileIntegrity.Untouched)
        }

    /** Step 4, with one repair attempt for providers that ignore truncation. */
    private fun verifyOrRepair(
        request: SaveRequest,
        pending: PendingSaves.Record,
        written: ByteDigest,
        deadline: SaveDeadline,
    ): SaveOutcome {
        var readBack = probe.digest(request.uri)
        val tooLong = readBack.valueOrNull()?.let { it.size > written.size } == true
        if (tooLong) {
            val retry =
                writer.write(request.uri, forceExplicitTruncate = true) { out -> copyPending(pending, out, deadline) }
            if (retry !is WriteAttempt.Written) return failure(retry.failureOrNull(), restore(request))
            readBack = probe.digest(request.uri)
        }
        return when (val back = readBack) {
            is ProviderResult.Ok -> {
                if (back.value.sameBytesAs(
                        written,
                    )
                ) {
                    saved(request.uri, written)
                } else {
                    SaveOutcome.VerifyFailed(restore(request))
                }
            }

            is ProviderResult.Failure -> {
                failure(back, restore(request))
            }
        }
    }

    /**
     * The new base: verified bytes plus the provider's new `lastModified`.
     * The base copy is still the old version; [finish] replaces it with the
     * pending bytes, which are exactly what was verified.
     */
    private fun saved(
        uri: DocumentUri,
        written: ByteDigest,
    ): SaveOutcome.Saved {
        val lastModified = provider.metadata(uri).valueOrNull()?.lastModified ?: 0L
        return SaveOutcome.Saved(BaseFingerprint(written.size, lastModified, written.sha256))
    }

    /** Writes the backup over a file a failed save may have damaged, and checks it took. */
    @Suppress("ReturnCount") // No backup and a failed rewrite both leave the file Unknown.
    private fun restore(request: SaveRequest): FileIntegrity {
        val input = baseCopies.openInput(request.uri) ?: return FileIntegrity.Unknown
        val attempt = input.use { backup -> writer.write(request.uri) { backup.copyTo(it) } }
        val restored = (attempt as? WriteAttempt.Written)?.digest ?: return FileIntegrity.Unknown
        val onDisk = probe.digest(request.uri).valueOrNull()
        val expected = ByteDigest(request.base.size, request.base.sha256)
        return if (restored.sameBytesAs(expected) && onDisk?.sameBytesAs(expected) == true) {
            FileIntegrity.Restored
        } else {
            FileIntegrity.Unknown
        }
    }

    private fun failure(
        failure: ProviderResult.Failure?,
        file: FileIntegrity,
    ): SaveOutcome =
        when (failure) {
            ProviderResult.NotFound -> SaveOutcome.Missing(file)
            ProviderResult.PermissionDenied -> SaveOutcome.PermissionLost(file)
            ProviderResult.StorageFull -> SaveOutcome.StorageFull(file)
            ProviderResult.TimedOut -> SaveOutcome.TimedOut(file)
            null -> SaveOutcome.VerifyFailed(file)
            else -> SaveOutcome.ProviderError(failure, file)
        }

    private fun WriteAttempt.failureOrNull(): ProviderResult.Failure? =
        when (this) {
            is WriteAttempt.Written -> null
            is WriteAttempt.NotOpened -> failure
            is WriteAttempt.FailedAfterOpen -> failure
        }

    private fun isStorageFull(error: Exception?): Boolean =
        error != null && ProviderFailures.classify(error) == ProviderResult.StorageFull
}
