package com.scorchsoft.ssmarkdown.files.memory

/**
 * Owns the memory budget (docs/robustness.md Part 2a): admission control
 * before a load, pressure checks during loads and pastes, and the shed
 * ladder when memory runs short.
 *
 * Since API 34 only `TRIM_MEMORY_UI_HIDDEN` and `TRIM_MEMORY_BACKGROUND`
 * are delivered, so callbacks alone are not enough: long operations call
 * [checkPressure] as they go ([GovernedInputStream] does it for loads).
 *
 * Not thread-safe for registration; [checkPressure] and [admit] only read.
 */
@Suppress("TooManyFunctions") // Admission, pressure, trim and storage: the whole Part 2a contract, each member small.
class MemoryGovernor(
    private val source: MemorySource,
) {
    private val shedders = ShedStep.entries.associateWith { mutableListOf<Shedder>() }

    /** The heap ceiling: `Runtime.maxMemory()`, capped by the memory class (we never set `largeHeap`). */
    fun heapCeilingBytes(): Long {
        val classBytes = source.memoryClassMegabytes.toLong() * MEGABYTE
        val max = source.heap().maxBytes
        return if (classBytes > 0) minOf(max, classBytes) else max
    }

    /** Free heap under the ceiling right now. */
    fun freeHeapBytes(): Long = (heapCeilingBytes() - source.heap().usedBytes).coerceAtLeast(0)

    /**
     * Estimated cost of opening a file of [fileBytes], counting everything
     * that grows with the file (performance review PERF-9, DS-19):
     *
     * - the text, decoded to UTF-16: × 2;
     * - the line index and the bounded caches: × ½;
     * - the two tokenizers' per-line block states (the outline's and the
     *   Code pane's): × 2, measured at 41 MB for a 20 MB file;
     * - one transient full copy of the text, as a `String`, built while the
     *   buffer is live: the replay of recovered edits on open, or a
     *   conflict's copies of the base and the file on disk: × 2;
     *
     * plus a fixed safety margin.
     */
    fun estimateOpenCost(fileBytes: Long): Long =
        fileBytes * TEXT_PER_BYTE +
            fileBytes / INDEX_DIVISOR +
            fileBytes * TOKENIZERS_PER_BYTE +
            fileBytes * TRANSIENT_COPY_PER_BYTE +
            SAFETY_MARGIN_BYTES

    /**
     * May a file of [fileBytes] open? Within 40% of free heap it opens
     * normally (lean on a low-RAM device), within 70% it opens lean, and
     * beyond that it is declined with the numbers to explain why. Open
     * documents already count, because they are in the heap's used bytes.
     */
    fun admit(fileBytes: Long): Admission {
        val cost = estimateOpenCost(fileBytes.coerceAtLeast(0))
        val free = freeHeapBytes()
        return when {
            cost <= free * NORMAL_SHARE_PERCENT / PERCENT -> {
                Admission.Admitted(if (source.isLowRamDevice) Budget.Lean else Budget.Normal, cost)
            }

            cost <= free * LEAN_SHARE_PERCENT / PERCENT -> {
                Admission.Admitted(Budget.Lean, cost)
            }

            else -> {
                decline(fileBytes)
            }
        }
    }

    /**
     * Estimated cost of pasting [chars]: the clip as a `String`, its
     * normalised copy, the piece tree's add buffer and the undo step's
     * reference to it (two bytes a char each, the last two shared), plus
     * the line index and tokenizer states it adds.
     */
    fun estimatePasteCost(chars: Long): Long = chars * PASTE_BYTES_PER_CHAR

    /**
     * May a paste of [chars] go in (PERF-G)? Yes while it fits within the
     * same 70% of free heap that admits a file lean; no beyond.
     */
    fun admitPaste(chars: Long): Boolean =
        estimatePasteCost(chars.coerceAtLeast(0)) <= freeHeapBytes() * LEAN_SHARE_PERCENT / PERCENT

    /** The refusal for a file of [fileBytes], also used when a load runs out of memory part way. */
    fun decline(fileBytes: Long): Admission.Declined =
        Admission.Declined(fileBytes, estimateOpenCost(fileBytes.coerceAtLeast(0)), heapCeilingBytes())

    /** True when free heap is under 15% of the ceiling or the system reports low memory. */
    fun underPressure(): Boolean =
        freeHeapBytes() * PERCENT < heapCeilingBytes() * PRESSURE_PERCENT || source.system().lowMemory

    /**
     * Polled during loads and large pastes: sheds while under pressure.
     * Returns the steps shed (empty when there was no pressure).
     */
    fun checkPressure(): List<ShedStep> = if (underPressure()) shedUntilRelieved(minimumSteps = 1) else emptyList()

    /**
     * `ComponentCallbacks2.onTrimMemory`. The level sets how far down the
     * ladder to go at least ([stepsForTrimLevel]); shedding then continues
     * while pressure lasts. Returns the steps shed.
     */
    fun onTrimMemory(level: Int): List<ShedStep> = shedUntilRelieved(stepsForTrimLevel(level))

    /** Registers [shedder] for [step]. */
    fun register(
        step: ShedStep,
        shedder: Shedder,
    ) {
        shedders.getValue(step) += shedder
    }

    /** Free app storage, for the journal and history low-space warnings. */
    fun allocatableStorageBytes(): Long = source.allocatableStorageBytes()

    /** True below the "Less than 50 MB free — recovery copies may stop" line. */
    fun storageLow(): Boolean = source.allocatableStorageBytes() < LOW_STORAGE_BYTES

    private fun shedUntilRelieved(minimumSteps: Int): List<ShedStep> {
        val shed = ArrayList<ShedStep>()
        for (step in ShedStep.entries) {
            if (shed.size >= minimumSteps && !underPressure()) break
            shedders.getValue(step).forEach { it.shed(step) }
            shed += step
        }
        return shed
    }

    /** Tunables and the platform's trim levels, named so tests can use them. */
    companion object {
        private const val MEGABYTE = 1024L * 1024
        private const val PERCENT = 100
        private const val NORMAL_SHARE_PERCENT = 40
        private const val LEAN_SHARE_PERCENT = 70
        private const val PRESSURE_PERCENT = 15

        /** UTF-16: two bytes a char, and a Markdown file's bytes are mostly one char each. */
        private const val TEXT_PER_BYTE = 2L

        /** The line index and bounded caches: half the file again. */
        private const val INDEX_DIVISOR = 2L

        /** Two tokenizers' block states, about ten bytes a line each, plus the outline (host-measured). */
        private const val TOKENIZERS_PER_BYTE = 2L

        /** One full `String` copy of the text during a recovery replay or a conflict (DS-19). */
        private const val TRANSIENT_COPY_PER_BYTE = 2L

        /** Clip `String`, normalised copy and add buffer (two bytes a char each), plus index and states. */
        private const val PASTE_BYTES_PER_CHAR = 7L

        /** Fixed headroom added to every open estimate. */
        const val SAFETY_MARGIN_BYTES: Long = 8 * MEGABYTE

        /** The journal/history warning line. */
        const val LOW_STORAGE_BYTES: Long = 50 * MEGABYTE

        /** `TRIM_MEMORY_RUNNING_MODERATE`. */
        const val TRIM_RUNNING_MODERATE: Int = 5

        /** `TRIM_MEMORY_RUNNING_LOW`. */
        const val TRIM_RUNNING_LOW: Int = 10

        /** `TRIM_MEMORY_RUNNING_CRITICAL`. */
        const val TRIM_RUNNING_CRITICAL: Int = 15

        /** `TRIM_MEMORY_UI_HIDDEN`. */
        const val TRIM_UI_HIDDEN: Int = 20

        /** `TRIM_MEMORY_BACKGROUND`. */
        const val TRIM_BACKGROUND: Int = 40

        /**
         * How many ladder steps a trim level demands at least. Hidden UI
         * and a low-memory warning drop the line layouts (cheap to redraw);
         * a critical one the tokens too; going to the background drops
         * everything rebuildable, because the process may die next. A
         * moderate warning sheds only if the heap itself is short.
         */
        fun stepsForTrimLevel(level: Int): Int =
            when {
                level >= TRIM_BACKGROUND -> ShedStep.entries.size
                level >= TRIM_UI_HIDDEN -> ShedStep.LineLayoutCaches.ordinal + 1
                level >= TRIM_RUNNING_CRITICAL -> ShedStep.TokenizerCache.ordinal + 1
                level >= TRIM_RUNNING_LOW -> ShedStep.LineLayoutCaches.ordinal + 1
                else -> 0
            }
    }
}
