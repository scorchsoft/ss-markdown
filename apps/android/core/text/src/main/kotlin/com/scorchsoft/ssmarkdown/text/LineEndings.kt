package com.scorchsoft.ssmarkdown.text

/**
 * The line breaks of a whole file: which [LineEnding] follows each line.
 *
 * Almost every file is [Uniform]. A [Mixed] file (a CRLF file someone
 * edited with an LF tool) keeps a per-line record of the lines that differ
 * from the dominant style, so opening and saving it without edits is
 * byte-identical, and editing it only gives *new* lines the dominant style.
 */
public sealed interface LineEndings {
    /** The break written after 0-based [line]. */
    public fun endingAfter(line: Int): LineEnding

    /**
     * The record after an edit that starts on [firstLine], removes
     * [removedBreaks] line breaks and inserts [insertedBreaks]. Removed
     * breaks lose their record; inserted ones get the dominant style; every
     * later line shifts.
     */
    public fun afterEdit(
        firstLine: Int,
        removedBreaks: Int,
        insertedBreaks: Int,
    ): LineEndings

    /** Every line ends the same way. */
    public data class Uniform(
        /** The one break this file uses. */
        public val ending: LineEnding,
    ) : LineEndings {
        override fun endingAfter(line: Int): LineEnding = ending

        override fun afterEdit(
            firstLine: Int,
            removedBreaks: Int,
            insertedBreaks: Int,
        ): LineEndings = this
    }

    /**
     * Lines end with [dominant] except those listed. [exceptionLines] is
     * sorted and parallel to [exceptionEndings]; only the minority is
     * stored, so a 1M-line CRLF file with three LF lines costs three entries.
     */
    public class Mixed internal constructor(
        /** The most common break, used for every unlisted and every new line. */
        public val dominant: LineEnding,
        private val exceptionLines: IntArray,
        private val exceptionEndings: Array<LineEnding>,
    ) : LineEndings {
        /** How many lines break differently from [dominant]. */
        public val exceptionCount: Int get() = exceptionLines.size

        override fun endingAfter(line: Int): LineEnding {
            val i = exceptionLines.binarySearch(line)
            return if (i >= 0) exceptionEndings[i] else dominant
        }

        override fun afterEdit(
            firstLine: Int,
            removedBreaks: Int,
            insertedBreaks: Int,
        ): LineEndings {
            val lines = ArrayList<Int>(exceptionLines.size)
            val endings = ArrayList<LineEnding>(exceptionLines.size)
            for (i in exceptionLines.indices) {
                val line = exceptionLines[i]
                when {
                    line < firstLine -> lines += line
                    line < firstLine + removedBreaks -> continue
                    else -> lines += line - removedBreaks + insertedBreaks
                }
                endings += exceptionEndings[i]
            }
            return of(dominant, lines.toIntArray(), endings.toTypedArray())
        }

        override fun equals(other: Any?): Boolean =
            other is Mixed &&
                dominant == other.dominant &&
                exceptionLines.contentEquals(other.exceptionLines) &&
                exceptionEndings.contentEquals(other.exceptionEndings)

        override fun hashCode(): Int =
            31 * (31 * dominant.hashCode() + exceptionLines.contentHashCode()) + exceptionEndings.contentHashCode()

        override fun toString(): String = "Mixed(dominant=$dominant, exceptions=${exceptionLines.size})"
    }

    /** Factories. */
    public companion object {
        /** A new file's default: Markdown is overwhelmingly LF. */
        public val DEFAULT: LineEndings = Uniform(LineEnding.LF)

        internal fun of(
            dominant: LineEnding,
            exceptionLines: IntArray,
            exceptionEndings: Array<LineEnding>,
        ): LineEndings =
            if (exceptionLines.isEmpty()) Uniform(dominant) else Mixed(dominant, exceptionLines, exceptionEndings)
    }
}
