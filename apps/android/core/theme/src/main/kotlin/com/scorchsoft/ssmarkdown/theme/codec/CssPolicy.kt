package com.scorchsoft.ssmarkdown.theme.codec

/**
 * What a custom theme's "Advanced CSS" may contain. It is appended inside
 * the preview's `<style>`, so it must not be able to close that element,
 * and the app is offline (CLAUDE.md), so it may not pull anything from the
 * network. The preview's Content-Security-Policy is the real wall; this
 * check exists so the person hears why at import time, on the right line.
 */
internal object CssPolicy {
    /** Longest custom CSS accepted, in characters. */
    const val MAX_LENGTH = 20_000

    private val rules =
        listOf(
            Regex("</", RegexOption.IGNORE_CASE) to "can't contain “</”",
            Regex("@import", RegexOption.IGNORE_CASE) to "can't use @import; themes work offline",
            Regex("""url\(\s*['"]?\s*(https?:|//|ftp:)""", RegexOption.IGNORE_CASE) to
                "can't load from the internet; themes work offline",
            Regex("""expression\s*\(|javascript:""", RegexOption.IGNORE_CASE) to "can't contain script",
        )

    /** The reason [css] is refused, or null when it is acceptable. */
    fun violation(css: String): String? {
        if (css.length > MAX_LENGTH) return "is longer than $MAX_LENGTH characters"
        return rules.firstOrNull { (pattern, _) -> pattern.containsMatchIn(css) }?.second
    }
}
