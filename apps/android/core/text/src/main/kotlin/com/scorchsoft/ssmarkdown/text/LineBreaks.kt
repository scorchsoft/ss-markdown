package com.scorchsoft.ssmarkdown.text

/**
 * The one place a `'\r'` is turned into a `'\n'` for text arriving after
 * load (typing, paste, a Preview edit). Loaded files go through
 * [TextBufferBuilder], which also records what the breaks were.
 */
internal object LineBreaks {
    /** [text] with every CRLF and lone CR as `'\n'`; [text] itself when it has no CR. */
    fun normalize(text: CharSequence): CharSequence {
        if (text is Snapshot || text.indexOf('\r') < 0) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\r') {
                out.append('\n')
                if (i + 1 < text.length && text[i + 1] == '\n') i++
            } else {
                out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /** Line feeds in normalised [text]; O(1) for a [Snapshot]. */
    fun count(text: CharSequence): Int =
        if (text is Snapshot) {
            text.lineCount - 1
        } else {
            text.count { it == '\n' }
        }
}
