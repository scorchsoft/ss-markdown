package com.scorchsoft.ssmarkdown.platform

import com.scorchsoft.ssmarkdown.editor.LaunchRequest
import com.scorchsoft.ssmarkdown.files.saf.IncomingRequest
import com.scorchsoft.ssmarkdown.files.saf.IncomingRequests
import com.scorchsoft.ssmarkdown.files.saf.IntentFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What an exported Activity's intent may make the editor open (SEC-2, SEC-3). */
class LaunchIntentsTest {
    private var now = 1_000L
    private val tokens = OpenTokens(clock = { now })

    private fun request(
        fields: IntentFields,
        token: String? = null,
    ): LaunchRequest? = LaunchIntents.request(fields, token, tokens::redeem) { "Gmail" }

    private val main = IntentFields(action = "android.intent.action.MAIN", data = null, type = null)

    @Test
    fun `our share sheet's ticket opens its document once, as one the user chose`() {
        val token = tokens.issue("content://tree/doc")
        assertEquals(
            LaunchRequest("content://tree/doc", writable = true, source = null, fromAnotherApp = false),
            request(main, token),
        )
        assertNull("a ticket is single use", request(main, token))
    }

    @Test
    fun `a forged, guessed or expired ticket opens nothing`() {
        assertNull(request(main, "content://com.victim/secret.md"))
        assertNull(request(main, "0".repeat(32)))
        val token = tokens.issue("content://tree/doc")
        now += 61_000
        assertNull(request(main, token))
        assertNotEquals(tokens.issue("a"), tokens.issue("a"))
    }

    @Test
    fun `a ticket is not taken from a VIEW intent's data either`() {
        val view = IntentFields(IncomingRequests.ACTION_VIEW, "content://a/1", "text/markdown")
        assertNull(request(view, "forged"))
    }

    @Test
    fun `another app's VIEW opens in place, marked as from that app`() {
        val view = IntentFields(IncomingRequests.ACTION_VIEW, "content://a/1", "text/markdown")
        assertEquals(LaunchRequest("content://a/1", writable = false, source = "Gmail"), request(view))
    }

    @Test
    fun `file URIs are refused for VIEW, EDIT and SEND`() {
        val private = "file:///data/data/com.scorchsoft.ssmarkdown/files/drafts/a.md"
        assertNull(request(IntentFields(IncomingRequests.ACTION_VIEW, private, "text/markdown")))
        assertNull(request(IntentFields(IncomingRequests.ACTION_EDIT, private, "text/markdown")))
        val send =
            IncomingRequests.parse(
                IntentFields(IncomingRequests.ACTION_SEND, null, "text/plain", listOf(private)),
            )
        assertTrue(send is IncomingRequest.Unsupported)
    }
}
