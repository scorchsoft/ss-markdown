package com.scorchsoft.ssmarkdown.markdown.paste

/**
 * Character references in pasted HTML: numeric ones, and the named ones
 * clipboard HTML uses (browsers, Gmail, Docs and Word write a handful). An
 * unknown name stays as written.
 */
internal object HtmlEntities {
    private val reference = Regex("""&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,31});""")
    private const val HEX = 16
    private const val REPLACEMENT = 0xFFFD

    private val named =
        mapOf(
            "amp" to "&",
            "lt" to "<",
            "gt" to ">",
            "quot" to "\"",
            "apos" to "'",
            "nbsp" to "\u00A0",
            "copy" to "©",
            "reg" to "®",
            "trade" to "™",
            "hellip" to "…",
            "mdash" to "—",
            "ndash" to "–",
            "lsquo" to "‘",
            "rsquo" to "’",
            "ldquo" to "“",
            "rdquo" to "”",
            "bull" to "•",
            "middot" to "·",
            "times" to "×",
            "divide" to "÷",
            "deg" to "°",
            "euro" to "€",
            "pound" to "£",
            "cent" to "¢",
            "yen" to "¥",
            "sect" to "§",
            "para" to "¶",
            "laquo" to "«",
            "raquo" to "»",
            "shy" to "\u00AD",
            "zwj" to "\u200D",
            "zwnj" to "\u200C",
            "ensp" to "\u2002",
            "emsp" to "\u2003",
            "thinsp" to "\u2009",
            "larr" to "←",
            "rarr" to "→",
            "uarr" to "↑",
            "darr" to "↓",
            "plusmn" to "±",
            "frac12" to "½",
            "frac14" to "¼",
            "frac34" to "¾",
            "iexcl" to "¡",
            "iquest" to "¿",
            "check" to "✓",
        )

    fun decode(text: String): String {
        if ('&' !in text) return text
        return reference.replace(text) { m ->
            val body = m.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") -> codePoint(body.substring(2).toInt(HEX))
                body.startsWith("#") -> codePoint(body.substring(1).toInt())
                else -> named[body] ?: m.value
            }
        }
    }

    private fun codePoint(value: Int): String {
        val surrogate = value in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code
        val safe = if (value == 0 || value > Character.MAX_CODE_POINT || surrogate) REPLACEMENT else value
        return String(Character.toChars(safe))
    }
}
