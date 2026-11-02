package com.scorchsoft.ssmarkdown.bench

import android.app.Activity
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/**
 * Runs one engine on one corpus and measures it, in the order a user meets
 * the costs: open, scroll, type. Every failure becomes a [RunResult] with
 * a note rather than an exception, and the risky steps leave a pending
 * marker in [store] first, so even a process kill is reported.
 *
 * Main thread only (a coroutine on the main dispatcher); the engine's
 * background load is the one step that leaves it.
 */
class BenchRunner(
    private val activity: Activity,
    private val host: FrameLayout,
    private val store: ResultStore,
    private val status: (String) -> Unit,
) {
    private var current: Engine? = null

    /** Measures [kind] on [corpus]. The engine stays on screen afterwards, so it can be tried by hand. */
    suspend fun run(
        kind: EngineKind,
        corpus: Corpus,
    ): RunResult {
        releaseCurrent()
        status("${corpus.label}: preparing corpus…")
        val file = withContext(Dispatchers.IO) { corpus.file(activity.cacheDir) }
        settleHeap()
        val base = RunResult(kind.label, corpus.label)
        val engine = kind.create().also { current = it }
        status("${kind.label} · ${corpus.label}: loading…")
        val opened = open(engine, file, base)
        if (opened.note.isNotEmpty()) return finish(opened)
        val withHeap = opened.copy(heapMb = usedHeapMb())
        status("${kind.label} · ${corpus.label}: scrolling…")
        val scrolled = measureScroll(engine, withHeap)
        status("${kind.label} · ${corpus.label}: typing…")
        return finish(measureTyping(engine, scrolled))
    }

    /** Removes the engine on screen and lets the heap settle before the next run. */
    suspend fun releaseCurrent() {
        host.removeAllViews()
        current?.release()
        current = null
        settleHeap()
    }

    private fun finish(result: RunResult): RunResult {
        store.save(result)
        status("${result.engine} · ${result.corpus}: done")
        return result
    }

    // ------------------------------------------------------------------ open

    private suspend fun open(
        engine: Engine,
        file: File,
        base: RunResult,
    ): RunResult {
        val start = System.nanoTime()
        store.markPending(base.copy(note = "killed while loading (OOM or crash)"))
        val failure =
            try {
                val done =
                    withTimeoutOrNull(TIMEOUT_MILLIS) {
                        runInterruptible(Dispatchers.IO) { InterruptibleInput(file.inputStream()).use(engine::load) }
                        true
                    }
                if (done == null) "timeout >10 s loading" else null
            } catch (_: OutOfMemoryError) {
                "OOM while loading"
            } catch (e: java.io.IOException) {
                "load failed: ${e.message}"
            }
        return failure?.let { failed(engine, base, it) } ?: showFirstFrame(engine, base, start)
    }

    private suspend fun showFirstFrame(
        engine: Engine,
        base: RunResult,
        start: Long,
    ): RunResult {
        store.markPending(base.copy(note = "killed on the main thread (ANR, OOM or crash) while showing"))
        val mainStart = System.nanoTime()
        try {
            host.addView(
                engine.show(activity),
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        } catch (_: OutOfMemoryError) {
            return failed(engine, base, "OOM in show (main thread)")
        }
        val drawn = awaitFrameDrawn()
        store.clearPending()
        val mainMillis = TimeUnit.NANOSECONDS.toMillis(drawn - mainStart)
        val result = base.copy(openMillis = TimeUnit.NANOSECONDS.toMillis(drawn - start), mainMillis = mainMillis)
        // The main thread cannot be interrupted, so a slow setText is only reported after it returns.
        return if (mainMillis > TIMEOUT_MILLIS) result.copy(note = "timeout >10 s on main thread") else result
    }

    private suspend fun failed(
        engine: Engine,
        base: RunResult,
        note: String,
    ): RunResult {
        host.removeAllViews()
        engine.release()
        current = null
        settleHeap()
        return base.copy(note = note)
    }

    // ---------------------------------------------------------------- scroll

    private suspend fun measureScroll(
        engine: Engine,
        result: RunResult,
    ): RunResult {
        val recorder = FrameRecorder(activity.window)
        recorder.start()
        scriptedFling(engine.scrollTarget, FLING_MILLIS)
        val frames = recorder.stop()
        if (frames.isEmpty()) return result.copy(note = "no frames recorded while scrolling")
        return result.copy(
            frames = frames.size,
            framesOver16Percent = percentOver(frames, SIXTEEN_MS),
            framesOver32Percent = percentOver(frames, THIRTY_TWO_MS),
        )
    }

    // ---------------------------------------------------------------- typing

    private suspend fun measureTyping(
        engine: Engine,
        result: RunResult,
    ): RunResult {
        engine.placeCaretInMiddle()
        awaitFrameDrawn()
        val connection = engine.inputConnection() ?: return result.copy(note = "no InputConnection")
        val latencies = ArrayList<Long>(KEYSTROKES)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS)
        while (latencies.size < KEYSTROKES && System.nanoTime() < deadline) {
            val start = System.nanoTime()
            connection.commitText("x", 1)
            latencies += awaitFrameDrawn() - start
            // One keystroke per frame at most, as a fast typist would.
            delay(1)
        }
        val sorted = latencies.sorted()
        val note =
            if (latencies.size <
                KEYSTROKES
            ) {
                "typing stopped at ${latencies.size} keys after 10 s"
            } else {
                result.note
            }
        return result.copy(
            keyAverageMillis = sorted.average() / NANOS_PER_MILLI,
            keyP95Millis = sorted[(ceil(sorted.size * P95).toInt() - 1).coerceIn(0, sorted.size - 1)] / NANOS_PER_MILLI,
            keystrokes = latencies.size,
            note = note,
        )
    }

    // ------------------------------------------------------------------ heap

    private suspend fun settleHeap() {
        // Heap numbers mean nothing without a collection first, and runs must not inherit garbage.
        @Suppress("ExplicitGarbageCollectionCall")
        Runtime.getRuntime().gc()
        delay(GC_SETTLE_MILLIS)
    }

    private suspend fun usedHeapMb(): Long {
        settleHeap()
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_MB
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val FLING_MILLIS = 5_000L
        const val KEYSTROKES = 200
        const val SIXTEEN_MS = 16_000_000L
        const val THIRTY_TWO_MS = 32_000_000L
        const val P95 = 0.95
        const val NANOS_PER_MILLI = 1_000_000.0
        const val GC_SETTLE_MILLIS = 300L
        const val BYTES_PER_MB = 1L shl 20
    }
}

private const val PERCENT = 100.0

/** The share of [frames] (durations in ns) slower than [limitNanos], in percent. */
private fun percentOver(
    frames: LongArray,
    limitNanos: Long,
): Double = PERCENT * frames.count { it > limitNanos } / frames.size

/**
 * A stream that stops with [InterruptedIOException] once its thread is
 * interrupted, so a load that runs past the timeout is really abandoned
 * (and stops allocating) instead of running on in the background.
 */
private class InterruptibleInput(
    input: InputStream,
) : FilterInputStream(input) {
    override fun read(): Int {
        checkInterrupted()
        return super.read()
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        checkInterrupted()
        return super.read(b, off, len)
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("load timed out")
    }
}
