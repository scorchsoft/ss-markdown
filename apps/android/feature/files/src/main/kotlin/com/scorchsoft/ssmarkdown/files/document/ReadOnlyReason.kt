package com.scorchsoft.ssmarkdown.files.document

/** Why a document opened read-only; each one shows a "Read-only" badge and offers Save as (R2). */
sealed interface ReadOnlyReason {
    /**
     * The bytes are not valid UTF-8. Shown with replacement characters,
     * never saved over: that would destroy what the user cannot see.
     *
     * @property byteOffset where the first bad sequence starts.
     */
    data class NotUtf8(
        val byteOffset: Long,
    ) : ReadOnlyReason

    /** NUL bytes: "This doesn't look like text". */
    data object NotText : ReadOnlyReason

    /** The provider or the granting app gave no write access. */
    data object NoWriteAccess : ReadOnlyReason

    /** Unsaved edits from a newer app version are waiting; editing would bury them. */
    data object NewerRecoveryData : ReadOnlyReason
}
