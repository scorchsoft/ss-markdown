package com.scorchsoft.ssmarkdown.markdown

/**
 * The delimiter runs of one line (`*`, `_`, `~`) and CommonMark's "process
 * emphasis" algorithm over them (spec §6.2, appendix A): each closer looks
 * back for the nearest compatible opener, `**` pairs before `*`, the rule
 * of three applies, and an openers-bottom bound per character keeps a
 * hostile line such as `*a *a *a ...` close to linear.
 *
 * Runs are kept as parallel arrays with a live `[low, high)` window: an
 * opener gives up delimiters from its right end, a closer from its left,
 * which is exactly where the markers sit in the text.
 */
@Suppress("TooManyFunctions") // One algorithm from the spec, split into its named steps.
internal class EmphasisRuns(
    private val out: TokenBuilder,
) {
    private var runStart = IntArray(INITIAL)
    private var runLow = IntArray(INITIAL)
    private var runHigh = IntArray(INITIAL)
    private var runLength = IntArray(INITIAL)
    private var runChar = CharArray(INITIAL)
    private var runFlags = IntArray(INITIAL)
    private var runCount = 0
    private var base = TokenKind.PLAIN
    private var linkTexts = IntArray(0)
    private var linkCount = 0

    fun reset(baseKind: TokenKind) {
        runCount = 0
        base = baseKind
    }

    /** Records a run of [length] [c] at [at] between [before] and [next], if it can open or close anything. */
    fun add(
        at: Int,
        length: Int,
        c: Char,
        before: Char,
        next: Char,
    ) {
        val flags = delimiterFlags(c, before, next)
        if (flags == 0) return
        if (runCount == runStart.size) grow()
        runStart[runCount] = at
        runLow[runCount] = at
        runHigh[runCount] = at + length
        runLength[runCount] = length
        runChar[runCount] = c
        runFlags[runCount] = flags
        runCount++
    }

    private fun grow() {
        val size = runCount * 2
        runStart = runStart.copyOf(size)
        runLow = runLow.copyOf(size)
        runHigh = runHigh.copyOf(size)
        runLength = runLength.copyOf(size)
        runChar = runChar.copyOf(size)
        runFlags = runFlags.copyOf(size)
    }

    /** CAN_OPEN / CAN_CLOSE for a run of [c] between [before] and [next]; `_` may not open or close inside a word. */
    private fun delimiterFlags(
        c: Char,
        before: Char,
        next: Char,
    ): Int {
        val left = flanks(next, before)
        val right = flanks(before, next)
        val canOpen = left && (c != '_' || !right || InlineSyntax.isPunctuation(before))
        val canClose = right && (c != '_' || !left || InlineSyntax.isPunctuation(next))
        return (if (canOpen) CAN_OPEN else 0) or (if (canClose) CAN_CLOSE else 0)
    }

    /** CommonMark's flanking test, seen from the side of [inner] (the char the run faces). */
    private fun flanks(
        inner: Char,
        outer: Char,
    ): Boolean =
        !inner.isWhitespace() &&
            (!InlineSyntax.isPunctuation(inner) || outer.isWhitespace() || InlineSyntax.isPunctuation(outer))

    /** Delimiter runs inside a URL, title or label are text, not emphasis. */
    fun kill(
        from: Int,
        to: Int,
    ) {
        var k = firstRunAtOrAfter(from)
        while (k < runCount && runStart[k] < to) {
            runHigh[k] = runLow[k]
            k++
        }
    }

    private fun firstRunAtOrAfter(column: Int): Int {
        var low = 0
        var high = runCount
        while (low < high) {
            val mid = (low + high) ushr 1
            if (runStart[mid] < column) low = mid + 1 else high = mid
        }
        return low
    }

    private val openersBottom = IntArray(DELIMITER_CHARS.length)

    /** Matches openers with closers and paints the pairs; [linkTexts] holds [linkCount] (start, end) pairs. */
    fun resolve(
        linkTexts: IntArray,
        linkCount: Int,
    ) {
        this.linkTexts = linkTexts
        this.linkCount = linkCount
        openersBottom.fill(-1)
        var closer = 0
        while (closer < runCount) {
            // A closer stays put while it still has delimiters left to match (`***a**b*`).
            if (!canClose(closer) || !closeOnce(closer)) closer++
        }
    }

    private fun canOpen(run: Int): Boolean = runFlags[run] and CAN_OPEN != 0 && runHigh[run] > runLow[run]

    private fun canClose(run: Int): Boolean = runFlags[run] and CAN_CLOSE != 0 && runHigh[run] > runLow[run]

    /** Matches [closer] with its opener once; true if it has delimiters left to try again. */
    private fun closeOnce(closer: Int): Boolean {
        val charIndex = DELIMITER_CHARS.indexOf(runChar[closer])
        val opener = findOpener(closer, charIndex)
        if (opener < 0) {
            openersBottom[charIndex] = closer - 1
            return false
        }
        match(opener, closer)
        return runHigh[closer] > runLow[closer]
    }

    private fun findOpener(
        closer: Int,
        charIndex: Int,
    ): Int {
        val c = runChar[closer]
        var o = closer - 1
        while (o > openersBottom[charIndex]) {
            if (runChar[o] == c && canOpen(o) && pairs(o, closer)) return o
            o--
        }
        return -1
    }

    private fun pairs(
        opener: Int,
        closer: Int,
    ): Boolean {
        if (runChar[closer] == '~') return runHigh[opener] - runLow[opener] == runHigh[closer] - runLow[closer]
        // CommonMark's rule of three, for runs that could both open and close.
        val bothWays = runFlags[opener] and CAN_CLOSE != 0 || runFlags[closer] and CAN_OPEN != 0
        val sumOfThree = (runLength[opener] + runLength[closer]) % RULE_OF_THREE == 0
        val bothOfThree = runLength[opener] % RULE_OF_THREE == 0 && runLength[closer] % RULE_OF_THREE == 0
        return !bothWays || !sumOfThree || bothOfThree
    }

    private fun match(
        opener: Int,
        closer: Int,
    ) {
        val c = runChar[closer]
        val available = minOf(runHigh[opener] - runLow[opener], runHigh[closer] - runLow[closer])
        val use = if (c == '~') available else minOf(available, 2)
        val openMarker = runHigh[opener] - use
        val closeMarker = runLow[closer]
        val (marker, content, style) =
            when {
                c == '~' -> STRIKE
                use == 2 -> STRONG
                else -> EMPHASIS
            }
        out.paint(openMarker, openMarker + use, marker)
        out.paint(closeMarker, closeMarker + use, marker)
        out.paintOver(openMarker + use, closeMarker, content, base)
        // Emphasis nested inside a link's text is the innermost role there; around a link it is not.
        if (insideLinkText(
                openMarker,
                closeMarker + use,
            )
        ) {
            out.paintOver(openMarker + use, closeMarker, content, TokenKind.LINK_TEXT)
        }
        out.addStyle(openMarker + use, closeMarker, style)
        runHigh[opener] -= use
        runLow[closer] += use
        for (between in opener + 1 until closer) runHigh[between] = runLow[between]
    }

    private fun insideLinkText(
        from: Int,
        to: Int,
    ): Boolean {
        for (k in 0 until linkCount) if (linkTexts[k * 2] <= from && to <= linkTexts[k * 2 + 1]) return true
        return false
    }

    private companion object {
        const val INITIAL = 16
        const val CAN_OPEN = 1
        const val CAN_CLOSE = 2
        const val RULE_OF_THREE = 3
        const val DELIMITER_CHARS = "*_~"
        val STRIKE = Triple(TokenKind.STRIKETHROUGH_MARKER, TokenKind.STRIKETHROUGH_TEXT, InlineStyle.STRIKETHROUGH)
        val STRONG = Triple(TokenKind.STRONG_MARKER, TokenKind.STRONG_TEXT, InlineStyle.STRONG)
        val EMPHASIS = Triple(TokenKind.EMPHASIS_MARKER, TokenKind.EMPHASIS_TEXT, InlineStyle.EMPHASIS)
    }
}
