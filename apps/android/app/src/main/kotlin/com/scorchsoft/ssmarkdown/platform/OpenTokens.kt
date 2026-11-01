package com.scorchsoft.ssmarkdown.platform

import java.security.SecureRandom

/**
 * One-time tickets for "open this document" between the app's own
 * Activities (SEC-2).
 *
 * `MainActivity` is exported, so any app can start it with any extra. An
 * extra holding a URI would make it open a file through the user's grants
 * on another app's say-so, looking like a file the user chose. Instead the
 * share sheet asks for a ticket here, in process, and passes only that; the
 * editor redeems it here. Another app cannot forge one: it is 128 random
 * bits, used once, and expires in a minute.
 *
 * Thread-safe; held by `AppGraph`.
 */
class OpenTokens(
    private val clock: () -> Long,
    private val random: SecureRandom = SecureRandom(),
    private val lifetimeMillis: Long = DEFAULT_LIFETIME_MILLIS,
) {
    private class Ticket(
        val uri: String,
        val expires: Long,
    )

    private val tickets = HashMap<String, Ticket>()

    /** A new ticket for [uri]. */
    @Synchronized
    fun issue(uri: String): String {
        val now = clock()
        tickets.values.removeAll { it.expires <= now }
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        tickets[token] = Ticket(uri, now + lifetimeMillis)
        return token
    }

    /** The URI [token] was issued for, once; null for an unknown, used or expired ticket. */
    @Synchronized
    fun redeem(token: String?): String? {
        val ticket = token?.let(tickets::remove) ?: return null
        return ticket.uri.takeIf { clock() < ticket.expires }
    }

    private companion object {
        const val TOKEN_BYTES = 16
        const val DEFAULT_LIFETIME_MILLIS = 60_000L
    }
}
