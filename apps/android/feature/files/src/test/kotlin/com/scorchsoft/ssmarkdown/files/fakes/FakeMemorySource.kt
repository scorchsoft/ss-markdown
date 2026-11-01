package com.scorchsoft.ssmarkdown.files.fakes

import com.scorchsoft.ssmarkdown.files.memory.HeapStats
import com.scorchsoft.ssmarkdown.files.memory.MemorySource
import com.scorchsoft.ssmarkdown.files.memory.SystemMemory

const val MB: Long = 1024L * 1024

/** Numbers instead of a phone. */
class FakeMemorySource(
    var maxHeap: Long = 512 * MB,
    var usedHeap: Long = 40 * MB,
    var lowMemory: Boolean = false,
    override var isLowRamDevice: Boolean = false,
    override var memoryClassMegabytes: Int = 512,
    var allocatable: Long = 4096 * MB,
) : MemorySource {
    override fun heap() = HeapStats(maxHeap, usedHeap)

    override fun system() = SystemMemory(availableBytes = 2048 * MB, thresholdBytes = 128 * MB, lowMemory = lowMemory)

    override fun allocatableStorageBytes() = allocatable
}
