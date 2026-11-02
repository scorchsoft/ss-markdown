package com.scorchsoft.ssmarkdown.text

/**
 * One kind of line break as it appears in a file. In memory every break
 * is `'\n'`; this is what gets written back (docs/robustness.md: the
 * original style is remembered and re-emitted, which removes the CRLF bug
 * class).
 */
public enum class LineEnding(
    /** The chars written to disk for this break. */
    public val chars: String,
) {
    /** Unix, macOS and most Markdown: `\n`. */
    LF("\n"),

    /** Windows: `\r\n`. */
    CRLF("\r\n"),

    /** Classic Mac OS: a lone `\r`. Rare, but it must round-trip like the others. */
    CR("\r"),
}
