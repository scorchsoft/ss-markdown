package com.scorchsoft.ssmarkdown.markdown

/**
 * One heading of the document, as the Navigator's outline lists it.
 *
 * [text] is the heading as a reader sees it: markers, link URLs and
 * escapes stripped, whitespace collapsed, so `## **Save** [flow](#x)`
 * reads "Save flow".
 */
public data class Heading(
    /** 0-based line of the heading text (for a setext heading, the line above its underline). */
    public val line: Int,
    /** 1 to 6. */
    public val level: Int,
    /** Readable heading text. */
    public val text: String,
)

/**
 * The document's headings, ATX and setext, kept current by the
 * [IncrementalTokenizer] as lines are tokenised: the block index behind
 * the Navigator's outline, the title breadcrumb and "Copy section"
 * (docs/ux-spec.md, "Navigator" and "Where am I?").
 *
 * Built from tokenizer results, so a `#` inside a fence or front matter is
 * never a heading. Lines past the tokenizer's accurate frontier still show
 * the headings they had before (eventually consistent, like the tokens);
 * [IncrementalTokenizer.isComplete] says when the index is exact.
 *
 * Parents and section ends are derived in one O(headings) stack pass,
 * cached until the set of headings changes, so a breadcrumb or section
 * query on a 10k-heading document is a binary search plus a short walk.
 */
@Suppress("TooManyFunctions") // Queries, the tokenizer's updates and the detached copy's: one index.
public class Outline private constructor(
    private val entries: LineKeyedList<Entry>,
    /** True for a [copy]: it follows edits by [followEdit] instead of being fed by a tokenizer. */
    private val detached: Boolean,
) {
    internal constructor() : this(LineKeyedList(), detached = false)

    private var derivedVersion = -1
    private var parents = IntArray(0)
    private var sectionEnds = IntArray(0)

    /** Times the parent and section arrays were rebuilt: a statistic for the bounded-work tests. */
    internal var derivations: Int = 0
        private set

    /** Lines in the document, for the end of the last section. */
    internal var lineCount: Int = 1

    /** Number of headings. */
    public val size: Int get() = entries.size

    /** The heading at [index], in document order. */
    public fun heading(index: Int): Heading {
        val entry = entries.valueAt(index)
        return Heading(entries.lineAt(index), entry.level, entry.text)
    }

    /** Every heading, in document order. O(headings): for building the outline sheet, not for per-frame use. */
    public fun headings(): List<Heading> = List(size) { heading(it) }

    /** Index of the heading on [line], or -1. */
    public fun indexOfLine(line: Int): Int = entries.indexOf(line)

    /** Index of the last heading at or above [line]: the section [line] is in. -1 before the first heading. */
    public fun indexAt(line: Int): Int = entries.indexAtOrBefore(line)

    /** Index of the heading [index] is nested under (the nearest earlier heading of a lower level), or -1. */
    public fun parentOf(index: Int): Int {
        derive()
        return parents[index]
    }

    /**
     * The lines of the section headed on [headingLine]: from the heading to
     * the line before the next heading of the same or a higher level, or to
     * the end of the document. Null if no heading is on that line. This is
     * what "Copy section" copies.
     */
    public fun sectionRange(headingLine: Int): IntRange? {
        val index = entries.indexOf(headingLine)
        if (index < 0) return null
        derive()
        val next = sectionEnds[index]
        val last = if (next < 0) lineCount - 1 else entries.lineAt(next) - 1
        return headingLine..last
    }

    /**
     * The chain of headings that contain [line], outermost first: the
     * breadcrumb under the title ("How we work › Code review"). Empty before
     * the first heading.
     */
    public fun breadcrumbAt(line: Int): List<Heading> {
        var index = indexAt(line)
        if (index < 0) return emptyList()
        derive()
        val chain = ArrayList<Heading>(BlockSyntax.MAX_HEADING_LEVEL)
        while (index >= 0) {
            chain.add(heading(index))
            index = parents[index]
        }
        chain.reverse()
        return chain
    }

    internal fun set(
        line: Int,
        level: Int,
        text: String?,
    ) {
        entries.set(line, if (level > 0 && text != null) Entry(level, text) else null)
    }

    internal fun replaceLines(
        from: Int,
        removed: Int,
        inserted: Int,
    ) {
        entries.replaceLines(from, removed, inserted)
    }

    /** Roughly the heap the headings hold: entries, texts and the derived arrays. */
    internal fun retainedBytesEstimate(): Long =
        entries.retainedBytesEstimate { ENTRY_BYTES + stringBytes(it.text) } +
            (parents.size + sectionEnds.size).toLong() * Int.SIZE_BYTES

    /**
     * A detached copy of the headings as they are now, to hand to another
     * thread: the outline tokenizer runs on a background snapshot and
     * publishes copies to the main thread (performance review PERF-D).
     * Headings are immutable, so the copy costs two array copies.
     */
    public fun copy(): Outline {
        val copy = Outline(entries.copy(), detached = true)
        copy.lineCount = lineCount
        return copy
    }

    /**
     * Follows an edit on a detached copy until a fresh one arrives: lines
     * `[from, from + removed)` became [inserted] lines. Headings on lines
     * that still exist stay where they are (the next copy corrects their
     * text, so the breadcrumb never blinks while a heading is typed in),
     * headings on deleted lines go, and later ones move. Line numbers are
     * therefore always valid for the current text, if briefly stale in
     * content.
     */
    public fun followEdit(
        from: Int,
        removed: Int,
        inserted: Int,
    ) {
        check(detached) { "a tokenizer's own outline follows its tokenizer" }
        val kept = minOf(removed, inserted)
        entries.replaceLines(from + kept, removed - kept, inserted - kept)
        lineCount += inserted - removed
    }

    /** One stack pass computes every heading's parent and the heading that ends its section. */
    private fun derive() {
        if (derivedVersion == entries.version) return
        val count = entries.size
        parents = IntArray(count)
        sectionEnds = IntArray(count)
        val stack = IntArray(count)
        var top = 0
        for (i in 0 until count) {
            val level = entries.valueAt(i).level
            while (top > 0 && entries.valueAt(stack[top - 1]).level >= level) sectionEnds[stack[--top]] = i
            parents[i] = if (top > 0) stack[top - 1] else -1
            stack[top++] = i
        }
        while (top > 0) sectionEnds[stack[--top]] = -1
        derivedVersion = entries.version
        derivations++
    }

    private data class Entry(
        val level: Int,
        val text: String,
    )

    internal companion object {
        private const val MAX_TEXT = 200

        /** An [Entry]: header, level and a reference. */
        private const val ENTRY_BYTES = 24L

        private val HIDDEN =
            setOf(
                TokenKind.HEADING_MARKER,
                TokenKind.EMPHASIS_MARKER,
                TokenKind.STRONG_MARKER,
                TokenKind.STRIKETHROUGH_MARKER,
                TokenKind.CODE_SPAN_MARKER,
                TokenKind.ESCAPE,
                TokenKind.LINK_PUNCTUATION,
                TokenKind.LINK_URL,
                TokenKind.LINK_TITLE,
                TokenKind.LINK_LABEL,
                TokenKind.IMAGE_MARKER,
                TokenKind.QUOTE_MARKER,
                TokenKind.LIST_MARKER,
                TokenKind.TASK_OPEN,
                TokenKind.TASK_DONE,
                TokenKind.HTML_TAG,
                TokenKind.MATH_DELIMITER,
            )

        /** The readable text of a heading line: visible tokens only, whitespace collapsed, at most 200 chars. */
        fun headingText(
            line: CharSequence,
            tokens: LongArray,
        ): String {
            val out = CollapsedText(MAX_TEXT)
            for (token in tokens) {
                // CollapsedText ignores input once full, and a line is at most MAX_TOKENIZED_COLUMNS long.
                if (Tokens.kind(token) !in
                    HIDDEN
                ) {
                    for (i in Tokens.start(token) until Tokens.end(token)) out.append(line[i])
                }
            }
            return out.toString()
        }
    }
}
