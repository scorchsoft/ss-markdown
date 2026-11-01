package com.scorchsoft.ssmarkdown.bench

import android.os.Build
import java.io.File
import java.util.Locale

/**
 * One engine × corpus measurement. A null number means "not measured",
 * and [note] says why (OOM, timeout, killed); a result is never dropped,
 * because "it fell over" is the most important number of all.
 */
data class RunResult(
    val engine: String,
    val corpus: String,
    /** File read + engine model build + main-thread hand-over + first frame. */
    val openMillis: Long? = null,
    /** The part of [openMillis] spent blocking the main thread: what freezes the UI. */
    val mainMillis: Long? = null,
    /** Java heap in use after load and a GC. */
    val heapMb: Long? = null,
    val framesOver16Percent: Double? = null,
    val framesOver32Percent: Double? = null,
    val frames: Int? = null,
    val keyAverageMillis: Double? = null,
    val keyP95Millis: Double? = null,
    val keystrokes: Int? = null,
    val note: String = "",
) {
    val key: String get() = "$engine|$corpus"

    /** One tab-separated line for [ResultStore]. */
    fun encode(): String =
        listOf(engine, corpus, openMillis, mainMillis, heapMb, framesOver16Percent, framesOver32Percent, frames)
            .plus(listOf(keyAverageMillis, keyP95Millis, keystrokes, note.replace('\t', ' ').replace('\n', ' ')))
            .joinToString("\t") { it?.toString() ?: "" }

    companion object {
        private const val FIELDS = 12

        fun decode(line: String): RunResult? {
            val f = line.split('\t')
            if (f.size != FIELDS) return null
            @Suppress("MagicNumber") // Field positions of the format written by encode().
            return RunResult(
                engine = f[0],
                corpus = f[1],
                openMillis = f[2].toLongOrNull(),
                mainMillis = f[3].toLongOrNull(),
                heapMb = f[4].toLongOrNull(),
                framesOver16Percent = f[5].toDoubleOrNull(),
                framesOver32Percent = f[6].toDoubleOrNull(),
                frames = f[7].toIntOrNull(),
                keyAverageMillis = f[8].toDoubleOrNull(),
                keyP95Millis = f[9].toDoubleOrNull(),
                keystrokes = f[10].toIntOrNull(),
                note = f[11],
            )
        }
    }
}

/**
 * Results saved to disk after every run, so a run that kills the process
 * (an OOM in native code, an ANR the system acts on) still leaves a row.
 *
 * Before the risky part of a run, [markPending] writes what is about to
 * happen. If the process dies there, the next launch finds the marker and
 * records it as the result ("killed during …"), so Run all can be resumed
 * and nothing is silently missing.
 */
class ResultStore(
    directory: File,
) {
    private val results = File(directory, "bench-results.tsv")
    private val pending = File(directory, "bench-pending.tsv")

    /** Every saved result, oldest first, after turning a leftover pending marker into a "killed" row. */
    fun load(): List<RunResult> {
        pending
            .takeIf { it.exists() }
            ?.readText()
            ?.let { RunResult.decode(it.trim()) }
            ?.let { save(it) }
        pending.delete()
        if (!results.exists()) return emptyList()
        return results
            .readLines()
            .mapNotNull(RunResult::decode)
            .associateBy { it.key }
            .values
            .toList()
    }

    /** Records [result], replacing any earlier row for the same engine and corpus. */
    fun save(result: RunResult) {
        val kept =
            if (results.exists()) {
                results.readLines().filter {
                    RunResult
                        .decode(
                            it,
                        )?.key != result.key
                }
            } else {
                emptyList()
            }
        results.writeText((kept + result.encode()).joinToString("\n", postfix = "\n"))
        pending.delete()
    }

    /** Writes the row to keep if the process dies before [save] or [clearPending]. */
    fun markPending(result: RunResult) {
        pending.writeText(result.encode())
    }

    fun clearPending() {
        pending.delete()
    }

    fun clear() {
        results.delete()
        pending.delete()
    }
}

/** The plain-text table Andrew pastes back: a device header, then one row per run. */
object ResultsTable {
    fun format(
        results: List<RunResult>,
        refreshRateHz: Float,
    ): String {
        val maxHeapMb = Runtime.getRuntime().maxMemory() / BYTES_PER_MB
        val header =
            "SS editor bench | ${Build.MANUFACTURER} ${Build.MODEL} | API ${Build.VERSION.SDK_INT} | " +
                "heap limit $maxHeapMb MB | ${refreshRateHz.roundToText(0)} Hz"
        val columns =
            listOf(
                "engine",
                "corpus",
                "open ms",
                "main ms",
                "heap MB",
                ">16ms %",
                ">32ms %",
                "frames",
                "key avg",
                "key p95",
                "note",
            )
        val rows =
            results.map { r ->
                listOf(
                    r.engine,
                    r.corpus,
                    r.openMillis.text(),
                    r.mainMillis.text(),
                    r.heapMb.text(),
                    r.framesOver16Percent?.roundToText(1) ?: "-",
                    r.framesOver32Percent?.roundToText(1) ?: "-",
                    r.frames.text(),
                    r.keyAverageMillis?.roundToText(2) ?: "-",
                    r.keyP95Millis?.roundToText(2) ?: "-",
                    r.note,
                )
            }
        val widths = columns.indices.map { i -> (rows.map { it[i].length } + columns[i].length).max() }

        fun line(cells: List<String>) =
            cells.mapIndexed { i, cell -> cell.padEnd(widths[i]) }.joinToString("  ").trimEnd()
        return (listOf(header, line(columns)) + rows.map(::line)).joinToString("\n")
    }

    private fun Number?.text(): String = this?.toString() ?: "-"

    private fun Number.roundToText(decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", toDouble())

    private const val BYTES_PER_MB = 1L shl 20
}
