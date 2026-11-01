package com.scorchsoft.ssmarkdown.markdown

/**
 * Builds text with runs of whitespace collapsed to one space and none at
 * either end: how a heading reads in the outline and how a link label is
 * compared. Stops accepting characters at [limit].
 */
internal class CollapsedText(
    private val limit: Int = Int.MAX_VALUE,
) {
    private val out = StringBuilder()
    private var pendingSpace = false

    /** True once [limit] characters are in; later appends are ignored. */
    val isFull: Boolean get() = out.length >= limit

    fun append(c: Char) {
        when {
            isFull -> {
                Unit
            }

            c.isWhitespace() -> {
                pendingSpace = out.isNotEmpty()
            }

            else -> {
                if (pendingSpace) out.append(' ')
                pendingSpace = false
                out.append(c)
            }
        }
    }

    override fun toString(): String = out.toString()
}
