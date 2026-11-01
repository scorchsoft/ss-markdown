package com.scorchsoft.ssmarkdown.history.versions

/**
 * A text cut into lines, each keeping its own terminator, so joining the
 * lines gives back the text exactly (a final line without `\n`, a `\r\n`
 * pair and a lone `\r` all survive).
 *
 * Lines are the unit of every delta and every diff in version history:
 * they are what people edit, and they keep Myers' input small (a 200 KB
 * document is a few thousand lines, not 200,000 characters).
 *
 * @property text the whole text.
 * @property starts `starts[i]` is the offset of line `i`; `starts[count]` is `text.length`.
 */
internal class Lines private constructor(
    val text: String,
    val starts: IntArray,
) {
    /** Number of lines. An empty text has none. */
    val count: Int get() = starts.size - 1

    /** Line [index], including its terminator. */
    operator fun get(index: Int): String = text.substring(starts[index], starts[index + 1])

    companion object {
        /** Splits [text] after every `\n`. */
        fun of(text: String): Lines {
            val starts = ArrayList<Int>(text.length / AVERAGE_LINE + 2)
            starts += 0
            var i = text.indexOf('\n')
            while (i >= 0) {
                starts += i + 1
                i = text.indexOf('\n', i + 1)
            }
            if (starts.last() != text.length) starts += text.length
            return Lines(text, starts.toIntArray())
        }

        private const val AVERAGE_LINE = 40
    }
}

/**
 * Turns lines into small ints, equal lines getting equal ints, so Myers
 * compares ints instead of strings. One interner serves both sides of a
 * diff; that is what makes their ids comparable.
 */
internal class LineInterner {
    private val ids = HashMap<String, Int>()

    /** Ids for every line of [lines], in order. */
    fun intern(lines: Lines): IntArray = IntArray(lines.count) { ids.getOrPut(lines[it]) { ids.size } }
}
