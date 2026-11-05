package com.scorchsoft.ssmarkdown.markdown.tidy

import com.scorchsoft.ssmarkdown.markdown.tidy.TidyGrammar.CODE_INDENT

/** How sure Tidy is about a list or quote it walked. */
internal enum class RegionKind {
    /** Plain items and paragraphs: Tidy may tidy inside it. */
    SIMPLE,

    /** Holds code, HTML, tables or odd indentation, but its extent is certain: left exactly as written. */
    COMPLEX,

    /** Its extent depends on details Tidy does not model (lazy lines after code, tabs): see [BlockScanner.freeze]. */
    UNSURE,
}

/**
 * One number to rewrite: chars `[start, end)` of line [line] become [digits].
 * [repeated]: every item of its list had the same number (`1. 1. 1.`).
 */
internal data class Renumber(
    val line: Int,
    val start: Int,
    val end: Int,
    val digits: String,
    val repeated: Boolean = false,
)

/** What walking a list found: it ends before line [end]; [lastText] is its last non-blank line. */
internal class ListWalk(
    val end: Int,
    val lastText: Int,
    val kind: RegionKind,
    val renumbers: List<Renumber>,
)

/**
 * Walks a top-level list from its first item to where markdown-it would
 * end it: a line indented less than the item's content after a blank line,
 * or a line that interrupts a paragraph. Lines inside nested items are
 * sorted into items by their indentation, the way CommonMark's list item
 * rule does, so each list's items can be renumbered from its first number.
 *
 * Anything the walk cannot be sure renders the same after an edit (code
 * or HTML in an item, a lazy line whose paragraph may be code, a tab)
 * makes the region [RegionKind.COMPLEX] or [RegionKind.UNSURE], and Tidy
 * then leaves every line of it alone.
 */
internal class ListRegion(
    private val lines: List<String>,
    private val start: Int,
) {
    private class Item(
        val line: Int,
        val marker: TidyGrammar.Marker,
        val number: Long,
    ) {
        var continued = false
    }

    private val stack = ArrayList<Item>()
    private val items = ArrayList<Item>()
    private var kind = RegionKind.SIMPLE
    private var previousBlank = false
    private var previousPlain = false

    fun walk(): ListWalk {
        var j = start
        var lastText = start
        while (j < lines.size && kind != RegionKind.UNSURE) {
            val line = lines[j]
            if (TidyGrammar.isBlank(line)) {
                previousBlank = true
            } else if (TidyGrammar.hasLeadingTab(line)) {
                // Tabs move content columns in ways the walk does not follow.
                unsure()
            } else if (take(j, line)) {
                lastText = j
                previousBlank = false
            } else {
                break
            }
            j++
        }
        val renumbers = if (kind == RegionKind.SIMPLE) renumbers() else emptyList()
        return ListWalk(j, lastText, kind, renumbers)
    }

    /** Sorts non-blank line [j] into the region; false when the region ends before it. */
    private fun take(
        j: Int,
        line: String,
    ): Boolean {
        val col = TidyGrammar.indent(line)
        val marker = TidyGrammar.listMarker(line)?.takeUnless { TidyGrammar.isHr(line) }
        if (j == start) {
            open(j, requireNotNull(marker), null, popped = true)
            return true
        }
        val top = stack.first().marker.contentCol
        if (col < top) return outside(j, line, col, marker)
        var popped: Item? = null
        while (stack.size > 1 && stack.last().marker.contentCol > col) popped = stack.removeAt(stack.size - 1)
        val relative = col - stack.last().marker.contentCol
        if (marker != null && relative < CODE_INDENT) {
            open(j, marker, popped, popped != null)
        } else {
            content(line, relative)
        }
        return true
    }

    /** A line indented less than the top-level item's content: a new item, a lazy line, or the end. */
    private fun outside(
        j: Int,
        line: String,
        col: Int,
        marker: TidyGrammar.Marker?,
    ): Boolean {
        if (marker != null && col < CODE_INDENT) {
            var popped: Item? = null
            while (stack.isNotEmpty()) popped = stack.removeAt(stack.size - 1)
            open(j, marker, popped, popped = true)
            return true
        }
        if (previousBlank || TidyGrammar.interrupts(line)) return false
        // A lazy continuation line. It only belongs to a paragraph if the line above was one.
        if (kind != RegionKind.SIMPLE || !previousPlain || !TidyGrammar.isPlain(line)) {
            unsure()
            return false
        }
        while (stack.size > 1) stack.removeAt(stack.size - 1)
        stack.first().continued = true
        return true
    }

    private fun open(
        j: Int,
        marker: TidyGrammar.Marker,
        last: Item?,
        popped: Boolean,
    ) {
        val content = lines[j].substring(marker.end).trimStart(' ')
        // Preview's maths rule reads on past an item's end, across blank lines: unsure.
        if (lines[j].contains("$$")) unsure()
        if (marker.isOdd || isBlockish(content)) complex()
        // "- a\n  2. b" is the paragraph "a 2. b": only `1.` and non-empty bullets interrupt a paragraph.
        val afterText = !popped && !previousBlank && previousPlain
        if (afterText && !marker.canInterrupt) complex()
        val sibling = last?.takeIf { it.marker.kind == marker.kind }
        val number = if (sibling != null && marker.ordered) sibling.number + 1 else marker.number
        for (open in stack) open.continued = true
        val item = Item(j, marker, number)
        stack += item
        items += item
        previousPlain = !marker.empty && TidyGrammar.isPlain(content)
    }

    private fun content(
        line: String,
        relative: Int,
    ) {
        val text = line.trimStart(' ')
        if (text.contains("$$")) unsure()
        if (relative >= CODE_INDENT || isBlockish(text) || TidyGrammar.isSetextUnderline(text)) complex()
        for (open in stack) open.continued = true
        previousPlain = TidyGrammar.isPlain(text)
    }

    /** Text an item may not hold for Tidy to touch it: code, maths, HTML, quotes, tables, rules, definitions. */
    private fun isBlockish(text: String): Boolean =
        text.startsWith("```") || text.startsWith("~~~") || text.startsWith("$$") || text.startsWith("<") ||
            text.startsWith(">") || text.contains('|') || TidyGrammar.isHr(text) ||
            TidyGrammar.isDefinitionMarker(text) ||
            // `- - ```` and the like: a marker whose own content is one of the above.
            TidyGrammar.listMarker(text)?.let { it.empty || isBlockish(text.substring(it.end).trimStart(' ')) } == true

    private fun complex() {
        if (kind == RegionKind.SIMPLE) kind = RegionKind.COMPLEX
    }

    private fun unsure() {
        kind = RegionKind.UNSURE
    }

    /**
     * The numbers to rewrite. A number whose width changes moves its item's
     * content column, so it is rewritten only when nothing below depends on
     * that column: the item has no other lines, and the next line is
     * indented less than either column.
     */
    private fun renumbers(): List<Renumber> {
        val numbers = items.filter { it.marker.ordered }.map { it.marker.number }
        val repeated = numbers.size > 1 && numbers.distinct().size == 1
        return items.mapNotNull { item ->
            val m = item.marker
            if (!m.ordered || item.number == m.number || item.number > MAX_NUMBER) return@mapNotNull null
            val digits = item.number.toString()
            if (digits.length != m.digits) {
                val shifted = m.contentCol + digits.length - m.digits
                if (item.continued || nextIndent(item.line) >= minOf(shifted, m.contentCol)) return@mapNotNull null
            }
            Renumber(item.line, m.numberStart, m.end - 1, digits, repeated)
        }
    }

    private fun nextIndent(line: Int): Int {
        for (j in line + 1 until lines.size) {
            if (!TidyGrammar.isBlank(lines[j])) return TidyGrammar.indent(lines[j])
        }
        return -1
    }

    private companion object {
        const val MAX_NUMBER = 999_999_999L
    }
}
