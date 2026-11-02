package com.scorchsoft.ssmarkdown.markdown

import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.Snapshot

/**
 * The lines an [IncrementalTokenizer] reads. An interface rather than a
 * [PieceTree] so the tokenizer runs over the live tree on the main thread,
 * over a [Snapshot] on a background one, or over a plain list in a test.
 * Lines carry no line break; the editor's text holds `'\n'` only.
 */
public interface LineSource {
    /** Number of lines; an empty text has one empty line. */
    public val lineCount: Int

    /** The text of 0-based [line], without its line break. */
    public fun lineText(line: Int): CharSequence
}

/**
 * The live tree's lines, cut at [LineTokenizer.MAX_TOKENIZED_COLUMNS]: the
 * tokenizer never reads further, so a 5 MB line costs a 10k-char copy
 * rather than a 5 MB string each time it is re-read (performance review
 * PERF-16).
 */
public fun PieceTree.asLineSource(): LineSource =
    object : LineSource {
        override val lineCount: Int get() = this@asLineSource.lineCount

        override fun lineText(line: Int): CharSequence {
            val start = lineStartOffset(line)
            return getText(start, minOf(lineEndOffset(line), start + LineTokenizer.MAX_TOKENIZED_COLUMNS))
        }
    }

/** A snapshot's lines, for tokenising off the main thread; cut like the tree's. */
public fun Snapshot.asLineSource(): LineSource =
    object : LineSource {
        override val lineCount: Int get() = this@asLineSource.lineCount

        override fun lineText(line: Int): CharSequence {
            val start = lineStartOffset(line)
            return subSequence(start, minOf(lineEndOffset(line), start + LineTokenizer.MAX_TOKENIZED_COLUMNS))
        }
    }

/**
 * A source of monotonic time, injected so budget tests are deterministic.
 * The tokenizer never starts a thread: the caller decides where its time
 * slices run and passes the clock they are measured by.
 */
public fun interface NanoClock {
    /** Monotonic nanoseconds, as [System.nanoTime]. */
    public fun nanoTime(): Long

    /** The wall-clock-independent system timer. */
    public companion object {
        /** [System.nanoTime]. */
        public val SYSTEM: NanoClock = NanoClock { System.nanoTime() }
    }
}
