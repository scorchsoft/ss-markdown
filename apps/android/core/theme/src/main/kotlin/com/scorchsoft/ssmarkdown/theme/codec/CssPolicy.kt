package com.scorchsoft.ssmarkdown.theme.codec

/**
 * What a custom theme's "Advanced CSS" may contain. It is appended inside
 * the preview's `<style>`, so it must not be able to close that element,
 * and the app is offline (CLAUDE.md), so it may not pull anything from the
 * network.
 *
 * The preview's Content-Security-Policy is the wall: it allows no network
 * at all, so CSS that slipped past this check still could not load
 * anything. This check exists so the person hears why at import time, on
 * the right line, and so a theme that tries is refused whole. To mean
 * anything it reads CSS the way the browser does: comments are removed and
 * escapes decoded first (`@\69mport`, `\75rl(`, `url("\68ttps:…")`), and
 * `image-set("…")` loads without any `url(` (security review SEC-5).
 *
 * The preview page has the same rules in `web/src/core/cssPolicy.ts`;
 * change both together.
 */
internal object CssPolicy {
    /** Longest custom CSS accepted, in characters. */
    const val MAX_LENGTH = 20_000

    private const val OFFLINE = "can't load from the internet; themes work offline"
    private const val MAX_CODE_POINT = 0x10FFFF
    private const val HEX = 16

    private val comment = Regex("""/\*[\s\S]*?(?:\*/|$)""")
    private val escape = Regex("""\\(?:([0-9a-fA-F]{1,6})[\t\n\u000C\r ]?|\r\n|[\n\u000C\r]|([\s\S]))""")
    private val url = Regex("""url\s*\(([^)]*)\)?""")
    private val scheme = Regex("^[a-z][a-z0-9+.-]*:")

    private val rules =
        listOf(
            Regex("</") to "can't contain “</”",
            Regex("@import") to "can't use @import; themes work offline",
            Regex("@font-face") to "can't declare fonts; pick them in the theme's Fonts",
            Regex("""image-set\s*\(|(?:^|[^a-z-])src\s*\(""") to OFFLINE,
            Regex("""expression\s*\(|javascript:|-moz-binding|behavior\s*:""") to "can't contain script",
        )

    /** The reason [css] is refused, or null when it is acceptable. */
    fun violation(css: String): String? {
        if (css.length > MAX_LENGTH) return "is longer than $MAX_LENGTH characters"
        val normal = normalize(css)
        return rules.firstOrNull { (pattern, _) -> pattern.containsMatchIn(normal) }?.second
            ?: OFFLINE.takeUnless { url.findAll(normal).all { isLocal(it.groupValues[1]) } }
    }

    /** [css] as the browser's tokenizer sees it: no comments, escapes decoded, lower case. */
    fun normalize(css: String): String =
        css
            .replace(comment, "")
            .replace(escape) { m ->
                val hex = m.groups[1]?.value
                when {
                    hex != null -> codePoint(hex.toInt(HEX))
                    else -> m.groups[2]?.value.orEmpty()
                }
            }.lowercase()

    /** A decoded escape; NUL, surrogates and out-of-range values become U+FFFD as in CSS Syntax §4.3.7. */
    private fun codePoint(code: Int): String =
        if (code == 0 || code > MAX_CODE_POINT || code in Char.MIN_SURROGATE.code..Char.MAX_SURROGATE.code) {
            "\uFFFD"
        } else {
            String(Character.toChars(code))
        }

    /** A url() argument that stays inside the page: `data:` or a relative path. */
    private fun isLocal(argument: String): Boolean {
        val target =
            argument
                .trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
                .trim()
        return target.startsWith("data:") ||
            !(scheme.containsMatchIn(target) || target.startsWith("//") || target.startsWith("\\"))
    }
}
