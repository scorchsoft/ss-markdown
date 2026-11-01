package com.scorchsoft.ssmarkdown.files.provider

/**
 * What a provider says about a document without reading it: the cheap
 * half of an outside-change check (docs/robustness.md §2a).
 *
 * @property displayName the name to show, if the provider has one.
 * @property size length in bytes, or [UNKNOWN_SIZE] when the provider does not say.
 * @property lastModified epoch millis, or 0 when unknown. Never trusted alone: some providers
 *   report 0 or whole seconds.
 * @property mimeType the provider's MIME type, if any.
 * @property writable true when the provider advertises that this document can be written.
 */
data class FileMetadata(
    val displayName: String?,
    val size: Long,
    val lastModified: Long,
    val mimeType: String? = null,
    val writable: Boolean = true,
) {
    /** Sentinel values. */
    companion object {
        /** The provider did not report a size. */
        const val UNKNOWN_SIZE: Long = -1
    }
}
