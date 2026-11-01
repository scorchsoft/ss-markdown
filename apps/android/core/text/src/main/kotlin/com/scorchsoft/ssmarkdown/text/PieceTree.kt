/*
 * The piece tree design, and the lookups this facade delegates to, are
 * ported from Visual Studio Code's pieceTreeTextBuffer (Copyright (c)
 * Microsoft Corporation, MIT License; see core/text/NOTICE).
 */
package com.scorchsoft.ssmarkdown.text

/**
 * The document text: a red-black tree of pieces over immutable chunks,
 * after VS Code's piece tree (docs/robustness.md, "Text model").
 *
 * Every edit and every lookup — offset to line, line to offset, a line's
 * content — is one O(log pieces) descent, whatever the file size. Opening
 * a file never copies its text: the decoded chunks *are* the original
 * buffers. Typing appends to an add buffer in place, so a burst of
 * keystrokes grows one piece instead of making one per key.
 *
 * Text is held with `'\n'` as its only line break. Anything inserted is
 * normalised first; the file's own breaks are remembered in
 * [LineEndings] and put back on save.
 *
 * Not thread-safe: one thread (the editor's) owns a tree. Other threads
 * read through a [snapshot]. It implements [CharSequence] so regexes and
 * grapheme iteration can run over it directly; [get] remembers the last
 * piece it read, so sequential reads are O(1) each.
 */
@Suppress("TooManyFunctions") // CharSequence, line access and editing: the API is wide by nature, each member small.
public class PieceTree internal constructor(
    chunks: List<TextBuffer>,
) : CharSequence {
    private val tree = RedBlackTree()
    private var additions = AddBuffers()
    private var surgery = PieceSurgery(tree, additions)
    private var totalLength = 0

    /** Bumped by every change; keys the read caches below. */
    private var version = 0
    private var cachedNode: TreeNode = tree.nil
    private var cachedNodeStart = 0
    private var cachedNodeVersion = -1
    private var cachedLine = -1
    private var cachedLineText = ""
    private var cachedLineVersion = -1

    override val length: Int get() = totalLength

    /** Lines separated by `'\n'`; an empty document has one empty line. */
    public var lineCount: Int = 1
        private set

    // After every field above: Kotlin runs initialisers in declaration order.
    init {
        rebuild(chunks)
    }

    /** Nodes in the tree. Grows with editing; [compact] brings it back down. */
    public val pieceCount: Int get() = tree.nodeCount

    /**
     * True once editing has fragmented the tree past VS Code's own warning
     * line, 2 × (chars / 64K) + 1,000 pieces. Call [compact] at idle time.
     */
    public val needsCompaction: Boolean
        get() = pieceCount > 2 * (totalLength / TextBuffer.CAPACITY) + COMPACTION_SLACK

    override fun get(index: Int): Char {
        if (index < 0 || index >= totalLength) throw IndexOutOfBoundsException("index $index, length $totalLength")
        if (!cacheCovers(index)) locate(index)
        return cachedNode.piece.charAt(index - cachedNodeStart)
    }

    /** A zero-copy [Snapshot] of `[startIndex, endIndex)`. */
    override fun subSequence(
        startIndex: Int,
        endIndex: Int,
    ): Snapshot = slice(startIndex, endIndex)

    /** The whole text. O(length): prefer [snapshot] for anything large. */
    override fun toString(): String = getText(0, totalLength)

    /** An immutable view of the whole text for another thread; O(pieces). */
    public fun snapshot(): Snapshot {
        val pieces = ArrayList<Piece>(tree.nodeCount)
        tree.forEachPiece { pieces += it }
        return Snapshot(pieces.toTypedArray())
    }

    /** An immutable, zero-copy view of `[start, end)`; what undo keeps for deleted text. */
    public fun slice(
        start: Int,
        end: Int,
    ): Snapshot {
        checkRange(start, end)
        if (start == end) return Snapshot.EMPTY
        val pieces = ArrayList<Piece>()
        forEachPieceIn(start, end) { piece, from, to -> pieces += piece.slice(from, to) }
        return Snapshot(pieces.toTypedArray())
    }

    /** The text of `[start, end)` as a String. */
    public fun getText(
        start: Int,
        end: Int,
    ): String {
        checkRange(start, end)
        val out = StringBuilder(end - start)
        forEachPieceIn(start, end) { piece, from, to ->
            out.appendRange(piece.buffer.chars, piece.start + from, piece.start + to)
        }
        return out.toString()
    }

    /** Offset of the first char of 0-based [line]. */
    public fun lineStartOffset(line: Int): Int {
        checkLine(line)
        return tree.lineStartOffset(line)
    }

    /** Offset just past the last char of [line], before its `'\n'`. */
    public fun lineEndOffset(line: Int): Int = if (line == lineCount - 1) totalLength else lineStartOffset(line + 1) - 1

    /** Length of [line] in chars, without its line break. */
    public fun lineLength(line: Int): Int = lineEndOffset(line) - lineStartOffset(line)

    /**
     * The text of [line] without its line break. The last line read is
     * cached, because a view asks for the same line many times per frame.
     */
    public fun lineContent(line: Int): String {
        if (cachedLineVersion != version || cachedLine != line) {
            cachedLineText = getText(lineStartOffset(line), lineEndOffset(line))
            cachedLine = line
            cachedLineVersion = version
        }
        return cachedLineText
    }

    /**
     * The offset of ([line], [column]). A column past the end of the line
     * clamps to its end, because a cursor's remembered column routinely
     * outlives a move onto a shorter line.
     */
    public fun offsetAt(
        line: Int,
        column: Int,
    ): Int {
        require(column >= 0) { "column $column is negative" }
        val start = lineStartOffset(line)
        return minOf(start + column, lineEndOffset(line))
    }

    /** The 0-based line and column of [offset], which may equal [length]. */
    public fun positionAt(offset: Int): TextPosition {
        checkOffset(offset)
        val line = tree.lineFeedsBefore(offset)
        return TextPosition(line, offset - tree.lineStartOffset(line))
    }

    /**
     * Inserts [text] at [offset]. CRLF and lone CR become `'\n'`. A
     * [Snapshot] (from this tree or another) is linked in without copying.
     */
    public fun insert(
        offset: Int,
        text: CharSequence,
    ) {
        checkOffset(offset)
        if (text.isEmpty()) return
        if (text is Snapshot) {
            surgery.insertPieces(offset, text.pieces.filter { it.length > 0 })
        } else {
            surgery.insertText(offset, LineBreaks.normalize(text))
        }
        afterEdit()
    }

    /** Deletes [count] chars starting at [offset]. */
    public fun delete(
        offset: Int,
        count: Int,
    ) {
        require(count >= 0) { "count $count is negative" }
        checkRange(offset, offset + count)
        if (count == 0) return
        surgery.delete(offset, count)
        afterEdit()
    }

    /**
     * Rewrites the text into fresh, full chunks and a balanced tree of
     * ~length / 64K pieces. O(length); run it at idle time when
     * [needsCompaction] says so. Snapshots and undo history taken before
     * stay valid: they keep the old buffers alive until they are dropped.
     */
    public fun compact() {
        val text = snapshot()
        val packer = ChunkPacker()
        text.forEachChunk { chars, start, count -> packer.putAll(chars, start, count) }
        rebuild(packer.finish())
    }

    /** Checks the tree's structural invariants; O(n), for tests. */
    internal fun checkInvariants() {
        tree.checkInvariants()
        check(tree.subtreeLength(tree.root) == totalLength) { "cached length is stale" }
    }

    private fun rebuild(chunks: List<TextBuffer>) {
        tree.clear()
        additions = AddBuffers()
        surgery = PieceSurgery(tree, additions)
        var last = tree.nil
        for (chunk in chunks) if (chunk.length > 0) last = tree.insertRight(last, Piece.of(chunk))
        afterEdit()
    }

    /** Totals live on the right spine: O(log n), as in VS Code's `computeBufferMetadata`. */
    private fun afterEdit() {
        version++
        var x = tree.root
        var size = 0
        var lineFeeds = 0
        while (x !== tree.nil) {
            size += x.sizeLeft + x.piece.length
            lineFeeds += x.lineFeedsLeft + x.piece.lineFeeds
            x = x.right
        }
        totalLength = size
        lineCount = lineFeeds + 1
    }

    /** Calls [action] with each piece overlapping `[start, end)` and the overlapping piece-relative range. */
    private inline fun forEachPieceIn(
        start: Int,
        end: Int,
        action: (piece: Piece, from: Int, to: Int) -> Unit,
    ) {
        if (start == end) return
        val position = checkNotNull(tree.nodeAt(start))
        var node = position.node
        var from = position.remainder
        var remaining = end - start
        while (remaining > 0) {
            val piece = node.piece
            val take = minOf(piece.length - from, remaining)
            if (take > 0) action(piece, from, from + take)
            remaining -= take
            from = 0
            node = tree.next(node)
        }
    }

    private fun cacheCovers(index: Int): Boolean =
        cachedNodeVersion == version &&
            index >= cachedNodeStart &&
            index < cachedNodeStart + cachedNode.piece.length

    private fun locate(index: Int) {
        val nextStart = cachedNodeStart + cachedNode.piece.length
        val successor = if (cachedNodeVersion == version && index == nextStart) tree.next(cachedNode) else tree.nil
        if (successor !== tree.nil && successor.piece.length > 0) {
            cachedNode = successor
            cachedNodeStart = nextStart
        } else {
            val position = tree.nodeContaining(index)
            cachedNode = position.node
            cachedNodeStart = position.nodeStart
        }
        cachedNodeVersion = version
    }

    private fun checkOffset(offset: Int) {
        if (offset < 0 || offset > totalLength) throw IndexOutOfBoundsException("offset $offset, length $totalLength")
    }

    private fun checkRange(
        start: Int,
        end: Int,
    ) {
        if (start < 0 || end > totalLength || start > end) {
            throw IndexOutOfBoundsException("range [$start, $end), length $totalLength")
        }
    }

    private fun checkLine(line: Int) {
        if (line < 0 || line >= lineCount) throw IndexOutOfBoundsException("line $line, line count $lineCount")
    }

    /** Ways to make a tree. */
    public companion object {
        private const val COMPACTION_SLACK = 1_000

        /** A tree holding [text], with its line breaks normalised to `'\n'`. */
        public fun of(text: CharSequence): PieceTree = TextBufferBuilder().apply { append(text) }.build().text

        /** A new, empty document. */
        public fun empty(): PieceTree = PieceTree(emptyList())
    }
}
