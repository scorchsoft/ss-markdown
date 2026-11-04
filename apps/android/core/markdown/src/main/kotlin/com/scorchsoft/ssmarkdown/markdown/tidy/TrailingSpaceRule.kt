package com.scorchsoft.ssmarkdown.markdown.tidy

import com.scorchsoft.ssmarkdown.markdown.tidy.TidyGrammar.CODE_INDENT

/**
 * [TidyRule.TrailingSpaces] on lines with text. markdown-it trims the end
 * of every block and drops one space before a soft line break itself, so
 * those are removed; two or more spaces inside a paragraph are a hard
 * break and stay, and so does anything after which a code span, maths or
 * HTML might carry on to the next line (their spaces are kept as written).
 * Lines indented four or more are never touched: they may be code.
 */
internal class TrailingSpaceRule(
    private val doc: TidyDoc,
) {
    private val classes = doc.classes
    private val lines = doc.lines

    fun apply() {
        var raw = false
        var link = false
        for (i in lines.indices) {
            val line = lines[i]
            // A span can only run on inside one paragraph: start afresh after a blank line or a block of one line.
            if (startsAfresh(i)) {
                raw = false
                link = false
            }
            raw = raw || line.contains('$') || line.contains('<') || TidyGrammar.hasOpenCodeSpan(line)
            link = link || line.contains("](") || line.contains("]:")
            val keep = line.trimEnd(' ', '\t').length
            if (keep < line.length && mayStrip(i) && strips(i, keep, raw, link)) {
                doc.edit(doc.starts[i] + keep, doc.lineEnd(i), "", TidyRule.TrailingSpaces)
            }
        }
    }

    private fun startsAfresh(i: Int): Boolean {
        if (i == 0 || classes[i].isBlank || classes[i] in STARTS) return true
        val above = classes[i - 1]
        return above.isBlank || above in ENDS
    }

    private fun strips(
        i: Int,
        keep: Int,
        raw: Boolean,
        link: Boolean,
    ): Boolean {
        val line = lines[i]
        val cls = classes[i]
        return when {
            cls == LineClass.HEADING || cls == LineClass.SETEXT || cls == LineClass.BREAK -> true

            raw -> false

            endsParagraph(i) -> true

            link -> false

            // Inside a paragraph markdown-it drops one space before a line break itself, and keeps anything else.
            else -> keep == line.length - 1 && line[keep] == ' ' && line[keep - 1] != '\\'
        }
    }

    private fun mayStrip(i: Int): Boolean =
        !classes[i].isBlank && TidyGrammar.indent(lines[i]) < CODE_INDENT &&
            when (classes[i]) {
                LineClass.TEXT, LineClass.HEADING, LineClass.SETEXT, LineClass.BREAK, LineClass.LIST, LineClass.QUOTE,
                -> true

                else -> false
            }

    /** True when the next line ends the paragraph, where markdown-it trims the spaces anyway. */
    private fun endsParagraph(i: Int): Boolean {
        if (i + 1 >= lines.size || doc.gap[i + 1]) return true
        return when (classes[i + 1]) {
            LineClass.BLANK, LineClass.FROZEN_BLANK, LineClass.HEADING, LineClass.FENCE_OPEN, LineClass.BREAK,
            LineClass.SETEXT,
            -> true

            else -> false
        }
    }

    private companion object {
        val STARTS = setOf(LineClass.HEADING, LineClass.FENCE_OPEN, LineClass.BREAK)
        val ENDS = setOf(LineClass.HEADING, LineClass.FENCE_CLOSE, LineClass.BREAK, LineClass.SETEXT)
    }
}
