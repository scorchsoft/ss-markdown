package com.scorchsoft.ssmarkdown.history.journal

/**
 * What a journal file says about itself before its first record: which
 * document it belongs to and which bytes its edits start from.
 *
 * The startup scan reads only headers, so the recovery banner can name
 * the document and decide whether its base still matches without
 * replaying every edit.
 *
 * @property documentKey the caller's opaque name for the document, usually its content URI.
 * @property base the fingerprint of the file the edits were made against.
 * @property createdAtMillis when this journal file was written, from the store's clock.
 * @property formatVersion the on-disk format version the file was written in.
 */
public data class JournalHeader(
    public val documentKey: String,
    public val base: BaseFingerprint,
    public val createdAtMillis: Long,
    public val formatVersion: Int = JournalFormat.VERSION,
)

/**
 * The format's fixed numbers, public so tools and tests can name them.
 * FORMAT.md in this module is the byte-level description.
 */
public object JournalFormat {
    /** The format this code writes. Readers refuse anything newer as [ReplayResult.Unsupported]. */
    public const val VERSION: Int = 1

    /** The four bytes every journal starts with: "SSMJ". */
    public val MAGIC: ByteArray get() =
        byteArrayOf(
            'S'.code.toByte(),
            'S'.code.toByte(),
            'M'.code.toByte(),
            'J'.code.toByte(),
        )

    /** File extension of a journal in a [JournalStore] directory. */
    public const val FILE_EXTENSION: String = "ssmj"

    /**
     * Largest record payload accepted, 1 GiB. Large enough for a snapshot
     * of any document the editor can open; small enough that a damaged
     * length field is recognised instead of triggering a huge allocation.
     */
    public const val MAX_PAYLOAD_BYTES: Int = 1 shl 30
}
