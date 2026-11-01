package com.scorchsoft.ssmarkdown.files.save

import com.scorchsoft.ssmarkdown.files.provider.ProviderResult
import com.scorchsoft.ssmarkdown.history.journal.BaseFingerprint

/**
 * What happened to the file on disk when a save failed after opening it.
 * The answer the user most needs: "is my file still all right?"
 */
enum class FileIntegrity {
    /** The file was never opened for writing. */
    Untouched,

    /** A write started, failed, and the backup was written back and verified. */
    Restored,

    /** A write started and the backup could not be put back. The backup is still in app storage. */
    Unknown,
}

/**
 * Every way a save can end (docs/robustness.md §2). In every case except
 * [Saved] the document stays dirty and its journal is untouched, so no
 * outcome here can lose work.
 */
sealed interface SaveOutcome {
    /**
     * Written, read back and verified.
     *
     * @property fingerprint the file as now on disk: the document's new base.
     */
    data class Saved(
        val fingerprint: BaseFingerprint,
    ) : SaveOutcome

    /**
     * The disk no longer matches the base: another app changed the file.
     * Nothing was written; this becomes the conflict flow.
     *
     * @property disk the file's fingerprint now.
     */
    data class Conflict(
        val disk: BaseFingerprint,
    ) : SaveOutcome

    /**
     * The file was deleted or moved. Offer Save as / Recreate.
     *
     * @property file what happened to the file, if a write had started.
     */
    data class Missing(
        val file: FileIntegrity,
    ) : SaveOutcome

    /**
     * Write permission is gone. Offer Save as.
     *
     * @property file what happened to the file.
     */
    data class PermissionLost(
        val file: FileIntegrity,
    ) : SaveOutcome

    /**
     * The device or the provider is full.
     *
     * @property file what happened to the file.
     */
    data class StorageFull(
        val file: FileIntegrity,
    ) : SaveOutcome

    /**
     * The bytes read back were not the bytes written.
     *
     * @property file what happened to the file.
     */
    data class VerifyFailed(
        val file: FileIntegrity,
    ) : SaveOutcome

    /** The document is read-only (invalid UTF-8, not text, or no write grant). Offer Save as. */
    data object ReadOnly : SaveOutcome

    /**
     * The backup of the last-saved bytes could not be written to app
     * storage, so nothing was overwritten.
     *
     * @property storageFull true when app storage is out of space.
     */
    data class BackupFailed(
        val storageFull: Boolean,
    ) : SaveOutcome

    /**
     * The provider failed in some other way.
     *
     * @property failure what it reported.
     * @property file what happened to the file.
     */
    data class ProviderError(
        val failure: ProviderResult.Failure,
        val file: FileIntegrity,
    ) : SaveOutcome

    /**
     * The save ran past its [SaveTimeout]: offer Retry or Save locally.
     *
     * A timeout is reported as a [ProviderError] whose failure is
     * [ProviderResult.TimedOut], so exhaustive `when`s over [SaveOutcome]
     * written before timeouts existed keep compiling and still land on
     * "Couldn't save". Build one with `SaveOutcome.TimedOut(file)` and
     * recognise one with [SaveOutcome.isTimedOut].
     */
    object TimedOut {
        /** The outcome for a save that timed out, leaving the file as [file] says. */
        operator fun invoke(file: FileIntegrity): ProviderError = ProviderError(ProviderResult.TimedOut, file)
    }
}

/** True when the save ran out of time (see [SaveOutcome.TimedOut]). */
val SaveOutcome.isTimedOut: Boolean
    get() = this is SaveOutcome.ProviderError && failure == ProviderResult.TimedOut
