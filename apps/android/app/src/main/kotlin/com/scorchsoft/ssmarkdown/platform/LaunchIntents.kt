package com.scorchsoft.ssmarkdown.platform

import com.scorchsoft.ssmarkdown.editor.LaunchRequest
import com.scorchsoft.ssmarkdown.files.saf.IncomingRequest
import com.scorchsoft.ssmarkdown.files.saf.IncomingRequests
import com.scorchsoft.ssmarkdown.files.saf.IntentFields

/**
 * What a launch intent asks the editor to open, decided from plain values
 * so it is tested without Android.
 *
 * - Our own share sheet's "Open" carries a one-time [OpenTokens] ticket,
 *   never a URI: an exported Activity must not open a file because some
 *   other app put its URI in an extra (SEC-2). A ticket that does not
 *   redeem opens nothing.
 * - VIEW and EDIT from another app open in place (R2), read-only with
 *   "Save as…" when no write grant came, marked as from that app.
 */
object LaunchIntents {
    /**
     * @param fields the intent's parsed fields.
     * @param openToken the [EXTRA_OPEN_TOKEN] extra, if any.
     * @param redeem [OpenTokens.redeem].
     * @param sourceApp the sending app's name, asked only for a VIEW or EDIT.
     */
    fun request(
        fields: IntentFields,
        openToken: String?,
        redeem: (String) -> String?,
        sourceApp: () -> String?,
    ): LaunchRequest? {
        if (openToken != null) {
            return redeem(openToken)?.let { LaunchRequest(it, writable = true, source = null, fromAnotherApp = false) }
        }
        val open = IncomingRequests.parse(fields) as? IncomingRequest.OpenInPlace ?: return null
        return LaunchRequest(open.uri.value, open.writable, sourceApp())
    }

    /** The share sheet's one-time ticket for the document to open (see [OpenTokens]). */
    const val EXTRA_OPEN_TOKEN: String = "com.scorchsoft.ssmarkdown.OPEN_TOKEN"
}
