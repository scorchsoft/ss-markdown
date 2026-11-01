package com.scorchsoft.ssmarkdown.files.memory

import com.scorchsoft.ssmarkdown.files.fakes.FakeMemorySource
import com.scorchsoft.ssmarkdown.files.fakes.MB
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class MemoryGovernorTest {
    private val source = FakeMemorySource(maxHeap = 256 * MB, usedHeap = 56 * MB, memoryClassMegabytes = 256)
    private val governor = MemoryGovernor(source)

    // Free heap is 200 MB, so the normal line is 80 MB of cost and the lean line 140 MB.
    private fun fileCosting(cost: Long) = (cost - MemoryGovernor.SAFETY_MARGIN_BYTES) * 2 / 5

    @Test
    fun `cost is twice the size plus index, caches and margin`() {
        assertEquals(10 * MB * 5 / 2 + MemoryGovernor.SAFETY_MARGIN_BYTES, governor.estimateOpenCost(10 * MB))
    }

    @Test
    fun `within 40 percent of free heap opens normally`() {
        assertEquals(Budget.Normal, (governor.admit(fileCosting(80 * MB)) as Admission.Admitted).budget)
    }

    @Test
    fun `within 70 percent opens lean`() {
        assertEquals(Budget.Lean, (governor.admit(fileCosting(80 * MB + 1024)) as Admission.Admitted).budget)
        assertEquals(Budget.Lean, (governor.admit(fileCosting(140 * MB)) as Admission.Admitted).budget)
    }

    @Test
    fun `beyond 70 percent is declined with the numbers for the message`() {
        val declined = governor.admit(180 * MB) as Admission.Declined

        assertEquals(180 * MB, declined.fileBytes)
        assertEquals(governor.estimateOpenCost(180 * MB), declined.neededBytes)
        assertEquals(256 * MB, declined.allowedBytes)
    }

    @Test
    fun `the memory class caps the ceiling because we never ask for largeHeap`() {
        source.memoryClassMegabytes = 128
        assertEquals(128 * MB, governor.heapCeilingBytes())
    }

    @Test
    fun `a low-RAM device starts lean`() {
        source.isLowRamDevice = true
        assertEquals(Budget.Lean, (governor.admit(1024) as Admission.Admitted).budget)
    }

    @Test
    fun `pressure sheds in ladder order until relieved`() {
        val shed = mutableListOf<ShedStep>()
        ShedStep.entries.forEach { step ->
            governor.register(step) {
                shed += it
                if (it == ShedStep.LineLayoutCaches) source.usedHeap = 100 * MB
            }
        }
        source.usedHeap = 250 * MB // under 15% free

        assertEquals(listOf(ShedStep.DiagramCache, ShedStep.LineLayoutCaches), governor.checkPressure())
        assertEquals(listOf(ShedStep.DiagramCache, ShedStep.LineLayoutCaches), shed)
        assertTrue(governor.checkPressure().isEmpty())
    }

    @Test
    fun `trim levels set the minimum depth of the ladder`() {
        assertEquals(listOf(ShedStep.DiagramCache), governor.onTrimMemory(MemoryGovernor.TRIM_UI_HIDDEN))
        assertEquals(ShedStep.entries, governor.onTrimMemory(MemoryGovernor.TRIM_BACKGROUND))
        assertEquals(3, MemoryGovernor.stepsForTrimLevel(MemoryGovernor.TRIM_RUNNING_CRITICAL))
    }

    @Test
    fun `system low memory counts as pressure`() {
        source.lowMemory = true
        assertTrue(governor.underPressure())
    }

    @Test
    fun `a load that runs out of memory part way stops cleanly`() {
        source.usedHeap = 250 * MB
        val input = GovernedInputStream(ByteArrayInputStream(ByteArray(64)), governor, pollEveryBytes = 16)
        val failure = runCatching { input.readBytes() }.exceptionOrNull()
        assertTrue(failure is IOException)
    }

    @Test
    fun `low storage is flagged under 50 MB`() {
        source.allocatable = 49 * MB
        assertTrue(governor.storageLow())
    }
}
