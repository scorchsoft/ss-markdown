package com.scorchsoft.ssmarkdown.navigator

/**
 * The Navigator's matching rule (ux-spec "Navigator"): a query matches
 * only at the **start of a word**, contiguously. "sav" matches "Save flow"
 * and "savings-plan.md", never "re*s*e*a*rch-o*v*erview". Case is ignored.
 *
 * A word starts at the beginning, after any non-letter-or-digit, or at a
 * lower-to-upper case change ("saveFlow" has a word at "Flow").
 */
object WordStartMatcher {
    /** Where [query] matches [text] at a word start, or -1. Blank queries match at 0. */
    @Suppress("ReturnCount") // A blank query, a word-start hit and no hit.
    fun indexIn(
        text: String,
        query: String,
    ): Int {
        val q = query.trim()
        if (q.isEmpty()) return 0
        var i = text.indexOf(q, ignoreCase = true)
        while (i >= 0) {
            if (isWordStart(text, i)) return i
            i = text.indexOf(q, i + 1, ignoreCase = true)
        }
        return -1
    }

    /** True when [query] matches [text] at a word start. */
    fun matches(
        text: String,
        query: String,
    ): Boolean = indexIn(text, query) >= 0

    private fun isWordStart(
        text: String,
        index: Int,
    ): Boolean {
        if (index == 0) return true
        val before = text[index - 1]
        val at = text[index]
        return !before.isLetterOrDigit() || (before.isLowerCase() && at.isUpperCase())
    }
}
