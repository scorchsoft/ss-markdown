package com.scorchsoft.ssmarkdown.markdown

/**
 * Per-line tokens for a whole document, kept current lazily and within a
 * time budget: the Code pane's syntax-highlighting cache
 * (docs/robustness.md, "Lazy, with a time budget").
 *
 * For each line it keeps the [LineResult], the state the line was
 * tokenised from and the [LineRole] it was read with. Lines before the
 * first *pending mark* are accurate. An edit ([invalidate]) drops the
 * edited lines' results and places a mark; [tokenizeUntil] and
 * [continueInIdle] work forward from the first mark and **stop as soon as a
 * line's new end state equals the start state the next line was tokenised
 * from** (VS Code's `textModelTokens`). Typing in a paragraph therefore
 * re-tokenises one line, while opening a fence on line 0 of a 200k-line
 * file re-tokenises the viewport within the budget and the rest in idle
 * slices. Lines not reached yet are answered by [tokenizeHeuristically]:
 * their last tokens if the text is unchanged, otherwise a guess from the
 * nearest known state, corrected when the accurate pass arrives.
 *
 * The two non-local rules are handled here. A setext underline or table
 * delimiter row restyles the line above it, so whenever a line's
 * [LineResult.previousLineRole] differs from what the line above was read
 * with, that one line is re-tokenised (and an edit re-checks the line
 * above it). Reference definitions and headings feed [references] and
 * [outline] as lines are stored.
 *
 * Not thread-safe, and it starts no threads: the caller schedules the
 * slices (CodeMirror's model) and confines the cache to one thread.
 */
@Suppress("TooManyFunctions") // The cache's whole contract: edits, budgeted passes, lookups and stats.
public class IncrementalTokenizer(
    private val source: LineSource,
    private val tokenizer: LineTokenizer = LineTokenizer(),
) {
    /** Headings, for the outline, breadcrumb and Copy section. */
    public val outline: Outline = Outline()

    /** Reference and footnote definitions, by label. */
    public val references: ReferenceIndex = ReferenceIndex()

    /** Lines as of the last [invalidate]; must match the source's count. */
    public var lineCount: Int = source.lineCount
        private set

    /**
     * Lines tokenised by accurate passes so far, role re-reads included. A
     * statistic for tests and benchmarks: it is how "an edit costs one line"
     * is measured.
     */
    public var linesTokenized: Long = 0
        private set

    private var results = arrayOfNulls<LineResult>(lineCount)
    private var starts = arrayOfNulls<LineState>(lineCount)
    private var roles = ByteArray(lineCount)
    private val marks = SortedMarks()
    private val guesser = LineTokenizer()

    init {
        marks.add(0)
        outline.lineCount = lineCount
    }

    /** True once every line is accurate and the outline and reference index are exact. */
    public val isComplete: Boolean get() = marks.isEmpty

    /** Lines `[0, accurateLineCount)` are accurate. */
    public val accurateLineCount: Int get() = if (marks.isEmpty) lineCount else marks.first

    /** True if [line]'s tokens are final for the current text. */
    public fun isAccurate(line: Int): Boolean = line < accurateLineCount

    /**
     * Records an edit: old lines `[fromLine, fromLine + linesRemoved)` were
     * replaced by `linesInserted` new ones. A change inside one line is
     * `(line, 1, 1)`; splitting it with Enter is `(line, 1, 2)`. Call it after
     * the source has changed and before anything else reads the cache.
     */
    public fun invalidate(
        fromLine: Int,
        linesRemoved: Int,
        linesInserted: Int,
    ) {
        require(
            fromLine in 0..lineCount && linesRemoved >= 0 && linesInserted >= 0 && fromLine + linesRemoved <= lineCount,
        ) {
            "Edit ($fromLine, -$linesRemoved, +$linesInserted) is outside $lineCount lines"
        }
        val newCount = lineCount - linesRemoved + linesInserted
        replaceRange(fromLine, linesRemoved, linesInserted, newCount)
        outline.replaceLines(fromLine, linesRemoved, linesInserted)
        references.replaceLines(fromLine, linesRemoved, linesInserted)
        marks.replaceLines(fromLine, linesRemoved, linesInserted)
        lineCount = newCount
        outline.lineCount = newCount
        // The line above may have been read as a setext heading or table header because of a line that just changed.
        val lookBack = fromLine > 0 && roles[fromLine - 1] != AS_IS
        if (lookBack) {
            roles[fromLine - 1] = AS_IS
            marks.add(fromLine - 1)
        } else if (fromLine < newCount) {
            marks.add(fromLine)
        }
    }

    /**
     * Tokenises accurately up to and including [line], stopping early once
     * [budgetNanos] has passed on [clock]; at least one line is always done,
     * so repeated calls always progress. Returns true if [line] is accurate.
     * Meant for the main thread before drawing, with the ~8 ms budget of
     * docs/robustness.md.
     */
    public fun tokenizeUntil(
        line: Int,
        budgetNanos: Long,
        clock: NanoClock = NanoClock.SYSTEM,
    ): Boolean {
        run(minOf(line, lineCount - 1), budgetNanos, clock)
        return isAccurate(line)
    }

    /**
     * Continues the accurate pass for one idle slice of [budgetNanos], no
     * further than [limitLine] (the caller's "about 100k chars past the
     * viewport" bound). Returns true when nothing is left to do up to it.
     */
    public fun continueInIdle(
        budgetNanos: Long,
        clock: NanoClock = NanoClock.SYSTEM,
        limitLine: Int = lineCount - 1,
    ): Boolean {
        run(minOf(limitLine, lineCount - 1), budgetNanos, clock)
        return isAccurate(limitLine)
    }

    /** The best tokens for [line] now: accurate if reached, otherwise [tokenizeHeuristically]. */
    public fun resultAt(line: Int): LineResult {
        val cached = results[line]
        return if (cached != null && isAccurate(line)) cached else tokenizeHeuristically(line)
    }

    /**
     * Tokens for a line the accurate pass has not reached. Its previous
     * tokens are reused while its text is unchanged; otherwise it is
     * tokenised from the nearest earlier line with a known state (at most
     * [HEURISTIC_LOOKBACK] lines back). Nothing is stored: the accurate
     * pass replaces the guess when it arrives.
     */
    public fun tokenizeHeuristically(line: Int): LineResult {
        results[line]?.let { return it }
        var known = line - 1
        while (known >= 0 && line - known <= HEURISTIC_LOOKBACK && results[known] == null) known--
        var state =
            when {
                known < 0 -> LineState.DOCUMENT_START
                else -> results[known]?.endState ?: LineState.NORMAL
            }
        var result: LineResult? = null
        for (i in known + 1..line) {
            result = guesser.tokenize(source.lineText(i), state)
            state = result.endState
        }
        return checkNotNull(result)
    }

    private fun run(
        limit: Int,
        budgetNanos: Long,
        clock: NanoClock,
    ) {
        check(source.lineCount == lineCount) {
            "Source has ${source.lineCount} lines but the cache has $lineCount: missing invalidate()"
        }
        val deadline = clock.nanoTime() + budgetNanos
        while (!marks.isEmpty && marks.first <= limit) {
            step(marks.first)
            if (clock.nanoTime() - deadline >= 0) break
        }
    }

    /** Tokenises the first pending line, then either converges or moves the mark to the next line. */
    private fun step(line: Int) {
        val start = if (line == 0) LineState.DOCUMENT_START else checkNotNull(results[line - 1]).endState
        val role = LineRole.entries[roles[line].toInt()]
        val result = store(line, start, role)
        fixLineAbove(line, result)
        val next = line + 1
        val nextResult = if (next < lineCount) results[next] else null
        when {
            next >= lineCount -> {
                marks.removeFirst()
            }

            nextResult != null && starts[next] == result.endState -> {
                // Converged: everything up to the next mark was computed from this same state.
                marks.removeFirst()
                if (nextResult.previousLineRole != role) {
                    roles[line] = nextResult.previousLineRole.ordinal.toByte()
                    store(line, start, nextResult.previousLineRole)
                }
            }

            else -> {
                marks.moveFirstTo(next)
            }
        }
    }

    /** A setext underline or delimiter row reads the line above differently: re-read that one line. */
    private fun fixLineAbove(
        line: Int,
        result: LineResult,
    ) {
        if (line == 0 || roles[line - 1].toInt() == result.previousLineRole.ordinal) return
        roles[line - 1] = result.previousLineRole.ordinal.toByte()
        store(line - 1, checkNotNull(starts[line - 1]), result.previousLineRole)
    }

    private fun store(
        line: Int,
        start: LineState,
        role: LineRole,
    ): LineResult {
        val text = source.lineText(line)
        val result = tokenizer.tokenize(text, start, role)
        linesTokenized++
        results[line] = result
        starts[line] = start
        roles[line] = role.ordinal.toByte()
        val heading = result.kind == LineKind.HEADING
        outline.set(line, result.headingLevel, if (heading) Outline.headingText(text, result.tokens) else null)
        references.set(line, result.definedLabel)
        return result
    }

    private fun replaceRange(
        from: Int,
        removed: Int,
        inserted: Int,
        newCount: Int,
    ) {
        if (newCount > results.size) {
            val capacity = maxOf(newCount, results.size + results.size / 2)
            results = results.copyOf(capacity)
            starts = starts.copyOf(capacity)
            roles = roles.copyOf(capacity)
        }
        val tail = lineCount - from - removed
        // A change within lines (the common keystroke) moves nothing; skip copying a million-entry tail onto itself.
        if (removed != inserted) shiftTail(from, removed, inserted, tail)
        results.fill(null, from, from + inserted)
        starts.fill(null, from, from + inserted)
        roles.fill(AS_IS, from, from + inserted)
        if (newCount < lineCount) {
            results.fill(null, newCount, lineCount)
            starts.fill(null, newCount, lineCount)
        }
    }

    private fun shiftTail(
        from: Int,
        removed: Int,
        inserted: Int,
        tail: Int,
    ) {
        System.arraycopy(results, from + removed, results, from + inserted, tail)
        System.arraycopy(starts, from + removed, starts, from + inserted, tail)
        System.arraycopy(roles, from + removed, roles, from + inserted, tail)
    }

    /** Tuning shared with the editor. */
    public companion object {
        /** How far back [tokenizeHeuristically] looks for a known state before guessing a plain one. */
        public const val HEURISTIC_LOOKBACK: Int = 50

        private const val AS_IS: Byte = 0
    }
}

/**
 * The sorted lines where the accurate pass must resume. Usually one or
 * two (an edit or two ahead of the pass), so a small sorted array beats a
 * tree, and shifting it on an edit is trivial.
 */
private class SortedMarks {
    private var lines = IntArray(INITIAL)
    private var size = 0

    val isEmpty: Boolean get() = size == 0
    val first: Int get() = lines[0]

    fun add(line: Int) {
        var i = 0
        while (i < size && lines[i] < line) i++
        if (i < size && lines[i] == line) return
        if (size == lines.size) lines = lines.copyOf(size * 2)
        System.arraycopy(lines, i, lines, i + 1, size - i)
        lines[i] = line
        size++
    }

    fun removeFirst() {
        System.arraycopy(lines, 1, lines, 0, --size)
    }

    /** The first mark advances to [line], merging with the next mark if they meet. */
    fun moveFirstTo(line: Int) {
        if (size > 1 && lines[1] <= line) removeFirst() else lines[0] = line
    }

    /** Marks inside replaced lines go (the edit adds its own); later ones shift. */
    fun replaceLines(
        from: Int,
        removed: Int,
        inserted: Int,
    ) {
        var write = 0
        for (read in 0 until size) {
            val line = lines[read]
            when {
                line < from -> lines[write++] = line
                line >= from + removed -> lines[write++] = line - removed + inserted
            }
        }
        size = write
    }

    private companion object {
        const val INITIAL = 4
    }
}
