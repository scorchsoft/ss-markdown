/*
 * Ported from Visual Studio Code, pieceTreeBase.ts (`insert`, `delete`,
 * `deleteNodeHead`, `deleteNodeTail`, `shrinkNode`, `appendToNode`).
 * Copyright (c) Microsoft Corporation, MIT License; see core/text/NOTICE.
 *
 * All of VS Code's CRLF repair (`validateCRLFWith*`, `fixCRLF`,
 * `adjustCarriageReturnFromNext`) is gone: text is normalised to '\n' before
 * it reaches the tree, so a line break can never straddle two pieces.
 */
package com.scorchsoft.ssmarkdown.text

/**
 * The structural edits behind [PieceTree.insert] and [PieceTree.delete]:
 * splitting, trimming and linking pieces. Offsets are already validated.
 */
@Suppress("TooManyFunctions") // Each structural case of VS Code's insert/delete is its own small function.
internal class PieceSurgery(
    private val tree: RedBlackTree,
    private val additions: AddBuffers,
) {
    /** Inserts normalised [text] at [offset], growing the last typed piece in place when it can. */
    fun insertText(
        offset: Int,
        text: CharSequence,
    ) {
        val position = tree.nodeAt(offset)
        val growable = position?.let(::growableNode)
        if (growable != null && additions.fitsInPlace(text)) {
            appendToNode(growable, text)
        } else {
            insertPieces(position, additions.store(text))
        }
    }

    /** Links existing [pieces] in at [offset] without copying their text. */
    fun insertPieces(
        offset: Int,
        pieces: List<Piece>,
    ) {
        insertPieces(tree.nodeAt(offset), pieces)
    }

    fun delete(
        offset: Int,
        count: Int,
    ) {
        val start = tree.nodeAt(offset) ?: return
        val end = checkNotNull(tree.nodeAt(offset + count)) { "Delete runs past the end of the text" }
        if (start.node === end.node) {
            deleteWithinNode(start, end.remainder)
        } else {
            deleteAcrossNodes(start, end)
        }
    }

    /**
     * The node typing at this position may extend: the piece ending exactly
     * here, if it is the tail of the newest add buffer. The lookup can land
     * on either side of a boundary, so the left neighbour is checked too;
     * without that, typing mid-document would add a node per keystroke.
     */
    private fun growableNode(position: NodePosition): TreeNode? {
        val node = position.node
        val candidate =
            when (position.remainder) {
                node.piece.length -> node
                0 -> tree.prev(node)
                else -> null
            }
        return candidate?.takeIf { it !== tree.nil && additions.canExtend(it.piece) }
    }

    private fun insertPieces(
        position: NodePosition?,
        pieces: List<Piece>,
    ) {
        if (pieces.isEmpty()) return
        val node = position?.node ?: tree.nil
        when {
            position == null -> insertAfter(tree.nil, pieces)
            position.remainder == 0 -> insertBefore(node, pieces)
            position.remainder < node.piece.length -> splitAndInsert(node, position.remainder, pieces)
            else -> insertAfter(node, pieces)
        }
    }

    private fun insertAfter(
        node: TreeNode,
        pieces: List<Piece>,
    ): TreeNode {
        var last = node
        for (piece in pieces) last = tree.insertRight(last, piece)
        return last
    }

    private fun insertBefore(
        node: TreeNode,
        pieces: List<Piece>,
    ) {
        var first = node
        for (i in pieces.indices.reversed()) first = tree.insertLeft(first, pieces[i])
    }

    private fun splitAndInsert(
        node: TreeNode,
        at: Int,
        pieces: List<Piece>,
    ) {
        val piece = node.piece
        val right = piece.slice(at, piece.length)
        replacePiece(node, piece.slice(0, at))
        tree.insertRight(insertAfter(node, pieces), right)
    }

    private fun deleteWithinNode(
        start: NodePosition,
        endRemainder: Int,
    ) {
        val node = start.node
        val length = node.piece.length
        when {
            start.remainder == 0 && endRemainder == length -> {
                tree.delete(node)
            }

            start.remainder == 0 -> {
                replacePiece(node, node.piece.slice(endRemainder, length))
            }

            endRemainder == length -> {
                replacePiece(node, node.piece.slice(0, start.remainder))
            }

            else -> {
                val right = node.piece.slice(endRemainder, length)
                replacePiece(node, node.piece.slice(0, start.remainder))
                tree.insertRight(node, right)
            }
        }
    }

    private fun deleteAcrossNodes(
        start: NodePosition,
        end: NodePosition,
    ) {
        val doomed = ArrayList<TreeNode>()
        val first = start.node
        val last = end.node
        replacePiece(first, first.piece.slice(0, start.remainder))
        if (first.piece.length == 0) doomed += first
        replacePiece(last, last.piece.slice(end.remainder, last.piece.length))
        if (last.piece.length == 0) doomed += last
        var between = tree.next(first)
        while (between !== tree.nil && between !== last) {
            doomed += between
            between = tree.next(between)
        }
        doomed.forEach(tree::delete)
    }

    private fun appendToNode(
        node: TreeNode,
        text: CharSequence,
    ) {
        replacePiece(node, additions.extend(node.piece, text))
    }

    /** Swaps a node's piece and pushes the size and line-feed change up the tree. */
    private fun replacePiece(
        node: TreeNode,
        piece: Piece,
    ) {
        val old = node.piece
        if (old === piece) return
        node.piece = piece
        tree.updateMetadata(node, piece.length - old.length, piece.lineFeeds - old.lineFeeds)
    }
}
