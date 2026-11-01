package com.scorchsoft.ssmarkdown.editor

/**
 * The vertical position of every line, CodeMirror-style: lines that have
 * been laid out carry their **measured** row count, every other line an
 * **estimate** ([estimatedRows]), refined as the user scrolls. In No-wrap
 * mode nothing is ever measured, every line is one row, and every question
 * is arithmetic. Pure Kotlin, so it is tested on the host.
 *
 * ### Storage
 * Lines are grouped into blocks of about [BLOCK] lines. A block that has
 * never had a line measured holds only its line count, so a 2M-line file
 * costs a few thousand small objects, not an int per line. A block
 * allocates its per-line rows array the first time one of its lines is
 * measured. Queries walk the block list (thousands of additions, a few
 * microseconds), then the one block.
 *
 * ### Scroll anchoring
 * [setRows] returns how far the line's bottom moved. The view adds that
 * to its scroll position when the line is above its anchor (`newTop −
 * oldTop`), so measuring lines off-screen never moves what is on screen.
 *
 * All heights are px; [rowHeight] is one text row.
 */
@Suppress("TooManyFunctions") // Queries, edits and anchoring of one structure; each is a few lines.
class HeightMap(
    lineCount: Int,
    rowHeight: Int,
) {
    private class Block(
        var count: Int,
    ) {
        /** Rows per line, 0 for "not measured"; null until a line in the block is measured. */
        var rows: IntArray? = null
        var measuredCount: Int = 0
        var measuredRows: Long = 0

        fun rowsTotal(estimate: Int): Long = measuredRows + (count - measuredCount).toLong() * estimate
    }

    private val blocks = ArrayList<Block>()
    private var measuredLines = 0
    private var measuredRowsTotal = 0L

    /** Height of one row, in px. Changing it keeps every row count. */
    var rowHeight: Int = rowHeight
        set(value) {
            require(value > 0) { "rowHeight must be positive, was $value" }
            field = value
        }

    /** Rows assumed for a line not measured yet. At least 1. */
    var estimatedRows: Int = 1
        set(value) {
            field = value.coerceAtLeast(1)
        }

    /** Number of lines. */
    var lineCount: Int = 0
        private set

    init {
        require(rowHeight > 0) { "rowHeight must be positive, was $rowHeight" }
        reset(lineCount)
    }

    /** Forgets every measurement, e.g. after a width, font or mode change. */
    fun reset(newLineCount: Int) {
        require(newLineCount >= 1) { "a document has at least one line, was $newLineCount" }
        blocks.clear()
        measuredLines = 0
        measuredRowsTotal = 0
        var left = newLineCount
        while (left > 0) {
            val n = minOf(BLOCK, left)
            blocks += Block(n)
            left -= n
        }
        lineCount = newLineCount
    }

    /** Total height of the document. */
    val contentHeight: Long
        get() =
            if (measuredLines == 0) {
                lineCount.toLong() * estimatedRows * rowHeight
            } else {
                blocks.sumOf { it.rowsTotal(estimatedRows) } * rowHeight
            }

    /** Lines with a measured row count. */
    val measuredLineCount: Int get() = measuredLines

    /** Mean rows of the measured lines, rounded; [estimatedRows] when none are measured. */
    val averageMeasuredRows: Int
        get() =
            if (measuredLines ==
                0
            ) {
                estimatedRows
            } else {
                ((measuredRowsTotal + measuredLines / 2) / measuredLines).toInt()
            }

    /** True when [line] has a measured row count. */
    fun isMeasured(line: Int): Boolean = rowsOrZero(line) != 0

    /** Rows of [line]: measured, or [estimatedRows]. */
    fun rowsOf(line: Int): Int = rowsOrZero(line).takeIf { it != 0 } ?: estimatedRows

    /** Height of [line] in px. */
    fun heightOf(line: Int): Int = rowsOf(line) * rowHeight

    /** Top of [line] in content px; [lineCount] gives the bottom of the document. */
    fun lineTop(line: Int): Long {
        require(line in 0..lineCount) { "line $line outside 0..$lineCount" }
        return when {
            measuredLines == 0 -> {
                line.toLong() * estimatedRows * rowHeight
            }

            line == lineCount -> {
                contentHeight
            }

            else -> {
                val at = walk { first, _, block -> line < first + block.count }
                (at.rowsBefore + rowsBefore(at.block, line - at.first)) * rowHeight
            }
        }
    }

    /** The line under content-space [y], clamped to the document. */
    fun lineAt(y: Long): Int {
        val targetRow = y.coerceAtLeast(0) / rowHeight
        return when {
            measuredLines == 0 -> {
                (targetRow / estimatedRows).coerceAtMost(lineCount - 1L).toInt()
            }

            targetRow * rowHeight >= contentHeight -> {
                lineCount - 1
            }

            else -> {
                val at = walk { _, rows, block -> targetRow < rows + block.rowsTotal(estimatedRows) }
                at.first + lineInBlock(at.block, targetRow - at.rowsBefore)
            }
        }
    }

    /** Where a block walk stopped: the [block], its [first] line and the rows above it. */
    private class BlockAt(
        val block: Block,
        val first: Int,
        val rowsBefore: Long,
    )

    /** Walks the blocks until [stop] (given first line and rows above) holds; callers guarantee one does. */
    private inline fun walk(stop: (first: Int, rowsBefore: Long, block: Block) -> Boolean): BlockAt {
        var rows = 0L
        var first = 0
        var index = 0
        while (index < blocks.lastIndex && !stop(first, rows, blocks[index])) {
            rows += blocks[index].rowsTotal(estimatedRows)
            first += blocks[index].count
            index++
        }
        return BlockAt(blocks[index], first, rows)
    }

    /**
     * Records [rows] measured rows for [line] and returns how many px the
     * line's bottom (and every line below it) moved.
     */
    fun setRows(
        line: Int,
        rows: Int,
    ): Int {
        require(rows >= 1) { "a line has at least one row, was $rows" }
        val (block, index) = locate(line)
        val array = block.rows ?: IntArray(block.count).also { block.rows = it }
        val old = array[index]
        if (old == rows) return 0
        if (old == 0) {
            block.measuredCount++
            measuredLines++
        }
        block.measuredRows += rows - old
        measuredRowsTotal += rows - old
        array[index] = rows
        return (rows - (if (old == 0) estimatedRows else old)) * rowHeight
    }

    /**
     * Follows an edit: lines `[from, from + removed)` became [inserted] new
     * lines, which start unmeasured. Lines after them keep their rows.
     */
    fun replaceLines(
        from: Int,
        removed: Int,
        inserted: Int,
    ) {
        require(from in 0..lineCount && removed >= 0 && inserted >= 0 && from + removed <= lineCount) {
            "edit ($from, -$removed, +$inserted) is outside $lineCount lines"
        }
        if (removed > 0) remove(from, removed)
        if (inserted > 0) insert(from, inserted)
        if (lineCount == 0) reset(1)
    }

    private fun rowsOrZero(line: Int): Int {
        if (measuredLines == 0) return 0
        val (block, index) = locate(line)
        return block.rows?.get(index) ?: 0
    }

    private fun rowsBefore(
        block: Block,
        index: Int,
    ): Long {
        val rows = block.rows ?: return index.toLong() * estimatedRows
        var sum = 0L
        for (i in 0 until index) sum += rows[i].takeIf { it != 0 } ?: estimatedRows
        return sum
    }

    private fun lineInBlock(
        block: Block,
        targetRow: Long,
    ): Int {
        val rows = block.rows ?: return (targetRow / estimatedRows).toInt().coerceAtMost(block.count - 1)
        var sum = 0L
        var i = 0
        while (i < block.count - 1) {
            sum += rows[i].takeIf { it != 0 } ?: estimatedRows
            if (targetRow < sum) break
            i++
        }
        return i
    }

    private fun locate(line: Int): Pair<Block, Int> {
        require(line in 0 until lineCount) { "line $line outside 0 until $lineCount" }
        val at = walk { first, _, block -> line < first + block.count }
        return at.block to line - at.first
    }

    private fun remove(
        from: Int,
        count: Int,
    ) {
        var first = 0
        var left = count
        var i = 0
        while (i < blocks.size && left > 0) {
            val block = blocks[i]
            val start = from - first
            if (start < block.count) {
                val local = maxOf(start, 0)
                val n = minOf(left, block.count - local)
                cut(block, local, n)
                left -= n
            }
            first += block.count
            if (block.count == 0) blocks.removeAt(i) else i++
        }
        lineCount -= count
    }

    private fun cut(
        block: Block,
        at: Int,
        n: Int,
    ) {
        val rows = block.rows
        if (rows != null) {
            for (k in at until at + n) {
                if (rows[k] != 0) {
                    block.measuredCount--
                    block.measuredRows -= rows[k]
                    measuredRowsTotal -= rows[k]
                    measuredLines--
                }
            }
            val kept = IntArray(block.count - n)
            System.arraycopy(rows, 0, kept, 0, at)
            System.arraycopy(rows, at + n, kept, at, block.count - at - n)
            block.rows = kept
        }
        block.count -= n
    }

    private fun insert(
        at: Int,
        n: Int,
    ) {
        if (blocks.isEmpty()) blocks += Block(0)
        var first = 0
        var index = blocks.lastIndex
        for (i in blocks.indices) {
            if (at <= first + blocks[i].count) {
                index = i
                break
            }
            first += blocks[i].count
        }
        val block = blocks[index]
        val local = at - first
        block.rows?.let { rows ->
            val grown = IntArray(block.count + n)
            System.arraycopy(rows, 0, grown, 0, local)
            System.arraycopy(rows, local, grown, local + n, block.count - local)
            block.rows = grown
        }
        block.count += n
        lineCount += n
        if (block.count > 2 * BLOCK) split(index)
    }

    /** Splits an oversized block (after a big paste) into blocks of [BLOCK] lines. */
    private fun split(index: Int) {
        val big = blocks.removeAt(index)
        val pieces = ArrayList<Block>()
        var start = 0
        while (start < big.count) {
            val n = minOf(BLOCK, big.count - start)
            val piece = Block(n)
            big.rows?.let { rows ->
                val part = rows.copyOfRange(start, start + n)
                piece.measuredCount = part.count { it != 0 }
                if (piece.measuredCount > 0) {
                    piece.rows = part
                    piece.measuredRows = part.sumOf { it.toLong() }
                }
            }
            pieces += piece
            start += n
        }
        blocks.addAll(index, pieces)
    }

    private companion object {
        /** Lines per block: a few KB of rows once measured, and a short walk for queries. */
        const val BLOCK = 512
    }
}
