package com.scorchsoft.ssmarkdown.files.memory

/**
 * The Java heap as `Runtime` reports it.
 *
 * @property maxBytes `Runtime.maxMemory()`: the heap ceiling.
 * @property usedBytes `totalMemory() - freeMemory()`: what is live or not yet collected.
 */
data class HeapStats(
    val maxBytes: Long,
    val usedBytes: Long,
)

/**
 * System memory as `ActivityManager.getMemoryInfo()` reports it.
 *
 * @property availableBytes `availMem`.
 * @property thresholdBytes `threshold`: below it the system starts killing.
 * @property lowMemory `lowMemory`.
 */
data class SystemMemory(
    val availableBytes: Long,
    val thresholdBytes: Long,
    val lowMemory: Boolean,
)

/**
 * Everything the [MemoryGovernor] reads, behind an interface so its maths
 * is tested with numbers rather than a device (docs/robustness.md Part 2a).
 * [AndroidMemorySource] is the real one.
 */
interface MemorySource {
    /** The heap right now. */
    fun heap(): HeapStats

    /** The device right now. */
    fun system(): SystemMemory

    /** `ActivityManager.isLowRamDevice()`: start in the lean budget. */
    val isLowRamDevice: Boolean

    /** `ActivityManager.getMemoryClass()` in megabytes: the per-app budget, since we never ask for `largeHeap`. */
    val memoryClassMegabytes: Int

    /** `StorageManager.getAllocatableBytes()` for app storage: room for journals, history and backups. */
    fun allocatableStorageBytes(): Long
}
