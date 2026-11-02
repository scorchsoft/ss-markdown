package com.scorchsoft.ssmarkdown.history.versions

/**
 * A piece of one side of a word diff.
 *
 * @property text the characters.
 * @property changed true when they are not in the other side.
 */
public data class WordSegment(
    public val text: String,
    public val changed: Boolean,
)

/**
 * A changed line pair refined to words, so "Compare" can highlight the
 * three characters that changed in a long paragraph rather than the
 * whole line.
 *
 * Joining a side's segments gives that side's text back exactly.
 *
 * @property old segments of the old line.
 * @property new segments of the new line.
 */
public data class WordDiff(
    public val old: List<WordSegment>,
    public val new: List<WordSegment>,
) {
    /** Entry point. */
    public companion object {
        /**
         * Refines a removed/added pair of lines. Tokens are runs of letters
         * and digits, runs of whitespace, and single other characters (so
         * Markdown punctuation such as `**` or `[` diffs on its own).
         */
        public fun refine(
            old: String,
            new: String,
        ): WordDiff {
            val oldTokens = tokens(old)
            val newTokens = tokens(new)
            val ids = HashMap<String, Int>()
            val a = IntArray(oldTokens.size) { ids.getOrPut(oldTokens[it]) { ids.size } }
            val b = IntArray(newTokens.size) { ids.getOrPut(newTokens[it]) { ids.size } }
            val regions =
                MyersDiff.diff(a, b, DiffBudget(maxEdits = WORD_EDIT_CAP))
                    ?: listOf(EditRegion(0, a.size, 0, b.size))
            return WordDiff(
                old = segments(oldTokens, regions.map { it.aStart until it.aEnd }),
                new = segments(newTokens, regions.map { it.bStart until it.bEnd }),
            )
        }

        private const val WORD_EDIT_CAP = 4_000

        private fun tokens(text: String): List<String> {
            val out = ArrayList<String>()
            var i = 0
            while (i < text.length) {
                val start = i
                val kind = kindOf(text[i])
                i++
                if (kind != OTHER) {
                    while (i < text.length && kindOf(text[i]) == kind) i++
                }
                out += text.substring(start, i)
            }
            return out
        }

        private const val WORD = 0
        private const val SPACE = 1
        private const val OTHER = 2

        private fun kindOf(c: Char): Int =
            when {
                c.isLetterOrDigit() || c.isSurrogate() -> WORD
                c.isWhitespace() -> SPACE
                else -> OTHER
            }

        private fun segments(
            tokens: List<String>,
            changed: List<IntRange>,
        ): List<WordSegment> {
            val out = ArrayList<WordSegment>()
            var index = 0
            for (range in changed) {
                if (index < range.first) out += WordSegment(tokens.subList(index, range.first).joinToString(""), false)
                if (!range.isEmpty()) {
                    out +=
                        WordSegment(tokens.subList(range.first, range.last + 1).joinToString(""), true)
                }
                index = range.last + 1
            }
            if (index < tokens.size) out += WordSegment(tokens.subList(index, tokens.size).joinToString(""), false)
            return out
        }
    }
}
