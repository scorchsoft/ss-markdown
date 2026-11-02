/*
 * Ported from Visual Studio Code, rbTreeBase.ts. Copyright (c) Microsoft
 * Corporation, MIT License; see core/text/NOTICE.
 *
 * Differences from the original: the sentinel belongs to its tree rather
 * than being a module-wide global (two documents on two threads must not
 * share a mutable node), and the long fix-up loops are split into their
 * mirrored halves.
 */
package com.scorchsoft.ssmarkdown.text

/**
 * A tree node. Besides its piece it caches the size and line-feed count of
 * its LEFT subtree only, which is what makes offset and line lookups a
 * single O(log n) descent (the "one structure, no separate line tree" rule).
 */
internal class TreeNode(
    var piece: Piece,
    var isRed: Boolean,
) {
    var parent: TreeNode = this
    var left: TreeNode = this
    var right: TreeNode = this
    var sizeLeft: Int = 0
    var lineFeedsLeft: Int = 0
}

/** A document offset resolved to a node: `nodeStart + remainder == offset`. */
internal class NodePosition(
    val node: TreeNode,
    val remainder: Int,
    val nodeStart: Int,
)

/**
 * The balanced tree under [PieceTree]: CLRS red-black insert and delete,
 * plus the bookkeeping that keeps `sizeLeft` / `lineFeedsLeft` true through
 * every rotation.
 */
@Suppress("TooManyFunctions") // One cohesive algorithm; splitting it would scatter the invariants.
internal class RedBlackTree {
    /** The leaf sentinel: black, zero-length, and its own parent and children. */
    val nil: TreeNode = TreeNode(Piece.EMPTY, isRed = false)

    var root: TreeNode = nil

    var nodeCount: Int = 0
        private set

    fun clear() {
        root = nil
        nodeCount = 0
    }

    fun leftmost(node: TreeNode): TreeNode {
        var n = node
        while (n.left !== nil) n = n.left
        return n
    }

    fun rightmost(node: TreeNode): TreeNode {
        var n = node
        while (n.right !== nil) n = n.right
        return n
    }

    /** The in-order successor, or [nil]. */
    fun next(node: TreeNode): TreeNode {
        if (node.right !== nil) return leftmost(node.right)
        var n = node
        while (n.parent !== nil && n.parent.left !== n) n = n.parent
        return n.parent
    }

    /** The in-order predecessor, or [nil]. */
    fun prev(node: TreeNode): TreeNode {
        if (node.left !== nil) return rightmost(node.left)
        var n = node
        while (n.parent !== nil && n.parent.right !== n) n = n.parent
        return n.parent
    }

    /** Visits every piece in document order. */
    inline fun forEachPiece(action: (Piece) -> Unit) {
        if (root === nil) return
        var n = leftmost(root)
        while (n !== nil) {
            action(n.piece)
            n = next(n)
        }
    }

    /** Inserts [piece] immediately after [node] in document order ([nil] when the tree is empty). */
    fun insertRight(
        node: TreeNode,
        piece: Piece,
    ): TreeNode {
        val z = newNode(piece)
        when {
            root === nil -> root = z.also { it.isRed = false }
            node.right === nil -> attach(z, parent = node, asLeft = false)
            else -> attach(z, parent = leftmost(node.right), asLeft = true)
        }
        fixInsert(z)
        return z
    }

    /** Inserts [piece] immediately before [node] in document order ([nil] when the tree is empty). */
    fun insertLeft(
        node: TreeNode,
        piece: Piece,
    ): TreeNode {
        val z = newNode(piece)
        when {
            root === nil -> root = z.also { it.isRed = false }
            node.left === nil -> attach(z, parent = node, asLeft = true)
            else -> attach(z, parent = rightmost(node.left), asLeft = false)
        }
        fixInsert(z)
        return z
    }

    /** Removes [z] and rebalances. Other nodes keep their identity. */
    fun delete(z: TreeNode) {
        nodeCount--
        val y = if (z.left === nil || z.right === nil) z else leftmost(z.right)
        val x = if (y.left !== nil) y.left else y.right
        if (y === root) {
            root = x
            x.isRed = false
            root.parent = nil
            nil.parent = nil
            return
        }
        val yWasRed = y.isRed
        spliceOut(z, y, x)
        refreshParentLeftSize(x)
        recomputeMetadata(x.parent)
        if (!yWasRed) deleteFixup(x)
        nil.parent = nil
    }

    /**
     * A node's piece changed length by [delta] and line feeds by
     * [lineFeedDelta]: walk to the root fixing every ancestor that has it
     * in its left subtree.
     */
    fun updateMetadata(
        node: TreeNode,
        delta: Int,
        lineFeedDelta: Int,
    ) {
        var x = node
        while (x !== root && x !== nil) {
            if (x.parent.left === x) {
                x.parent.sizeLeft += delta
                x.parent.lineFeedsLeft += lineFeedDelta
            }
            x = x.parent
        }
    }

    fun subtreeLength(node: TreeNode): Int {
        var n = node
        var sum = 0
        while (n !== nil) {
            sum += n.sizeLeft + n.piece.length
            n = n.right
        }
        return sum
    }

    fun subtreeLineFeeds(node: TreeNode): Int {
        var n = node
        var sum = 0
        while (n !== nil) {
            sum += n.lineFeedsLeft + n.piece.lineFeeds
            n = n.right
        }
        return sum
    }

    private fun newNode(piece: Piece): TreeNode {
        nodeCount++
        return TreeNode(piece, isRed = true).apply {
            parent = nil
            left = nil
            right = nil
        }
    }

    private fun attach(
        z: TreeNode,
        parent: TreeNode,
        asLeft: Boolean,
    ) {
        if (asLeft) parent.left = z else parent.right = z
        z.parent = parent
    }

    /** Replaces [z] by [y] (its successor or itself), with [x] taking [y]'s old place. */
    private fun spliceOut(
        z: TreeNode,
        y: TreeNode,
        x: TreeNode,
    ) {
        if (y === y.parent.left) y.parent.left = x else y.parent.right = x
        if (y === z) {
            x.parent = y.parent
            recomputeMetadata(x)
            return
        }
        x.parent = if (y.parent === z) y else y.parent
        // x's subtree changed first, so fix sizes there before y moves.
        recomputeMetadata(x)
        y.left = z.left
        y.right = z.right
        y.parent = z.parent
        y.isRed = z.isRed
        replaceChild(z.parent, z, y)
        if (y.left !== nil) y.left.parent = y
        if (y.right !== nil) y.right.parent = y
        y.sizeLeft = z.sizeLeft
        y.lineFeedsLeft = z.lineFeedsLeft
        recomputeMetadata(y)
    }

    private fun refreshParentLeftSize(x: TreeNode) {
        val parent = x.parent
        if (parent.left !== x) return
        val newSize = subtreeLength(x)
        val newLineFeeds = subtreeLineFeeds(x)
        if (newSize != parent.sizeLeft || newLineFeeds != parent.lineFeedsLeft) {
            val delta = newSize - parent.sizeLeft
            val lineFeedDelta = newLineFeeds - parent.lineFeedsLeft
            parent.sizeLeft = newSize
            parent.lineFeedsLeft = newLineFeeds
            updateMetadata(parent, delta, lineFeedDelta)
        }
    }

    private fun deleteFixup(start: TreeNode) {
        var x = start
        while (x !== root && !x.isRed) {
            x = if (x === x.parent.left) rebalanceAfterLeftLoss(x) else rebalanceAfterRightLoss(x)
        }
        x.isRed = false
    }

    private fun rebalanceAfterLeftLoss(x: TreeNode): TreeNode {
        var w = x.parent.right
        if (w.isRed) {
            w.isRed = false
            x.parent.isRed = true
            leftRotate(x.parent)
            w = x.parent.right
        }
        if (!w.left.isRed && !w.right.isRed) {
            w.isRed = true
            return x.parent
        }
        if (!w.right.isRed) {
            w.left.isRed = false
            w.isRed = true
            rightRotate(w)
            w = x.parent.right
        }
        w.isRed = x.parent.isRed
        x.parent.isRed = false
        w.right.isRed = false
        leftRotate(x.parent)
        return root
    }

    private fun rebalanceAfterRightLoss(x: TreeNode): TreeNode {
        var w = x.parent.left
        if (w.isRed) {
            w.isRed = false
            x.parent.isRed = true
            rightRotate(x.parent)
            w = x.parent.left
        }
        if (!w.left.isRed && !w.right.isRed) {
            w.isRed = true
            return x.parent
        }
        if (!w.left.isRed) {
            w.right.isRed = false
            w.isRed = true
            leftRotate(w)
            w = x.parent.left
        }
        w.isRed = x.parent.isRed
        x.parent.isRed = false
        w.left.isRed = false
        rightRotate(x.parent)
        return root
    }

    private fun fixInsert(inserted: TreeNode) {
        recomputeMetadata(inserted)
        var x = inserted
        while (x !== root && x.parent.isRed) {
            x = if (x.parent === x.parent.parent.left) fixInsertUnderLeft(x) else fixInsertUnderRight(x)
        }
        root.isRed = false
    }

    private fun fixInsertUnderLeft(x: TreeNode): TreeNode {
        val uncle = x.parent.parent.right
        if (uncle.isRed) {
            x.parent.isRed = false
            uncle.isRed = false
            x.parent.parent.isRed = true
            return x.parent.parent
        }
        var n = x
        if (n === n.parent.right) {
            n = n.parent
            leftRotate(n)
        }
        n.parent.isRed = false
        n.parent.parent.isRed = true
        rightRotate(n.parent.parent)
        return n
    }

    private fun fixInsertUnderRight(x: TreeNode): TreeNode {
        val uncle = x.parent.parent.left
        if (uncle.isRed) {
            x.parent.isRed = false
            uncle.isRed = false
            x.parent.parent.isRed = true
            return x.parent.parent
        }
        var n = x
        if (n === n.parent.left) {
            n = n.parent
            rightRotate(n)
        }
        n.parent.isRed = false
        n.parent.parent.isRed = true
        leftRotate(n.parent.parent)
        return n
    }

    private fun leftRotate(x: TreeNode) {
        val y = x.right
        y.sizeLeft += x.sizeLeft + x.piece.length
        y.lineFeedsLeft += x.lineFeedsLeft + x.piece.lineFeeds
        x.right = y.left
        if (y.left !== nil) y.left.parent = x
        y.parent = x.parent
        replaceChild(x.parent, x, y)
        y.left = x
        x.parent = y
    }

    private fun rightRotate(y: TreeNode) {
        val x = y.left
        y.left = x.right
        if (x.right !== nil) x.right.parent = y
        x.parent = y.parent
        y.sizeLeft -= x.sizeLeft + x.piece.length
        y.lineFeedsLeft -= x.lineFeedsLeft + x.piece.lineFeeds
        replaceChild(y.parent, y, x)
        x.right = y
        y.parent = x
    }

    private fun replaceChild(
        parent: TreeNode,
        old: TreeNode,
        new: TreeNode,
    ) {
        when {
            parent === nil -> root = new
            parent.left === old -> parent.left = new
            else -> parent.right = new
        }
    }

    /**
     * Something below [node] changed: find the nearest ancestor holding it
     * in its left subtree, recount that subtree, and push the difference up.
     */
    private fun recomputeMetadata(node: TreeNode) {
        var x = node
        if (x === root) return
        while (x !== root && x === x.parent.right) x = x.parent
        if (x === root) return // appended at the very end: no left subtree changed
        x = x.parent
        val delta = subtreeLength(x.left) - x.sizeLeft
        val lineFeedDelta = subtreeLineFeeds(x.left) - x.lineFeedsLeft
        x.sizeLeft += delta
        x.lineFeedsLeft += lineFeedDelta
        if (delta != 0 || lineFeedDelta != 0) updateMetadata(x, delta, lineFeedDelta)
    }
}
