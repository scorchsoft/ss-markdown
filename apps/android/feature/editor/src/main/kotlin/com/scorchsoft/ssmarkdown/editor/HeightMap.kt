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
 * measured. Three Fenwick trees over the blocks (lines, unmeasured lines,
 * measured rows) answer "which block, and how far down is it" in
 * O(log blocks) without allocating; an edit inside a block is a point
 * update, and only a change to the block list rebuilds them, O(blocks).
 * Before, every query walked the block list from the top and returned a
 * new object: 0.35 ms of height queries a frame at 1.46M lines (host),
 * growing with the file (performance review PERF-18).
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

    // Fenwick trees over the blocks, 1-based; valid only while [treesValid].
    private var lineTree = LongArray(1)
    private var unmeasuredTree = LongArray(1)
    private var rowTree = LongArray(1)
    private var treesValid = false

    // Where the last block search stopped: fields, not a returned object, so queries allocate nothing.
    private var foundIndex = 0
    private var foundFirst = 0
    private var foundRowsBefore = 0L

    /** Fenwick steps taken by queries so far; the bounded-work tests read it. */
    internal var searchSteps: Long = 0
        private set

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
        treesValid = false
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
        get() = (measuredRowsTotal + (lineCount - measuredLines).toLong() * estimatedRows) * rowHeight

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
                findByLine(line)
                (foundRowsBefore + rowsBefore(blocks[foundIndex], line - foundFirst)) * rowHeight
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
                findByRow(targetRow)
                foundFirst + lineInBlock(blocks[foundIndex], targetRow - foundRowsBefore)
            }
        }
    }

    /** Sets the found* fields to the block holding [line]. */
    private fun findByLine(line: Int) {
        ensureTrees()
        val n = blocks.size
        var pos = 0
        var lines = 0L
        var unmeasured = 0L
        var rows = 0L
        var step = Integer.highestOneBit(n)
        while (step > 0) {
            searchSteps++
            val next = pos + step
            if (next <= n && lines + lineTree[next] <= line) {
                pos = next
                lines += lineTree[next]
                unmeasured += unmeasuredTree[next]
                rows += rowTree[next]
            }
            step = step shr 1
        }
        found(pos, lines, rows + unmeasured * estimatedRows)
    }

    /** Sets the found* fields to the block holding row [targetRow]. */
    private fun findByRow(targetRow: Long) {
        ensureTrees()
        val n = blocks.size
        var pos = 0
        var lines = 0L
        var unmeasured = 0L
        var rows = 0L
        var step = Integer.highestOneBit(n)
        while (step > 0) {
            searchSteps++
            val next = pos + step
            if (next <= n &&
                rows + rowTree[next] + (unmeasured + unmeasuredTree[next]) * estimatedRows <= targetRow
            ) {
                pos = next
                lines += lineTree[next]
                unmeasured += unmeasuredTree[next]
                rows += rowTree[next]
            }
            step = step shr 1
        }
        found(pos, lines, rows + unmeasured * estimatedRows)
    }

    private fun found(
        blocksBefore: Int,
        linesBefore: Long,
        rowsBefore: Long,
    ) {
        if (blocksBefore >= blocks.size) {
            // Past the end (clamped queries): the last block, measured from its own top.
            val last = blocks.lastIndex
            val block = blocks[last]
            foundIndex = last
            foundFirst = (linesBefore - block.count).toInt()
            foundRowsBefore = rowsBefore - block.rowsTotal(estimatedRows)
        } else {
            foundIndex = blocksBefore
            foundFirst = linesBefore.toInt()
            foundRowsBefore = rowsBefore
        }
    }

    private fun ensureTrees() {
        if (treesValid) return
        val n = blocks.size
        if (lineTree.size != n + 1) {
            lineTree = LongArray(n + 1)
            unmeasuredTree = LongArray(n + 1)
            rowTree = LongArray(n + 1)
        }
        for (i in 1..n) {
            val block = blocks[i - 1]
            lineTree[i] = block.count.toLong()
            unmeasuredTree[i] = (block.count - block.measuredCount).toLong()
            rowTree[i] = block.measuredRows
        }
        for (i in 1..n) {
            val parent = i + (i and -i)
            if (parent <= n) {
                lineTree[parent] += lineTree[i]
                unmeasuredTree[parent] += unmeasuredTree[i]
                rowTree[parent] += rowTree[i]
            }
        }
        treesValid = true
    }

    /** A point update for block [index]; a no-op while the trees await a rebuild. */
    private fun adjust(
        index: Int,
        lines: Int,
        unmeasured: Int,
        rows: Long,
    ) {
        if (!treesValid) return
        var i = index + 1
        while (i < lineTree.size) {
            lineTree[i] += lines
            unmeasuredTree[i] += unmeasured
            rowTree[i] += rows
            i += i and -i
        }
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
        locate(line)
        val block = blocks[foundIndex]
        val index = line - foundFirst
        val array = block.rows ?: IntArray(block.count).also { block.rows = it }
        val old = array[index]
        if (old == rows) return 0
        if (old == 0) {
            block.measuredCount++
            measuredLines++
        }
        block.measuredRows += rows - old
        measuredRowsTotal += rows - old
        adjust(foundIndex, 0, if (old == 0) -1 else 0, (rows - old).toLong())
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
        locate(line)
        return blocks[foundIndex].rows?.get(line - foundFirst) ?: 0
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

    /** Sets the found* fields to [line]'s block. */
    private fun locate(line: Int) {
        require(line in 0 until lineCount) { "line $line outside 0 until $lineCount" }
        findByLine(line)
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
                val unmeasuredBefore = block.count - block.measuredCount
                val rowsBefore = block.measuredRows
                cut(block, local, n)
                adjust(i, -n, block.count - block.measuredCount - unmeasuredBefore, block.measuredRows - rowsBefore)
                left -= n
            }
            first += block.count
            if (block.count == 0) {
                blocks.removeAt(i)
                treesValid = false
            } else {
                i++
            }
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
        if (blocks.isEmpty()) {
            blocks += Block(0)
            treesValid = false
        }
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
        adjust(index, n, n, 0)
        if (block.count > 2 * BLOCK) split(index)
    }

    /** Splits an oversized block (after a big paste) into blocks of [BLOCK] lines. */
    private fun split(index: Int) {
        treesValid = false
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
