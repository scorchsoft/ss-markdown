package com.scorchsoft.ssmarkdown.markdown.edit

import com.scorchsoft.ssmarkdown.text.EditOp

/**
 * Collects the replacements a command makes, in the coordinates of the
 * [text] it read, and turns them into an [EditResult].
 *
 * Every replacement is trimmed to its minimal form on the way in, and
 * [map] moves an old offset to where it lands, so commands describe their
 * change plainly ("this prefix becomes that one") and still produce the
 * smallest possible edit.
 */
internal class EditBuilder(
    private val text: CharSequence,
) {
    private class Pending(
        val start: Int,
        val end: Int,
        val insert: String,
    ) {
        val isInsert: Boolean get() = start == end

        /** True when this edit, which comes first, cannot be applied independently of [next]. */
        fun clashesWith(next: Pending): Boolean {
            val sameInsertPoint = isInsert && next.isInsert && start == next.start
            return end > next.start || sameInsertPoint
        }

        /** True when [other] belongs before this one: it starts earlier, or is a pure insert where this starts. */
        fun goesAfter(other: Pending): Boolean = start > other.start || (start == other.start && other.isInsert)
    }

    private val pending = ArrayList<Pending>()

    /** Replaces `text[start, end)` with [replacement], trimmed to the chars that actually differ. */
    fun replace(
        start: Int,
        end: Int,
        replacement: String,
    ) {
        require(start in 0..end && end <= text.length) { "range [$start, $end) outside text of ${text.length}" }
        var head = 0
        val oldLength = end - start
        while (head < oldLength && head < replacement.length && text[start + head] == replacement[head]) head++
        var tail = 0
        while (tail < oldLength - head &&
            tail < replacement.length - head &&
            text[end - 1 - tail] == replacement[replacement.length - 1 - tail]
        ) {
            tail++
        }
        if (head == oldLength && head == replacement.length) return
        add(Pending(start + head, end - tail, replacement.substring(head, replacement.length - tail)))
    }

    fun insert(
        at: Int,
        value: String,
    ) = replace(at, at, value)

    fun delete(
        start: Int,
        end: Int,
    ) = replace(start, end, "")

    /** True when nothing has been recorded. */
    val isEmpty: Boolean get() = pending.isEmpty()

    /**
     * Where [offset] in the old text lands in the new one. An offset inside
     * a replaced range, or at a pure insertion, lands after the new text
     * when [stickAfter] is true and before it otherwise.
     */
    fun map(
        offset: Int,
        stickAfter: Boolean = true,
    ): Int {
        var delta = 0
        for (p in pending) {
            val isInsert = p.isInsert
            when {
                p.end < offset || (!isInsert && p.end == offset) -> delta += p.insert.length - (p.end - p.start)
                p.start > offset -> break
                isInsert && p.start == offset -> if (stickAfter) delta += p.insert.length
                else -> return p.start + delta + if (stickAfter) p.insert.length else 0
            }
        }
        return offset + delta
    }

    fun build(selection: TextSelection): EditResult {
        val ops = pending.asReversed().map { EditOp(it.start, text.subSequence(it.start, it.end), it.insert) }
        return EditResult(ops, selection)
    }

    /**
     * [build] with the old selection carried through [map]. A selection
     * grows to take in text inserted at its edges, so whole selected lines
     * stay selected; a caret follows [caretSticksAfter].
     */
    fun buildMapping(
        old: TextSelection,
        caretSticksAfter: Boolean = true,
    ): EditResult {
        if (old.isCaret) return build(TextSelection.caret(map(old.start, caretSticksAfter)))
        val start = map(old.start, stickAfter = false)
        return build(TextSelection(start, maxOf(start, map(old.end, stickAfter = true))))
    }

    private fun add(p: Pending) {
        // Keep ascending order, a pure insert ahead of a replacement that starts
        // where it does. Commands mostly add in order, so search from the end.
        var i = pending.size
        while (i > 0 && pending[i - 1].goesAfter(p)) i--
        check(pending.getOrNull(i - 1)?.clashesWith(p) != true) { "edits overlap at ${p.start}" }
        check(pending.getOrNull(i)?.let { p.clashesWith(it) } != true) { "edits overlap at ${p.start}" }
        pending.add(i, p)
    }
}
