package com.scorchsoft.ssmarkdown.editor

/**
 * Selects the paragraph under [offset] (triple-tap): the run of non-blank
 * lines around it, as Markdown reads a paragraph, without the line break
 * after it. On a blank line, just that line.
 */
fun EditingSession.selectParagraph(offset: Int) {
    val tree = document.text
    val line = tree.positionAt(offset.coerceIn(0, tree.length)).line
    val blank = { l: Int -> tree.lineContent(l).isBlank() }
    var first = line
    var last = line
    if (!blank(line)) {
        while (first > 0 && !blank(first - 1)) first--
        while (last < tree.lineCount - 1 && !blank(last + 1)) last++
    }
    clearComposing()
    document.breakTypingRun()
    setSelection(tree.lineStartOffset(first), tree.lineEndOffset(last))
}

/** Selects the word under [offset] (double-tap, long-press). */
fun EditingSession.selectWord(
    offset: Int,
    words: WordFinder,
) {
    val word = words.wordAt(document.text, offset)
    clearComposing()
    document.breakTypingRun()
    setSelection(word.first, word.last + 1)
}
