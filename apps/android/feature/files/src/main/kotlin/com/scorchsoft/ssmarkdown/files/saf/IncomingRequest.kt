package com.scorchsoft.ssmarkdown.files.saf

import com.scorchsoft.ssmarkdown.files.provider.DocumentUri

/**
 * The parts of an incoming `Intent` that matter, as plain values, so the
 * parsing below is tested without Android. [IntentFields.from] builds it.
 *
 * @property action the intent action.
 * @property data the data URI.
 * @property type the MIME type.
 * @property streams `EXTRA_STREAM` URIs (one for SEND, several for SEND_MULTIPLE).
 * @property text `EXTRA_TEXT`.
 * @property subject `EXTRA_SUBJECT`, used as a title.
 * @property grantsWrite `FLAG_GRANT_WRITE_URI_PERMISSION` was set.
 */
data class IntentFields(
    val action: String?,
    val data: String?,
    val type: String?,
    val streams: List<String> = emptyList(),
    val text: CharSequence? = null,
    val subject: String? = null,
    val grantsWrite: Boolean = false,
    val grantsPersistable: Boolean = false,
) {
    /** Home of the Android adapter, `IntentFields.from(intent)`. */
    companion object
}

/** What another app asked of us (R2: edit in place, never silently import a copy). */
sealed interface IncomingRequest {
    /**
     * VIEW or EDIT a document where it is.
     *
     * @property uri the document.
     * @property writable false shows the Read-only badge with "Save as…" from the start.
     */
    data class OpenInPlace(
        val uri: DocumentUri,
        val writable: Boolean,
    ) : IncomingRequest

    /**
     * SEND with text in the intent: a new note (or an append, chosen in the share sheet).
     *
     * @property text what was shared.
     * @property title the sender's subject, if any.
     */
    data class SharedText(
        val text: String,
        val title: String?,
    ) : IncomingRequest

    /**
     * SEND with one or more stream URIs: read with a size check, never trusted as small.
     *
     * @property uris the shared streams.
     */
    data class SharedStreams(
        val uris: List<DocumentUri>,
    ) : IncomingRequest

    /** Nothing we can act on. */
    data object Unsupported : IncomingRequest
}

/** Turns [IntentFields] into an [IncomingRequest]. */
object IncomingRequests {
    /** `Intent.ACTION_VIEW`. */
    const val ACTION_VIEW: String = "android.intent.action.VIEW"

    /** `Intent.ACTION_EDIT`. */
    const val ACTION_EDIT: String = "android.intent.action.EDIT"

    /** `Intent.ACTION_SEND`. */
    const val ACTION_SEND: String = "android.intent.action.SEND"

    /** `Intent.ACTION_SEND_MULTIPLE`. */
    const val ACTION_SEND_MULTIPLE: String = "android.intent.action.SEND_MULTIPLE"

    /**
     * `content:` only. A `file:` URI would be opened with this app's own
     * identity, so another app could make us read our private files (drafts,
     * journals) into the share sheet; senders on API 24+ cannot legitimately
     * send one anyway (SEC-3).
     */
    private val OPENABLE_SCHEMES = setOf("content")

    /** Parses [fields]. Streams win over text, as the sender meant the file. */
    fun parse(fields: IntentFields): IncomingRequest =
        when (fields.action) {
            ACTION_VIEW, ACTION_EDIT -> openInPlace(fields)
            ACTION_SEND, ACTION_SEND_MULTIPLE -> shared(fields)
            else -> IncomingRequest.Unsupported
        }

    @Suppress("ReturnCount") // No data and a foreign scheme are both Unsupported.
    private fun openInPlace(fields: IntentFields): IncomingRequest {
        val data = fields.data ?: return IncomingRequest.Unsupported
        if (data.substringBefore(':').lowercase() !in OPENABLE_SCHEMES) return IncomingRequest.Unsupported
        val writable = fields.action == ACTION_EDIT || fields.grantsWrite
        return IncomingRequest.OpenInPlace(DocumentUri(data), writable)
    }

    @Suppress("ReturnCount") // Streams win; text is the fallback; neither is Unsupported.
    private fun shared(fields: IntentFields): IncomingRequest {
        val streams = fields.streams.filter { it.substringBefore(':').lowercase() in OPENABLE_SCHEMES }
        if (streams.isNotEmpty()) return IncomingRequest.SharedStreams(streams.map(::DocumentUri))
        val text = fields.text?.toString()?.takeIf { it.isNotEmpty() } ?: return IncomingRequest.Unsupported
        return IncomingRequest.SharedText(text, fields.subject?.takeIf { it.isNotBlank() })
    }
}
