package com.scorchsoft.ssmarkdown.markdown

/**
 * A mutable document for tests: a list of lines that applies offset-based
 * edits and reports them as the `(fromLine, removed, inserted)` triple the
 * [IncrementalTokenizer] wants, exactly as the editor will.
 */
class TestDoc(
    text: String,
) : LineSource {
    val lines: MutableList<String> = text.split('\n').toMutableList()

    override val lineCount: Int get() = lines.size

    override fun lineText(line: Int): CharSequence = lines[line]

    val text: String get() = lines.joinToString("\n")

    /** Replaces chars `[start, end)` of the whole text with [replacement]; returns the invalidation triple. */
    fun replace(
        start: Int,
        end: Int,
        replacement: String,
    ): Triple<Int, Int, Int> {
        val (fromLine, fromCol) = position(start)
        val (toLine, toCol) = position(end)
        val merged = lines[fromLine].substring(0, fromCol) + replacement + lines[toLine].substring(toCol)
        val newLines = merged.split('\n')
        repeat(toLine - fromLine + 1) { lines.removeAt(fromLine) }
        lines.addAll(fromLine, newLines)
        return Triple(fromLine, toLine - fromLine + 1, newLines.size)
    }

    /** Applies an edit to both this document and [cache]. */
    fun edit(
        cache: IncrementalTokenizer,
        start: Int,
        end: Int,
        replacement: String,
    ) {
        val (from, removed, inserted) = replace(start, end, replacement)
        cache.invalidate(from, removed, inserted)
    }

    fun offsetOf(line: Int): Int = (0 until line).sumOf { lines[it].length + 1 }

    private fun position(offset: Int): Pair<Int, Int> {
        var remaining = offset
        for (i in lines.indices) {
            if (remaining <= lines[i].length) return i to remaining
            remaining -= lines[i].length + 1
        }
        error("offset $offset past end")
    }
}

/** A clock that advances by [step] ns every time it is read: budgets in tests become line counts. */
class SteppingClock(
    private val step: Long,
) : NanoClock {
    private var now = 0L

    override fun nanoTime(): Long {
        now += step
        return now
    }
}

/** The non-plain tokens of [line], as `KIND:text`, e.g. `HEADING_MARKER:#`. */
fun spans(
    line: String,
    start: LineState = LineState.NORMAL,
    role: LineRole = LineRole.AS_IS,
): List<String> = spans(line, LineTokenizer().tokenize(line, start, role))

fun spans(
    line: String,
    result: LineResult,
): List<String> =
    result.tokens
        .filter { Tokens.kind(it) != TokenKind.PLAIN }
        .map { "${Tokens.kind(it)}:${line.substring(Tokens.start(it), Tokens.end(it))}" }

/** Tokenises a whole document from scratch with no cache: the reference the incremental cache must equal. */
fun tokenizeFromScratch(lines: List<String>): List<LineResult> {
    val tokenizer = LineTokenizer()
    val results = ArrayList<LineResult>(lines.size)
    var state = LineState.DOCUMENT_START
    for ((i, line) in lines.withIndex()) {
        val result = tokenizer.tokenize(line, state)
        if (i > 0 && result.previousLineRole != LineRole.AS_IS) {
            val previousStart = if (i == 1) LineState.DOCUMENT_START else results[i - 2].endState
            results[i - 1] = tokenizer.tokenize(lines[i - 1], previousStart, result.previousLineRole)
        }
        results.add(result)
        state = result.endState
    }
    return results
}

/** Fully tokenises [doc] and returns the cache. */
fun fullyTokenized(doc: LineSource): IncrementalTokenizer =
    IncrementalTokenizer(doc).also { it.continueInIdle(Long.MAX_VALUE / 4) }

fun kinds(text: String): List<LineKind> {
    val cache = fullyTokenized(TestDoc(text))
    return (0 until cache.lineCount).map { cache.resultAt(it).kind }
}
