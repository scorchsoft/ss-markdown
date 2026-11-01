package com.scorchsoft.ssmarkdown.files.merge

/**
 * Text as lines that keep their own terminators, so joining them gives
 * back the exact text: "a\nb" is ["a\n", "b"], and a missing final
 * newline survives a merge untouched.
 */
internal object Lines {
    fun split(text: CharSequence): List<String> {
        val lines = ArrayList<String>()
        var start = 0
        for (i in text.indices) {
            if (text[i] == '\n') {
                lines += text.subSequence(start, i + 1).toString()
                start = i + 1
            }
        }
        if (start < text.length) lines += text.subSequence(start, text.length).toString()
        return lines
    }

    /**
     * Maps each distinct line to a small int, shared across every sequence
     * passed in, so the diff compares ints rather than strings.
     */
    fun intern(vararg sequences: List<String>): List<IntArray> {
        val ids = HashMap<String, Int>()
        return sequences.map { lines -> IntArray(lines.size) { ids.getOrPut(lines[it]) { ids.size } } }
    }
}
