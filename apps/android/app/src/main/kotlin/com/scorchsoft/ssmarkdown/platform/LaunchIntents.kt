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
 * - A recent file's launcher shortcut carries a hashed shortcut id, also
 *   never a URI; it opens a file only if the id maps back to one of our
 *   own recents that we may still write (ADR 0017).
 * - VIEW and EDIT from another app open in place (R2), read-only with
 *   "Save as…" when no write grant came, marked as from that app.
 */
object LaunchIntents {
    /**
     * @param fields the intent's parsed fields.
     * @param openToken the [EXTRA_OPEN_TOKEN] extra, if any.
     * @param redeem [OpenTokens.redeem].
     * @param recentShortcut the recent file an [ACTION_OPEN_RECENT] shortcut id resolves to, if any.
     * @param sourceApp the sending app's name, asked only for a VIEW or EDIT.
     */
    fun request(
        fields: IntentFields,
        openToken: String?,
        redeem: (String) -> String?,
        recentShortcut: () -> String? = { null },
        sourceApp: () -> String?,
    ): LaunchRequest? {
        if (openToken != null) {
            return redeem(openToken)?.let { LaunchRequest(it, writable = true, source = null, fromAnotherApp = false) }
        }
        if (fields.action == ACTION_OPEN_RECENT) {
            // An unknown or forged id opens nothing: the launch default runs, as for the app icon.
            return recentShortcut()?.let { LaunchRequest(it, writable = true, source = null, fromAnotherApp = false) }
        }
        val open = IncomingRequests.parse(fields) as? IncomingRequest.OpenInPlace ?: return null
        return LaunchRequest(open.uri.value, open.writable, sourceApp())
    }

    /**
     * The grant to keep for [request], when [fields] say the sender made it
     * persistable: an "Open with" grant is otherwise gone after a restart,
     * stranding any unsaved edits' file (R2-DS-2). Null when there is none
     * to keep, including for our own share sheet's tickets.
     */
    fun grantToPersist(
        fields: IntentFields,
        request: LaunchRequest?,
    ): LaunchRequest? = request?.takeIf { fields.grantsPersistable && it.fromAnotherApp }

    /**
     * A launcher long-press on a recent file's shortcut (ADR 0017). It
     * carries a shortcut id, which only `DirectShareTargets.resolve` turns
     * back into a URI, through our own recents.
     */
    const val ACTION_OPEN_RECENT: String = "com.scorchsoft.ssmarkdown.action.OPEN_RECENT"

    /** The share sheet's one-time ticket for the document to open (see [OpenTokens]). */
    const val EXTRA_OPEN_TOKEN: String = "com.scorchsoft.ssmarkdown.OPEN_TOKEN"
}
