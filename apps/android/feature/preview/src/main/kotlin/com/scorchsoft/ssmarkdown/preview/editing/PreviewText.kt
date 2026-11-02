package com.scorchsoft.ssmarkdown.preview.editing

import com.scorchsoft.ssmarkdown.text.Snapshot

/**
 * The host's text as the preview's editing code reads it: characters plus
 * line lookups, which every message needs and which must not cost a scan of a
 * 20 MB file per keystroke (docs/robustness.md, Part 2). A core:text
 * [Snapshot] answers them in O(log pieces) through [of].
 */
interface PreviewText {
    /** The characters, `'\n'` only. */
    val chars: CharSequence

    val length: Int get() = chars.length

    /** The 0-based line holding [offset] (which may equal [length]). */
    fun lineOf(offset: Int): Int

    /** The offset of the first char of [line]. */
    fun lineStart(line: Int): Int

    /** The offset just past the last char of [line], before its `'\n'`. */
    fun lineEnd(line: Int): Int

    fun slice(
        from: Int,
        to: Int,
    ): String = chars.subSequence(from, to).toString()

    /** Adapters. */
    companion object {
        /** A core:text snapshot, with its own line index. */
        fun of(snapshot: Snapshot): PreviewText =
            object : PreviewText {
                override val chars: CharSequence = snapshot

                override fun lineOf(offset: Int): Int = snapshot.positionAt(offset).line

                override fun lineStart(line: Int): Int = snapshot.lineStartOffset(line)

                override fun lineEnd(line: Int): Int = snapshot.lineEndOffset(line)
            }

        /** Plain text, indexed once: for tests and small documents. */
        fun of(text: CharSequence): PreviewText {
            val starts = ArrayList<Int>().apply { add(0) }
            for (i in text.indices) if (text[i] == '\n') starts += i + 1
            return object : PreviewText {
                override val chars: CharSequence = text

                override fun lineOf(offset: Int): Int {
                    val i = starts.binarySearch(offset)
                    return if (i >= 0) i else -i - 2
                }

                override fun lineStart(line: Int): Int = starts[line.coerceIn(0, starts.lastIndex)]

                override fun lineEnd(line: Int): Int =
                    if (line >=
                        starts.lastIndex
                    ) {
                        text.length
                    } else {
                        starts[line + 1] - 1
                    }
            }
        }
    }
}
