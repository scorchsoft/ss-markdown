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
     * Estimated cost of opening a file of [fileBytes]: decoded size × 2
     * (UTF-16), plus the line index and caches (about half again), plus a
     * fixed safety margin.
     */
    fun estimateOpenCost(fileBytes: Long): Long = fileBytes * 2 + fileBytes / 2 + SAFETY_MARGIN_BYTES

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

    /** The refusal for a file of [fileBytes], also used when a load runs out of memory part way. */
    fun decline(fileBytes: Long): Admission.Declined =
        Admission.Declined(fileBytes, estimateOpenCost(fileBytes.coerceAtLeast(0)), heapCeilingBytes())

    /** True when free heap is under 15% of the ceiling or the system reports low memory. */
    fun underPressure(): Boolean =
        freeHeapBytes() * PERCENT < heapCeilingBytes() * PRESSURE_PERCENT || source.system().lowMemory

    /**
     * Polled during loads, pastes and exports: sheds while under pressure.
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
         * drops the diagram cache; going to the background drops
         * everything rebuildable, because the process may die next.
         */
        fun stepsForTrimLevel(level: Int): Int =
            when {
                level >= TRIM_BACKGROUND -> ShedStep.entries.size
                level >= TRIM_UI_HIDDEN -> 1
                level >= TRIM_RUNNING_CRITICAL -> ShedStep.TokenizerCache.ordinal + 1
                level >= TRIM_RUNNING_LOW -> ShedStep.LineLayoutCaches.ordinal + 1
                level >= TRIM_RUNNING_MODERATE -> ShedStep.DiagramCache.ordinal + 1
                else -> 0
            }
    }
}
