package com.scorchsoft.ssmarkdown.markdown.code

/**
 * A fixed table from words to small values, looked up straight from a
 * region of the line: `valueOf(text, start, end)` hashes and compares the
 * chars in place, so classifying an identifier allocates nothing (a
 * `Set<String>` would need a substring per word, per line).
 *
 * Open addressing over a power-of-two table at most half full. With
 * [ignoreCase], ASCII letters compare and hash case-insensitively (SQL,
 * PowerShell, Dockerfile); the words themselves are stored lower-case.
 */
internal class WordTable(
    words: Map<String, Int>,
    private val ignoreCase: Boolean = false,
) {
    private val capacity = Integer.highestOneBit(maxOf(words.size, 1) * 4 - 1).coerceAtLeast(LOAD_SLACK)
    private val mask = capacity - 1
    private val keys = arrayOfNulls<String>(capacity)
    private val values = IntArray(capacity)

    init {
        for ((word, value) in words) {
            val key = if (ignoreCase) word.lowercase() else word
            var slot = hash(key, 0, key.length) and mask
            while (keys[slot] != null && keys[slot] != key) slot = (slot + 1) and mask
            keys[slot] = key
            values[slot] = value
        }
    }

    /** The value stored for `text[start, end)`, or [MISSING]. */
    fun valueOf(
        text: CharSequence,
        start: Int,
        end: Int,
    ): Int {
        val length = end - start
        if (length <= 0 || length > MAX_WORD) return MISSING
        var slot = hash(text, start, end) and mask
        while (true) {
            val key = keys[slot] ?: return MISSING
            if (key.length == length && matches(key, text, start)) return values[slot]
            slot = (slot + 1) and mask
        }
    }

    /** True if `text[start, end)` is in the table. */
    fun contains(
        text: CharSequence,
        start: Int,
        end: Int,
    ): Boolean = valueOf(text, start, end) != MISSING

    private fun matches(
        key: String,
        text: CharSequence,
        start: Int,
    ): Boolean {
        for (i in key.indices) {
            if (key[i] != fold(text[start + i])) return false
        }
        return true
    }

    private fun hash(
        text: CharSequence,
        start: Int,
        end: Int,
    ): Int {
        var h = 0
        for (i in start until end) h = h * HASH_PRIME + fold(text[i]).code
        return h xor (h ushr HASH_SPREAD)
    }

    private fun fold(c: Char): Char = if (ignoreCase && c in 'A'..'Z') c + ('a' - 'A') else c

    companion object {
        /** What [valueOf] returns for a word that is not in the table. */
        const val MISSING: Int = -1

        /** No keyword is longer; a longer run is never looked up. */
        private const val MAX_WORD = 40
        private const val LOAD_SLACK = 8
        private const val HASH_PRIME = 31
        private const val HASH_SPREAD = 16

        /** A table where every word in [words] maps to [value]. */
        fun of(
            words: String,
            value: Int = 0,
            ignoreCase: Boolean = false,
        ): WordTable = WordTable(split(words).associateWith { value }, ignoreCase)

        /** The space-separated words of [words]. */
        fun split(words: String): List<String> = words.split(' ', '\n').filter { it.isNotEmpty() }
    }
}
