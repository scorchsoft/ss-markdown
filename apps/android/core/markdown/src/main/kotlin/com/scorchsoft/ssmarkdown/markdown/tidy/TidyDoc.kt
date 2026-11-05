package com.scorchsoft.ssmarkdown.markdown.tidy

import java.util.EnumMap

/**
 * One text as the rules see it: its lines (without the empty string after
 * a final line break), where each starts, each line's [LineClass], and the
 * edits and counts the rules have made so far.
 */
internal class TidyDoc(
    val text: String,
) {
    val endsWithNewline: Boolean = text.endsWith('\n')
    val lines: List<String> = text.split('\n').let { if (endsWithNewline) it.dropLast(1) else it }
    val starts: IntArray = IntArray(lines.size)
    val scanner: BlockScanner = BlockScanner(lines).scan()
    val classes: Array<LineClass> = scanner.classes

    /** `gap[i]`: a blank line is inserted before line i. */
    val gap: BooleanArray = BooleanArray(lines.size + 1)
    val edits: MutableList<TidyEdit> = mutableListOf()
    val counts: MutableMap<TidyRule, Int> = EnumMap(TidyRule::class.java)

    init {
        var at = 0
        for ((i, line) in lines.withIndex()) {
            starts[i] = at
            at += line.length + 1
        }
    }

    val size: Int get() = lines.size

    fun lineEnd(i: Int): Int = starts[i] + lines[i].length

    fun edit(
        start: Int,
        end: Int,
        replacement: String,
        rule: TidyRule,
    ) {
        edits += TidyEdit(start, end, replacement)
        counts[rule] = (counts[rule] ?: 0) + 1
    }
}
