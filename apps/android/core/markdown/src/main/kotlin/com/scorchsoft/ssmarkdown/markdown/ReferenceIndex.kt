package com.scorchsoft.ssmarkdown.markdown

/**
 * Every link reference definition (`[label]: url`) and footnote definition
 * (`[^1]: text`) in the document, by normalised label.
 *
 * References are Markdown's document-wide rule: `[text][label]` on line 3
 * may resolve to a definition on line 30,000. Tokens stay a function of
 * their own line, so this index lives beside them (docs/robustness.md,
 * "Reference definitions live in a separate document-wide index") and the
 * editor asks it when it wants to flag an unresolved reference or jump to
 * a definition. Footnote labels are stored with a leading `^`.
 */
public class ReferenceIndex internal constructor() {
    private val entries = LineKeyedList<String>()
    private val counts = HashMap<String, Int>()

    /** Number of definitions, duplicates included. */
    public val size: Int get() = entries.size

    /** True if [label] (in any case or spacing) is defined anywhere. */
    public fun contains(label: String): Boolean = counts.containsKey(normalize(label))

    /**
     * The line of the definition [label] resolves to, or -1. The first
     * definition wins, as in CommonMark. O(definitions), which is small.
     */
    public fun lineOf(label: String): Int {
        val key = normalize(label).takeIf(counts::containsKey) ?: return -1
        return entries.lineAt((0 until entries.size).first { entries.valueAt(it) == key })
    }

    /** Every defined label, in document order, without duplicates. */
    public fun labels(): List<String> = (0 until entries.size).map { entries.valueAt(it) }.distinct()

    /** Roughly the heap the definitions hold: entries, labels and the count map's nodes. */
    internal fun retainedBytesEstimate(): Long =
        entries.retainedBytesEstimate(::stringBytes) + counts.size.toLong() * COUNT_NODE_BYTES

    internal fun set(
        line: Int,
        label: String?,
    ) {
        val index = entries.indexOf(line)
        val old = if (index >= 0) entries.valueAt(index) else null
        if (old == label) return
        if (old != null) release(old)
        if (label != null) counts[label] = (counts[label] ?: 0) + 1
        entries.set(line, label)
    }

    internal fun replaceLines(
        from: Int,
        removed: Int,
        inserted: Int,
    ) {
        var index = entries.indexAtOrBefore(from - 1) + 1
        while (index < entries.size && entries.lineAt(index) < from + removed) release(entries.valueAt(index++))
        entries.replaceLines(from, removed, inserted)
    }

    private fun release(label: String) {
        val count = counts[label] ?: return
        if (count <= 1) counts.remove(label) else counts[label] = count - 1
    }

    private fun normalize(label: String): String {
        val inner = label.removeSurrounding("[", "]")
        val footnote = inner.startsWith('^')
        val key = BlockSyntax.normalizeLabel(inner, if (footnote) 1 else 0, inner.length)
        return if (footnote) "^$key" else key
    }

    private companion object {
        /** A `HashMap` node, its boxed count and its table slot. */
        const val COUNT_NODE_BYTES = 52L
    }
}
