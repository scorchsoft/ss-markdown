package com.scorchsoft.ssmarkdown.markdown.edit

/**
 * Text typed from the strip's symbols page (or anywhere that wants the
 * same rules). Replaces the selection, and with smart pairs on:
 *
 * - an opener wraps a selection: `(` around `x` gives `(x)`;
 * - an opener at a caret inserts its closer too, unless a word follows
 *   (or, for `` ` `` and `"`, a word precedes: then it is a closer);
 * - typing a closer that is already next steps over it.
 *
 * Smart pairs are **off by default**: they surprise as often as they help
 * in a text editor, and an unexpected `)` is exactly the kind of byte the
 * user did not type (docs/quality-bar.md, "Never rewrite bytes the user
 * didn't touch").
 */
internal object TypeText {
    private val pairs = mapOf('(' to ')', '[' to ']', '{' to '}', '`' to '`', '"' to '"')

    fun apply(
        text: CharSequence,
        selection: TextSelection,
        typed: String,
        smartPairs: Boolean,
    ): EditResult {
        val c = typed.singleOrNull()
        val paired = if (smartPairs && c != null) pairedTyping(text, selection, c) else null
        return paired ?: plain(text, selection, typed)
    }

    private fun plain(
        text: CharSequence,
        selection: TextSelection,
        typed: String,
    ): EditResult {
        val builder = EditBuilder(text)
        builder.replace(selection.start, selection.end, typed)
        return builder.build(TextSelection.caret(selection.start + typed.length))
    }

    /** What smart pairs do with [c], or null when they leave it as plain typing. */
    private fun pairedTyping(
        text: CharSequence,
        selection: TextSelection,
        c: Char,
    ): EditResult? {
        val closer = pairs[c]
        val caret = selection.start
        val next = text.getOrNull(selection.end)
        val builder = EditBuilder(text)
        return when {
            selection.isCaret && next == c && c in pairs.values -> {
                EditResult.unchanged(TextSelection.caret(caret + 1))
            }

            closer == null -> {
                null
            }

            !selection.isCaret -> {
                builder.insert(selection.end, closer.toString())
                builder.insert(selection.start, c.toString())
                builder.build(TextSelection(caret + 1, selection.end + 1))
            }

            canOpen(text, caret, c, closer) -> {
                builder.insert(caret, "$c$closer")
                builder.build(TextSelection.caret(caret + 1))
            }

            else -> {
                null
            }
        }
    }

    /**
     * An opener pairs up unless a word follows it. A symmetric one (`` ` ``,
     * `"`) also needs a word *not* to precede it: after a word it is
     * closing something.
     */
    private fun canOpen(
        text: CharSequence,
        caret: Int,
        c: Char,
        closer: Char,
    ): Boolean {
        val next = text.getOrNull(caret)
        val previous = text.getOrNull(caret - 1)
        val freeAfter = next == null || !next.isLetterOrDigit()
        val freeBefore = closer != c || previous == null || previous.isWhitespace()
        return freeAfter && freeBefore
    }
}
