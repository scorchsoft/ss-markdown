package com.scorchsoft.ssmarkdown.markdown

/**
 * Per-line tokens for a whole document, kept current lazily and within a
 * time budget: the Code pane's syntax-highlighting cache
 * (docs/robustness.md, "Lazy, with a time budget").
 *
 * For each line it keeps only what the incremental pass needs: the state
 * the line was tokenised from, the state it ended in, the [LineRole] it was
 * read with and the role it asked of the line above. That is about ten
 * bytes a line, whatever the line holds. The tokens themselves live in a
 * small direct-mapped cache of [tokenCacheLines] lines (the viewport and a
 * margin), and a line that falls out is re-tokenised from its stored start
 * state when it is next drawn: one line, exact, a few microseconds
 * (CodeMirror keeps states and drops tokens the same way). Before this, a
 * 20 MB file held about 121 MB of tokens per tokenizer (performance review
 * PERF-9).
 *
 * Lines before the first *pending mark* are accurate. An edit
 * ([invalidate]) drops the edited lines' states and places a mark;
 * [tokenizeUntil] and [continueInIdle] work forward from the first mark and
 * **stop as soon as a line's new end state equals the start state the next
 * line was tokenised from** (VS Code's `textModelTokens`). Typing in a
 * paragraph therefore re-tokenises one line, while opening a fence on line
 * 0 of a 200k-line file re-tokenises the viewport within the budget and the
 * rest in idle slices. Lines not reached yet are answered by
 * [tokenizeHeuristically]: their last tokens if the text is unchanged,
 * otherwise a guess from the nearest known state, corrected when the
 * accurate pass arrives. Guesses are cached too, so a viewport far below
 * the accurate frontier costs one walk, not one walk per line per frame
 * (PERF-15).
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
 *
 * @param tokenCacheLines lines whose tokens are kept, rounded up to a power
 *   of two; 0 keeps none (the outline, which never draws).
 */
@Suppress("TooManyFunctions") // The cache's whole contract: edits, budgeted passes, lookups and stats.
public class IncrementalTokenizer(
    private val source: LineSource,
    private val tokenizer: LineTokenizer = LineTokenizer(),
    tokenCacheLines: Int = DEFAULT_TOKEN_CACHE_LINES,
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

    /**
     * Lines tokenised to answer [resultAt] or [tokenizeHeuristically]
     * rather than by the accurate pass: cache misses and guesses. A
     * statistic for the bounded-work tests of PERF-15.
     */
    public var linesTokenizedForDisplay: Long = 0
        private set

    private val states = LineStates(lineCount)
    private val marks = SortedMarks()
    private val guesser = LineTokenizer(tokenizer.highlightCode)
    private val cache = TokenCache(tokenCacheLines)

    /**
     * One shared instance per distinct state. A paragraph's first line ends
     * in a fresh "paragraph open" state, so without this a document holds
     * one state object per block: 25 MB of duplicates in a 20 MB file.
     */
    private val interned = HashMap<LineState, LineState>()

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

    /** Lines whose tokens are held right now; never more than the cache's capacity. */
    public val cachedLines: Int get() = cache.size

    /**
     * Per-line state slots moved to follow line breaks so far. A statistic
     * for the PERF-E regression test: a run of line breaks in one place
     * moves nothing after the first ([LineStates] is a gap buffer).
     */
    public val stateEntriesMoved: Long get() = states.movedEntries

    /**
     * Roughly what this cache keeps alive, in bytes: the per-line slots at
     * their current capacity, the cached tokens, the outline's headings and
     * the reference labels (each with its text), and the interned states.
     * For the PERF-9 regression test and the benchmark. It used to count
     * the slots and tokens only, half of what the benchmark measures on a
     * heading-rich file (performance review PERF-J).
     */
    public fun retainedBytesEstimate(): Long =
        states.capacity.toLong() * BYTES_PER_LINE +
            cache.retainedBytesEstimate() +
            outline.retainedBytesEstimate() +
            references.retainedBytesEstimate() +
            interned.size.toLong() * INTERNED_STATE_BYTES

    /**
     * Drops every cached token (the memory governor's "tokenizer cache"
     * step). States are kept, so nothing is re-tokenised beyond the lines
     * drawn next.
     */
    public fun trimCache() {
        cache.clear()
    }

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
        states.replace(fromLine, linesRemoved, linesInserted)
        cache.onEdit(fromLine, linesRemoved, linesInserted)
        outline.replaceLines(fromLine, linesRemoved, linesInserted)
        references.replaceLines(fromLine, linesRemoved, linesInserted)
        marks.replaceLines(fromLine, linesRemoved, linesInserted)
        lineCount = newCount
        outline.lineCount = newCount
        // The line above may have been read as a setext heading or table header because of a line that just changed.
        val lookBack = fromLine > 0 && states.role(fromLine - 1) != AS_IS
        if (lookBack) {
            states.setRole(fromLine - 1, AS_IS)
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
        if (!isAccurate(line)) return tokenizeHeuristically(line)
        val slot = cache.slotOf(line)
        if (slot >= 0 && !cache.isGuess(slot)) return cache.resultIn(slot)
        val result = retokenize(line, checkNotNull(states.start(line)))
        cache.put(line, result, guess = false)
        return result
    }

    /**
     * Tokens for a line the accurate pass has not reached. Its previous
     * tokens are reused while its text is unchanged; otherwise it is
     * tokenised from the nearest earlier line with a known state (at most
     * [HEURISTIC_LOOKBACK] lines back). Nothing is stored in the per-line
     * state: the accurate pass replaces the guess when it arrives. The
     * guess and the lines walked to reach it are cached, so the next line
     * down, or the same line next frame, costs nothing.
     */
    public fun tokenizeHeuristically(line: Int): LineResult {
        val slot = cache.slotOf(line)
        if (slot >= 0) return cache.resultIn(slot)
        states.start(line)?.let { start ->
            // The text is unchanged since this line was tokenised from [start]: the same tokens, re-derived.
            return retokenize(line, start).also { cache.put(line, it, guess = true) }
        }
        var known = line - 1
        while (known >= 0 && line - known <= HEURISTIC_LOOKBACK && knownEnd(known) == null) known--
        var state =
            when {
                known < 0 -> LineState.DOCUMENT_START
                else -> knownEnd(known) ?: LineState.NORMAL
            }
        var result: LineResult? = null
        for (i in known + 1..line) {
            result = guesser.tokenize(source.lineText(i), state)
            linesTokenizedForDisplay++
            cache.put(i, result, guess = true)
            state = result.endState
        }
        return checkNotNull(result)
    }

    /** The end state of [line] if anything (a stored pass or a cached guess) knows it. */
    private fun knownEnd(line: Int): LineState? {
        states.end(line)?.let { return it }
        val slot = cache.slotOf(line)
        return if (slot >= 0) cache.resultIn(slot).endState else null
    }

    private fun retokenize(
        line: Int,
        start: LineState,
    ): LineResult {
        linesTokenizedForDisplay++
        return guesser.tokenize(source.lineText(line), start, LineRole.entries[states.role(line).toInt()])
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
        val start = if (line == 0) LineState.DOCUMENT_START else checkNotNull(states.end(line - 1))
        val role = LineRole.entries[states.role(line).toInt()]
        val result = store(line, start, role)
        fixLineAbove(line, result)
        val next = line + 1
        when {
            next >= lineCount -> {
                marks.removeFirst()
            }

            states.start(next) != null && states.start(next) == result.endState -> {
                // Converged: everything up to the next mark was computed from this same state.
                marks.removeFirst()
                val asked = LineRole.entries[states.aboveRole(next).toInt()]
                if (asked != role) {
                    states.setRole(line, asked.ordinal.toByte())
                    store(line, start, asked)
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
        if (line == 0 || states.role(line - 1).toInt() == result.previousLineRole.ordinal) return
        states.setRole(line - 1, result.previousLineRole.ordinal.toByte())
        store(line - 1, checkNotNull(states.start(line - 1)), result.previousLineRole)
    }

    private fun store(
        line: Int,
        start: LineState,
        role: LineRole,
    ): LineResult {
        val text = source.lineText(line)
        val result = tokenizer.tokenize(text, start, role)
        linesTokenized++
        states.store(
            line,
            start,
            intern(result.endState),
            role.ordinal.toByte(),
            result.previousLineRole.ordinal.toByte(),
        )
        cache.put(line, result, guess = false)
        val heading = result.kind == LineKind.HEADING
        outline.set(line, result.headingLevel, if (heading) Outline.headingText(text, result.tokens) else null)
        references.set(line, result.definedLabel)
        return result
    }

    private fun intern(state: LineState): LineState {
        interned[state]?.let { return it }
        // Fence info strings are user text: past the cap, a hostile document just shares less.
        if (interned.size < MAX_INTERNED_STATES) interned[state] = state
        return state
    }

    /** Tuning shared with the editor. */
    public companion object {
        /** How far back [tokenizeHeuristically] looks for a known state before guessing a plain one. */
        public const val HEURISTIC_LOOKBACK: Int = 50

        /** Lines of tokens the editor keeps: several screens, so scrolling back re-tokenises nothing. */
        public const val DEFAULT_TOKEN_CACHE_LINES: Int = 1024

        /**
         * Per-line cost of the state arrays: two references (4 bytes each on
         * ART and a compressed-oops JVM) and two role bytes. The states
         * themselves are shared: most lines end in the state they started in.
         */
        public const val BYTES_PER_LINE: Int = 10

        private const val AS_IS: Byte = 0

        /** A map node plus a small [LineState]: the cost of one distinct interned state. */
        private const val INTERNED_STATE_BYTES = 96L
        private const val MAX_INTERNED_STATES = 4_096
    }
}

/**
 * The tokens of a few recently drawn lines, direct-mapped by line number:
 * a viewport is a run of consecutive lines, so it never collides with
 * itself, and a lookup is one array read. Entries are either accurate or
 * guesses; an edit drops the guesses (the states they came from may have
 * changed) and the entries of the lines it touched or shifted.
 */
private class TokenCache(
    lines: Int,
) {
    private val capacity = if (lines <= 0) 0 else Integer.highestOneBit(lines - 1).shl(1).coerceAtLeast(1)
    private val mask = capacity - 1
    private val keys = IntArray(capacity) { EMPTY }
    private val results = arrayOfNulls<LineResult>(capacity)
    private val guesses = BooleanArray(capacity)

    var size: Int = 0
        private set

    /** The slot holding [line], or -1. */
    fun slotOf(line: Int): Int {
        if (capacity == 0) return -1
        val slot = line and mask
        return if (keys[slot] == line) slot else -1
    }

    fun resultIn(slot: Int): LineResult = checkNotNull(results[slot])

    fun isGuess(slot: Int): Boolean = guesses[slot]

    fun put(
        line: Int,
        result: LineResult,
        guess: Boolean,
    ) {
        if (capacity == 0) return
        val slot = line and mask
        if (keys[slot] == EMPTY) size++
        keys[slot] = line
        results[slot] = result
        guesses[slot] = guess
    }

    /**
     * Lines `[from, from + removed)` became [inserted] lines. Entries for
     * those lines go, and so does every entry below them if the line count
     * changed (their keys moved); entries above stay unless they were
     * guesses. O(capacity), a thousand array reads per keystroke.
     */
    fun onEdit(
        from: Int,
        removed: Int,
        inserted: Int,
    ) {
        val shifted = removed != inserted
        val touchedEnd = from + maxOf(removed, inserted)
        for (slot in 0 until capacity) {
            val line = keys[slot]
            if (line == EMPTY) continue
            val touched = line >= from && (shifted || line < touchedEnd)
            if (touched || guesses[slot]) drop(slot)
        }
    }

    fun clear() {
        for (slot in 0 until capacity) if (keys[slot] != EMPTY) drop(slot)
    }

    fun retainedBytesEstimate(): Long {
        var bytes = capacity.toLong() * SLOT_BYTES
        for (slot in 0 until capacity) {
            val result = results[slot] ?: continue
            bytes += RESULT_BYTES + result.tokens.size.toLong() * Long.SIZE_BYTES
        }
        return bytes
    }

    private fun drop(slot: Int) {
        keys[slot] = EMPTY
        results[slot] = null
        guesses[slot] = false
        size--
    }

    private companion object {
        const val EMPTY = -1

        /** A key, a reference and a flag. */
        const val SLOT_BYTES = 9

        /** A [LineResult] and its token array's header. */
        const val RESULT_BYTES = 56
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
