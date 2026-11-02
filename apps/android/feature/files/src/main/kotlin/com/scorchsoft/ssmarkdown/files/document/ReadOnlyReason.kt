package com.scorchsoft.ssmarkdown.files.document

/** Why a document opened read-only; each one shows a "Read-only" badge and offers Save as (R2). */
sealed interface ReadOnlyReason {
    /**
     * The bytes are not valid in the encoding they were read as (UTF-8, or
     * UTF-16 by BOM or NUL pattern). Shown with replacement characters,
     * never saved over: that would destroy what the user cannot see.
     *
     * @property byteOffset where the first bad sequence starts.
     * @property encoding the encoding it was read as, e.g. `UTF-8`.
     */
    data class InvalidText(
        val byteOffset: Long,
        val encoding: String,
    ) : ReadOnlyReason

    /**
     * Readable but not writable here: UTF-32, by its BOM. Shown decoded so
     * it can be read and copied, and Save as writes a UTF-8 copy; the file
     * itself is never rewritten in another encoding.
     *
     * @property encoding the encoding's name, e.g. `UTF-32LE`.
     */
    data class UnsupportedEncoding(
        val encoding: String,
    ) : ReadOnlyReason

    /** NUL bytes: "This doesn't look like text". */
    data object NotText : ReadOnlyReason

    /** The provider or the granting app gave no write access. */
    data object NoWriteAccess : ReadOnlyReason

    /** Unsaved edits from a newer app version are waiting; editing would bury them. */
    data object NewerRecoveryData : ReadOnlyReason
}
