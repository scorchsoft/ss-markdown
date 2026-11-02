package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.markdown.edit.FenceStates

// The code view's find, large-paste and fence-state API, as extensions over
// its internal collaborators (FindHighlights, ChunkedPaste, TokenizerFences)
// so MarkdownCodeView itself stays the Android contract.

/**
 * Highlights find matches `[starts[i], ends[i])` (docs/ux-spec.md, "Find
 * & replace"); [current], an index into them or -1, gets the stronger
 * fill and an ink outline. The host searches, the view only draws, and
 * a frame touches just the matches on screen. Edits move the matches;
 * undo clears them until the host sets them again.
 */
fun MarkdownCodeView.setFindMatches(
    starts: IntArray,
    ends: IntArray,
    current: Int = -1,
) {
    finds.set(starts, ends, current)
    invalidate()
}

/** Removes every find highlight: the find bar closed. */
fun MarkdownCodeView.clearFindMatches() {
    finds.clear()
    invalidate()
}

/** How many find matches are drawn now. */
val MarkdownCodeView.findMatchCount: Int get() = finds.count

/**
 * Replaces the selection with [text] as a large paste must go
 * (docs/edge-cases.md, "Paste of 50 MB"): prepared off the main
 * thread, inserted a slice per frame with [EditorListener.onPasteProgress],
 * undone in one step, with typing during it applied after it. Short
 * texts go in at once.
 */
fun MarkdownCodeView.pasteText(text: CharSequence) = pasteText(text.length) { text }

/** True while a large paste is going in. */
val MarkdownCodeView.isPasting: Boolean get() = paste.isRunning

/** Fence state for `EditCommand.apply`, from this view's tokenizer rather than a fresh scan. */
fun MarkdownCodeView.fenceStates(): FenceStates =
    TokenizerFences(highlight.tokens) {
        session.document.text
            .positionAt(it)
            .line
    }

/**
 * The memory governor's "line layout caches" step: drops every cached
 * line layout and recorded display list. They are rebuilt for the lines
 * drawn next; nothing on screen moves.
 */
fun MarkdownCodeView.trimLayoutCaches() {
    geometry.cache.clear()
    renderer.invalidateDrawings()
    invalidate()
}

/**
 * The memory governor's "tokenizer cache" step: drops every cached
 * token. Block states stay, so only the lines drawn next are re-tokenised.
 */
fun MarkdownCodeView.trimTokenCache() = highlight.trimCache()
