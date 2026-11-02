package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.markdown.IncrementalTokenizer
import com.scorchsoft.ssmarkdown.markdown.LineState
import com.scorchsoft.ssmarkdown.markdown.edit.FenceRole
import com.scorchsoft.ssmarkdown.markdown.edit.FenceStates

/**
 * [FenceStates] answered from the view's [IncrementalTokenizer], so a
 * toolbar command reads the fence state the colours already show. A line
 * the accurate pass has not reached yet is brought up to date first,
 * within [BUDGET_NANOS]; past that it falls back to the tokenizer's
 * heuristic guess, as the colours do.
 */
internal class TokenizerFences(
    private val tokenizer: IncrementalTokenizer,
    private val lineOf: (Int) -> Int,
) : FenceStates {
    override fun roleAt(lineStart: Int): FenceRole {
        val line = lineOf(lineStart)
        if (!tokenizer.isAccurate(line)) tokenizer.tokenizeUntil(line, BUDGET_NANOS)
        val start = if (line == 0) LineState.DOCUMENT_START else tokenizer.resultAt(line - 1).endState
        return FenceRole.of(start, tokenizer.resultAt(line))
    }

    private companion object {
        /** A toolbar tap may spend this long catching the tokenizer up: a few frames, once. */
        const val BUDGET_NANOS = 50_000_000L
    }
}
