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

    fun classify(href: String): LinkTarget {
        val trimmed = href.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("//")) return LinkTarget.Blocked
        val found =
            scheme
                .find(trimmed)
                ?.groupValues
                ?.get(1)
                ?.lowercase()
        return when {
            found == null -> LinkTarget.Relative(trimmed)
            found in external -> LinkTarget.External(trimmed)
            else -> LinkTarget.Blocked
        }
    }
}
