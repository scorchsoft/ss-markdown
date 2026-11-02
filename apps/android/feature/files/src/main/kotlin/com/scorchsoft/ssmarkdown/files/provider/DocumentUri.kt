package com.scorchsoft.ssmarkdown.files.provider

/**
 * A document's address, as the string form of its content URI.
 *
 * Kept as a plain string rather than `android.net.Uri` so the store, the
 * save pipeline and their tests run without an Android runtime; the
 * Android edge ([ContentResolverProvider]) converts. Drafts use the
 * [DRAFT_SCHEME] scheme and live in app storage.
 *
 * @property value the URI exactly as the provider gave it; it is also the journal's document key.
 */
@JvmInline
value class DocumentUri(
    val value: String,
) {
    /** True for an untitled document kept in app storage rather than a user file. */
    val isDraft: Boolean get() = value.startsWith("$DRAFT_SCHEME:")

    override fun toString(): String = value

    /** Well-known schemes. */
    companion object {
        /** Scheme of untitled drafts: `ssmd-draft:<id>`. Never handed to another app. */
        const val DRAFT_SCHEME: String = "ssmd-draft"
    }
}
