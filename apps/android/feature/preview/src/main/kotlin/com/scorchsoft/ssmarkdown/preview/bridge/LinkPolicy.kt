package com.scorchsoft.ssmarkdown.preview.bridge

/** Where a tapped link may go (docs/edge-cases.md, "Malicious Markdown"). */
sealed interface LinkTarget {
    /** A web, mail or phone link: open in another app after the user confirms in the chip. */
    data class External(
        val url: String,
    ) : LinkTarget

    /** A relative link to another file next to this document. */
    data class Relative(
        val path: String,
    ) : LinkTarget

    /** Anything else (`file:`, `content:`, `intent:`, `javascript:`…): never opened. */
    data object Blocked : LinkTarget
}

/**
 * Classifies hrefs from the page. The page already strips unsafe schemes; this
 * is the second check, on the Kotlin side, because the page renders untrusted
 * documents and its messages are data, not instructions.
 */
object LinkPolicy {
    private val external = setOf("http", "https", "mailto", "tel")
    private val scheme = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")

    /** Bidi controls can make a URL read as something else in the chip; they never belong in one. */
    private val bidiControls = Regex("[\u200E\u200F\u202A-\u202E\u2066-\u2069]")

    /**
     * The mailto fields a mail app may prefill. Others (`attach`,
     * `attachment`, …) are honoured by some clients and could attach a
     * file from the phone to a message the user did not write (SEC-7).
     */
    private val mailFields = setOf("subject", "body", "cc", "bcc")

    fun classify(href: String): LinkTarget {
        val trimmed = href.replace(bidiControls, "").trim()
        if (trimmed.isEmpty() || trimmed.startsWith("//")) return LinkTarget.Blocked
        val found =
            scheme
                .find(trimmed)
                ?.groupValues
                ?.get(1)
                ?.lowercase()
        return when {
            found == null -> LinkTarget.Relative(trimmed)
            found == "mailto" -> LinkTarget.External(mailto(trimmed))
            found in external -> LinkTarget.External(trimmed)
            else -> LinkTarget.Blocked
        }
    }

    /** [href] with every query field but [mailFields] removed. */
    private fun mailto(href: String): String {
        val query = href.substringAfter('?', "")
        if (query.isEmpty()) return href
        val kept = query.split('&').filter { it.substringBefore('=').lowercase() in mailFields }
        val address = href.substringBefore('?')
        return if (kept.isEmpty()) address else "$address?${kept.joinToString("&")}"
    }
}
