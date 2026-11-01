package com.scorchsoft.ssmarkdown.files.save

import com.scorchsoft.ssmarkdown.files.fingerprint.ByteDigest
import com.scorchsoft.ssmarkdown.files.fingerprint.DiskProbe
import com.scorchsoft.ssmarkdown.files.fingerprint.DiskState
import com.scorchsoft.ssmarkdown.files.provider.DocumentProvider
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.provider.ProviderFailures
import com.scorchsoft.ssmarkdown.files.provider.ProviderResult
import com.scorchsoft.ssmarkdown.files.provider.valueOrNull
import com.scorchsoft.ssmarkdown.history.journal.BaseFingerprint
import com.scorchsoft.ssmarkdown.text.Utf8Encoder

/**
 * The safe save (docs/robustness.md §2), in this order and no other:
 *
 * 1. **Precondition.** Hash the disk; it must equal the base, or the
 *    outcome is [SaveOutcome.Conflict] and nothing is written. The same
 *    pass takes the backup if there is no valid one yet.
 * 2. **Backup.** The last-saved bytes are in [BaseCopyStore] before any
 *    write starts. If they cannot be, nothing is written.
 * 3. **Write** in `"wt"` mode, falling back to `"rwt"` + truncate.
 * 4. **Verify.** Read the file back; length and SHA-256 must equal what
 *    was written. A longer read-back is a provider that ignored
 *    truncation: rewrite once with an explicit truncate.
 * 5. Only then is the result [SaveOutcome.Saved]. Any failure after the
 *    file was opened writes the backup back, so the file ends as the old
 *    version or the new one, never a mixture.
 *
 * Blocking: run it on the I/O dispatcher. The caller marks the document
 * clean, records history and ends the journal, and only on [SaveOutcome.Saved].
 * A save that runs past [timeout] ends as [SaveOutcome.TimedOut] with the
 * file untouched or restored; see [SaveTimeout] for where it is enforced.
 */
class SavePipeline(
    private val provider: DocumentProvider,
    private val baseCopies: BaseCopyStore,
    private val timeout: SaveTimeout = SaveTimeout(),
) {
    private val probe = DiskProbe(provider)
    private val writer = ProviderWriter(provider)

    /** Saves [request]; see the class description for the steps. */
    @Suppress("ReturnCount") // The steps of the class description, each able to end the save.
    fun save(request: SaveRequest): SaveOutcome {
        if (request.readOnly) return SaveOutcome.ReadOnly
        val deadline = timeout.start()
        checkDiskAndBackup(request.uri, request.base)?.let { return it }
        if (deadline.expired) return SaveOutcome.TimedOut(FileIntegrity.Untouched)
        val first =
            writer.write(request.uri) { Utf8Encoder.write(request.text, request.format, deadline.guard(it)) }
        val written =
            when (first) {
                is WriteAttempt.Written -> first.digest
                is WriteAttempt.NotOpened -> return failure(first.failure, FileIntegrity.Untouched)
                is WriteAttempt.FailedAfterOpen -> return failure(first.failure, restore(request))
            }
        return verifyOrRepair(request, written, deadline)
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
        written: ByteDigest,
        deadline: SaveDeadline,
    ): SaveOutcome {
        var readBack = probe.digest(request.uri)
        val tooLong = readBack.valueOrNull()?.let { it.size > written.size } == true
        if (tooLong) {
            val retry =
                writer.write(request.uri, forceExplicitTruncate = true) {
                    Utf8Encoder.write(request.text, request.format, deadline.guard(it))
                }
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

    /** The new base: verified bytes plus the provider's new `lastModified`. Refreshes the backup. */
    private fun saved(
        uri: DocumentUri,
        written: ByteDigest,
    ): SaveOutcome.Saved {
        val lastModified = provider.metadata(uri).valueOrNull()?.lastModified ?: 0L
        val fingerprint = BaseFingerprint(written.size, lastModified, written.sha256)
        refreshBaseCopy(uri, fingerprint)
        return SaveOutcome.Saved(fingerprint)
    }

    /**
     * Copies what is now on disk into the base copy. If that fails the
     * save still stands: the stale copy is dropped, and the next save's
     * precondition takes a fresh one before writing.
     */
    private fun refreshBaseCopy(
        uri: DocumentUri,
        fingerprint: BaseFingerprint,
    ) {
        val staged = baseCopies.stage(uri)
        val copied = probe.digest(uri, staged.stream).valueOrNull()
        val good = copied != null && copied.sameBytesAs(ByteDigest(fingerprint.size, fingerprint.sha256))
        if (!good || !staged.commit()) {
            staged.abandon()
            baseCopies.delete(uri)
        }
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
