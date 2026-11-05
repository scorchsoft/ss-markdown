package com.scorchsoft.ssmarkdown.markdown.tidy

import com.scorchsoft.ssmarkdown.markdown.tidy.TidyGrammar.CODE_INDENT

/**
 * [TidyRule.BlankLineAroundHeading], [TidyRule.BlankLineAroundFence] and
 * [TidyRule.ExtraBlankLines]. A blank line is only inserted between a
 * top-level heading or fence and a neighbour it already ends (a paragraph,
 * list, quote, heading or fence), so the blocks stay the blocks they were.
 */
internal class BlankLineRules(
    private val doc: TidyDoc,
    private val collapse: Boolean = true,
) {
    private val classes = doc.classes
    private val lines = doc.lines

    fun around() {
        for (i in lines.indices) {
            if (lines[i].startsWith(' ')) continue
            val cls = classes[i]
            val rule = if (cls == LineClass.HEADING) TidyRule.BlankLineAroundHeading else TidyRule.BlankLineAroundFence
            val above = cls == LineClass.HEADING || cls == LineClass.FENCE_OPEN
            val below = cls == LineClass.HEADING || cls == LineClass.FENCE_CLOSE
            if (above && i > 0 && canPrecede(i - 1)) insert(i, rule)
            if (below && i + 1 < lines.size && canFollow(i + 1)) insert(i + 1, rule)
        }
    }

    private fun insert(
        before: Int,
        rule: TidyRule,
    ) {
        if (doc.gap[before]) return
        doc.gap[before] = true
        doc.edit(doc.starts[before], doc.starts[before], "\n", rule)
    }

    /** Lines a heading or fence may be separated from by a blank line without changing the page. */
    private fun canPrecede(i: Int): Boolean =
        when (classes[i]) {
            LineClass.TEXT -> isPlainParagraph(i)
            LineClass.LIST, LineClass.QUOTE, LineClass.HEADING, LineClass.FENCE_CLOSE -> true
            else -> false
        }

    private fun canFollow(i: Int): Boolean =
        when (classes[i]) {
            LineClass.TEXT -> isPlainParagraph(i)
            LineClass.LIST, LineClass.QUOTE, LineClass.HEADING, LineClass.FENCE_OPEN -> true
            else -> false
        }

    /** Not a definition list's `:` line or a table-like row. */
    private fun isPlainParagraph(i: Int): Boolean {
        val line = lines[i]
        val first = line.getOrNull(TidyGrammar.firstText(line))
        return first != ':' && first != '~' && !line.contains('|')
    }

    /** Collapses each run of blank lines to one and removes those at the top. The run at the end is the file end's. */
    fun runs() {
        var a = 0
        while (a < lines.size) {
            var b = a
            while (b < lines.size && classes[b].isBlank) b++
            if (b > a && b < lines.size && (a until b).all { classes[it] == LineClass.BLANK }) run(a, b)
            a = maxOf(b, a + 1)
        }
    }

    private fun run(
        a: Int,
        b: Int,
    ) {
        when {
            // Blank lines after an indented line may be code: indented code, or a fence in a footnote.
            a > 0 && TidyGrammar.indent(lines[a - 1]) >= CODE_INDENT -> {
                Unit
            }

            // Spacing kept (TidyOptions): only whitespace on the blank lines goes.
            !collapse -> {
                for (i in a until b) {
                    if (lines[i].isNotEmpty()) doc.edit(doc.starts[i], doc.lineEnd(i), "", TidyRule.TrailingSpaces)
                }
            }

            // Front matter must start on the first line: removing the blank lines above `---` would make some.
            a == 0 && !lines[b].startsWith("---") -> {
                doc.edit(0, doc.starts[b], "", TidyRule.ExtraBlankLines)
            }

            // A term and its definition may be one blank line apart, not two: collapsing would join them.
            b - a >= 2 && !TidyGrammar.isDefinitionMarker(lines[b]) -> {
                doc.edit(doc.starts[a], doc.starts[b], "\n", TidyRule.ExtraBlankLines)
            }

            b - a == 1 && lines[a].isNotEmpty() -> {
                doc.edit(doc.starts[a], doc.lineEnd(a), "", TidyRule.TrailingSpaces)
            }

            else -> {
                Unit
            }
        }
    }
}
