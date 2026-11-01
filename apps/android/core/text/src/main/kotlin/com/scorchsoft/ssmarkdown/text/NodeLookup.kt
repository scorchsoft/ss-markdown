/*
 * Ported in part from Visual Studio Code, pieceTreeBase.ts (`nodeAt`,
 * `getOffsetAt`). Copyright (c) Microsoft Corporation, MIT License; see
 * core/text/NOTICE.
 */
package com.scorchsoft.ssmarkdown.text

/**
 * The node for an edit at [offset], or null in an empty tree. At a boundary
 * between two pieces this may return either side (VS Code's rule); callers
 * look at [NodePosition.remainder] to tell which.
 */
internal fun RedBlackTree.nodeAt(offset: Int): NodePosition? {
    var x = root
    var remaining = offset
    var nodeStart = 0
    while (x !== nil) {
        when {
            x.sizeLeft > remaining -> {
                x = x.left
            }

            x.sizeLeft + x.piece.length >= remaining -> {
                return NodePosition(x, remaining - x.sizeLeft, nodeStart + x.sizeLeft)
            }

            else -> {
                val skipped = x.sizeLeft + x.piece.length
                remaining -= skipped
                nodeStart += skipped
                x = x.right
            }
        }
    }
    return null
}

/** The node holding the char at [index], which must be in `[0, length)`. */
internal fun RedBlackTree.nodeContaining(index: Int): NodePosition {
    var x = root
    var remaining = index
    var nodeStart = 0
    while (x !== nil) {
        when {
            remaining < x.sizeLeft -> {
                x = x.left
            }

            remaining < x.sizeLeft + x.piece.length -> {
                return NodePosition(
                    x,
                    remaining - x.sizeLeft,
                    nodeStart + x.sizeLeft,
                )
            }

            else -> {
                val skipped = x.sizeLeft + x.piece.length
                remaining -= skipped
                nodeStart += skipped
                x = x.right
            }
        }
    }
    throw IndexOutOfBoundsException("index $index is past the end of the text")
}

/** Offset of the first char of 0-based [line]; the caller checks the line exists. */
internal fun RedBlackTree.lineStartOffset(line: Int): Int {
    var x = root
    var lineFeedsToPass = line
    var before = 0
    while (x !== nil) {
        when {
            x.left !== nil && x.lineFeedsLeft >= lineFeedsToPass -> {
                x = x.left
            }

            x.lineFeedsLeft + x.piece.lineFeeds >= lineFeedsToPass -> {
                return before + x.sizeLeft + x.piece.offsetAfterLineFeed(lineFeedsToPass - x.lineFeedsLeft)
            }

            else -> {
                lineFeedsToPass -= x.lineFeedsLeft + x.piece.lineFeeds
                before += x.sizeLeft + x.piece.length
                x = x.right
            }
        }
    }
    return before
}

/** Number of line feeds in `[0, offset)`: the 0-based line of [offset]. */
internal fun RedBlackTree.lineFeedsBefore(offset: Int): Int {
    var x = root
    var remaining = offset
    var lineFeeds = 0
    while (x !== nil) {
        when {
            remaining < x.sizeLeft -> {
                x = x.left
            }

            remaining <= x.sizeLeft + x.piece.length -> {
                return lineFeeds + x.lineFeedsLeft + x.piece.lineFeedsBefore(remaining - x.sizeLeft)
            }

            else -> {
                remaining -= x.sizeLeft + x.piece.length
                lineFeeds += x.lineFeedsLeft + x.piece.lineFeeds
                x = x.right
            }
        }
    }
    return lineFeeds
}
