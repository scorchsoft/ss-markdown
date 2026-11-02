package com.scorchsoft.ssmarkdown.files.fingerprint

import com.scorchsoft.ssmarkdown.files.provider.DocumentProvider
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.provider.FileMetadata
import com.scorchsoft.ssmarkdown.files.provider.ProviderResult
import com.scorchsoft.ssmarkdown.files.provider.providerCall
import com.scorchsoft.ssmarkdown.history.journal.BaseFingerprint
import java.io.OutputStream

/** What the file on disk looks like compared with the base a document was loaded from. */
sealed interface DiskState {
    /**
     * The same bytes as the base.
     *
     * @property fingerprint the disk's current fingerprint (its `lastModified` may have moved).
     */
    data class Unchanged(
        val fingerprint: BaseFingerprint,
    ) : DiskState

    /**
     * Different bytes: another app wrote the file.
     *
     * @property fingerprint the disk's fingerprint now.
     */
    data class Changed(
        val fingerprint: BaseFingerprint,
    ) : DiskState

    /** The file is gone. */
    data object Missing : DiskState

    /** The grant is gone. */
    data object PermissionLost : DiskState

    /**
     * The provider failed some other way.
     *
     * @property failure what it said.
     */
    data class Unreadable(
        val failure: ProviderResult.Failure,
    ) : DiskState
}

/**
 * Compares the disk with a base fingerprint (docs/robustness.md §2a,
 * "Fingerprints, not just timestamps").
 *
 * A [quick][check] check trusts metadata only when it is trustworthy:
 * equal size and equal, non-zero, sub-second `lastModified`. A zero time
 * or a whole-second time (a coarse provider, where a same-size edit in the
 * same second would be invisible) falls through to hashing. A thorough
 * check, which every save makes, always hashes.
 */
class DiskProbe(
    private val provider: DocumentProvider,
) {
    /**
     * Checks [uri] against [base]. When [thorough] the bytes are always
     * hashed; [copyTo] then receives every byte read, which is how a save
     * takes its backup in the same pass.
     */
    @Suppress("ReturnCount") // Each provider failure ends the check with its own state.
    fun check(
        uri: DocumentUri,
        base: BaseFingerprint,
        thorough: Boolean,
        copyTo: OutputStream? = null,
    ): DiskState {
        val metadata =
            when (val result = provider.metadata(uri)) {
                is ProviderResult.Ok -> result.value
                is ProviderResult.Failure -> return failed(result)
            }
        if (!thorough && copyTo == null && metadataProvesUnchanged(metadata, base)) return DiskState.Unchanged(base)
        val digest =
            when (val result = digest(uri, copyTo)) {
                is ProviderResult.Ok -> result.value
                is ProviderResult.Failure -> return failed(result)
            }
        val now = BaseFingerprint(digest.size, metadata.lastModified, digest.sha256)
        return if (now.matches(base)) DiskState.Unchanged(now) else DiskState.Changed(now)
    }

    /** Hashes the whole document. */
    fun digest(
        uri: DocumentUri,
        copyTo: OutputStream? = null,
    ): ProviderResult<ByteDigest> =
        when (val input = provider.openInput(uri)) {
            is ProviderResult.Ok -> providerCall { input.value.use { ByteDigest.of(it, copyTo) } }
            is ProviderResult.Failure -> input
        }

    private fun failed(failure: ProviderResult.Failure): DiskState =
        when (failure) {
            ProviderResult.NotFound -> DiskState.Missing
            ProviderResult.PermissionDenied -> DiskState.PermissionLost
            else -> DiskState.Unreadable(failure)
        }

    /** Public so the folder listing can apply the same rule to rows it already has. */
    companion object {
        private const val MILLIS_PER_SECOND = 1_000L

        /**
         * True only when metadata alone proves nothing changed: same size,
         * same `lastModified`, and that time is precise enough to trust.
         */
        fun metadataProvesUnchanged(
            metadata: FileMetadata,
            base: BaseFingerprint,
        ): Boolean =
            metadata.size == base.size &&
                metadata.lastModified == base.lastModified &&
                isPreciseTime(metadata.lastModified)

        /** Zero means unknown; an exact whole second suggests a coarse clock. Neither is trusted. */
        fun isPreciseTime(lastModified: Long): Boolean = lastModified != 0L && lastModified % MILLIS_PER_SECOND != 0L
    }
}
