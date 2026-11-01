/*
 * Ported from Visual Studio Code's test helpers `assertValidTree`,
 * `assertValidNode` and `depth` (pieceTreeTextBuffer.test.ts). Copyright (c)
 * Microsoft Corporation, MIT License; see core/text/NOTICE.
 */
package com.scorchsoft.ssmarkdown.text

/**
 * Verifies the red-black rules and every cached `sizeLeft` /
 * `lineFeedsLeft`. O(n): for tests and debugging, never the editing path.
 */
internal fun RedBlackTree.checkInvariants() {
    check(!nil.isRed && nil.sizeLeft == 0 && nil.lineFeedsLeft == 0) { "sentinel was modified" }
    check(nil.parent === nil && nil.left === nil && nil.right === nil) { "sentinel links were left dirty" }
    if (root === nil) return
    check(!root.isRed) { "root must be black" }
    check(root.parent === nil) { "root must have no parent" }
    blackHeight(root)
    checkSubtree(root)
}

/** Returns the subtree's (length, line feeds) after checking its caches. */
private fun RedBlackTree.checkSubtree(node: TreeNode): Pair<Int, Int> {
    if (node === nil) return 0 to 0
    if (node.isRed) check(!node.left.isRed && !node.right.isRed) { "red node with a red child" }
    if (node.left !== nil) check(node.left.parent === node) { "broken parent link" }
    if (node.right !== nil) check(node.right.parent === node) { "broken parent link" }
    val (leftSize, leftLineFeeds) = checkSubtree(node.left)
    check(leftSize == node.sizeLeft) { "sizeLeft ${node.sizeLeft} != $leftSize" }
    check(leftLineFeeds == node.lineFeedsLeft) { "lineFeedsLeft ${node.lineFeedsLeft} != $leftLineFeeds" }
    val (rightSize, rightLineFeeds) = checkSubtree(node.right)
    return (leftSize + node.piece.length + rightSize) to (leftLineFeeds + node.piece.lineFeeds + rightLineFeeds)
}

private fun RedBlackTree.blackHeight(node: TreeNode): Int {
    if (node === nil) return 1
    val left = blackHeight(node.left)
    check(left == blackHeight(node.right)) { "unequal black heights" }
    return left + if (node.isRed) 0 else 1
}
