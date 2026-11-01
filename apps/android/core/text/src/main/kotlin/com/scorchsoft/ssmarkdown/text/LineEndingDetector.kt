package com.scorchsoft.ssmarkdown.text

/**
 * Records each line break as a file is loaded and works out its
 * [LineEndings]. The per-line record is only allocated once a second kind
 * of break turns up, so a uniform file costs three counters.
 */
internal class LineEndingDetector {
    private val counts = IntArray(LineEnding.entries.size)
    private var lines = 0
    private var first: LineEnding? = null
    private var perLine: ByteArray? = null

    fun record(ending: LineEnding) {
        counts[ending.ordinal]++
        val firstSeen = first ?: ending.also { first = it }
        var record = perLine
        if (record == null && ending != firstSeen) {
            record = ByteArray(maxOf(INITIAL_RECORD, lines * 2))
            record.fill(firstSeen.ordinal.toByte(), 0, lines)
            perLine = record
        }
        if (record != null) {
            if (lines == record.size) record = record.copyOf(record.size * 2).also { perLine = it }
            record[lines] = ending.ordinal.toByte()
        }
        lines++
    }

    fun finish(): LineEndings {
        val record = perLine ?: return LineEndings.Uniform(first ?: LineEnding.LF)
        // Ties go to the earlier entry, LF, then CRLF.
        val dominant = LineEnding.entries.maxBy { counts[it.ordinal] }
        val exceptionLines = IntArray(lines - counts[dominant.ordinal])
        val exceptionEndings = arrayOfNulls<LineEnding>(exceptionLines.size)
        var next = 0
        for (line in 0 until lines) {
            val ending = LineEnding.entries[record[line].toInt()]
            if (ending != dominant) {
                exceptionLines[next] = line
                exceptionEndings[next++] = ending
            }
        }
        return LineEndings.of(dominant, exceptionLines, exceptionEndings.requireNoNulls())
    }

    private companion object {
        const val INITIAL_RECORD = 1_024
    }
}
