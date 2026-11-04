package com.scorchsoft.ssmarkdown.markdown.paste

import com.scorchsoft.ssmarkdown.markdown.LinearSyntax

/**
 * Words from pasted HTML written as Markdown text: syntax characters are
 * escaped only where they could act (`snake_case`, `a * b` and `$5` stay as
 * they are), and block syntax at a line's start is escaped so a line of
 * text never turns into a heading or a list. The same rules as Preview's
 * `escapePasted` (`web/src/core/paste.ts`), with spaces spelled out so the
 * Kotlin and TypeScript regexes agree character for character.
 */
internal object MarkdownText {
    /** A no-break space; HTML keeps it, Markdown output turns it into a space. */
    const val NBSP = '\u00A0'

    /** Whitespace the HTML parser collapses (not the no-break space). */
    val collapsible = Regex("[ \\t\\n\\r\\f]+")

    private val inlineSyntax =
        Regex(
            """\\(?=[!-/:-@\[-`{-~]|$)|[`\[\]]|(?<![ \t\n\r\f\u000B\u00A0])[*~^]|[*~^](?![ \t\n\r\f\u000B\u00A0])|""" +
                """(?<![\p{L}\p{N}])_|_(?![\p{L}\p{N}])|<(?=[A-Za-z/!?]|$)|(?<==|^)=|=(?==|$)|""" +
                """\$(?![ \t\n\r\f\u000B\u00A00-9])|&(?=#?[A-Za-z0-9_]+;)|:(?=[A-Za-z_+-][A-Za-z0-9_+-]*:)""",
        )
    private val blockStart = Regex("""^(?:[#+-](?=[ \t]|$)|>)""")
    private const val MIN_DASH_RULE = 3
    private val numberStart = Regex("""^([0-9]{1,9})(?=[.)](?:[ \t]|$))""")

    /** [text] with every character that could act as inline syntax escaped. */
    fun escapeInline(text: String): String = inlineSyntax.replace(text) { "\\" + it.value }

    /** [line] with block syntax at its start escaped (`\# `, `\- `, `1\. `, `\>`). */
    fun escapeLineStart(line: String): String {
        // `---` and `- - -` (a rule or setext underline) by scan: as a regex group it recursed per dash.
        val rule = LinearSyntax.isRunOf(line, 0, '-', MIN_DASH_RULE)
        val marked = if (rule || blockStart.containsMatchIn(line)) "\\" + line else line
        return numberStart.replace(marked) { it.value + "\\" }
    }

    /** The longest run of [c] in [text]. */
    fun longestRun(
        text: String,
        c: Char,
    ): Int {
        var best = 0
        var run = 0
        for (ch in text) {
            run = if (ch == c) run + 1 else 0
            if (run > best) best = run
        }
        return best
    }
}
