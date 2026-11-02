package com.scorchsoft.ssmarkdown.text

/**
 * Packs a stream of normalised chars into sealed [TextBuffer] chunks of at
 * most [TextBuffer.CAPACITY] chars. A chunk never ends on a high surrogate:
 * that char moves to the next chunk, so an emoji is never cut in two.
 * (It never ends on `'\r'` either, because no `'\r'` gets this far.)
 */
internal class ChunkPacker {
    private val chunks = ArrayList<TextBuffer>()
    private var chunk = CharArray(TextBuffer.CAPACITY)
    private var used = 0

    fun put(c: Char) {
        if (used == chunk.size) seal()
        chunk[used++] = c
    }

    fun putAll(
        chars: CharArray,
        start: Int,
        count: Int,
    ) {
        var from = start
        val end = start + count
        while (from < end) {
            if (used == chunk.size) seal()
            val take = minOf(end - from, chunk.size - used)
            chars.copyInto(chunk, used, from, from + take)
            used += take
            from += take
        }
    }

    /** The finished chunks; the packer must not be used afterwards. */
    fun finish(): List<TextBuffer> {
        if (used > 0) chunks += TextBuffer.sealed(chunk.copyOf(used), used)
        used = 0
        return chunks
    }

    private fun seal() {
        val carry = used > 1 && chunk[used - 1].isHighSurrogate()
        val keep = if (carry) used - 1 else used
        val next = CharArray(TextBuffer.CAPACITY)
        if (carry) next[0] = chunk[used - 1]
        chunks += TextBuffer.sealed(chunk, keep)
        chunk = next
        used = if (carry) 1 else 0
    }
}
