package com.scorchsoft.ssmarkdown.files.memory

import com.scorchsoft.ssmarkdown.files.fakes.FakeMemorySource
import com.scorchsoft.ssmarkdown.files.fakes.MB
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class MemoryGovernorTest {
    private val source = FakeMemorySource(maxHeap = 256 * MB, usedHeap = 56 * MB, memoryClassMegabytes = 256)
    private val governor = MemoryGovernor(source)

    // Free heap is 200 MB, so the normal line is 80 MB of cost and the lean line 140 MB.
    private fun fileCosting(cost: Long) = (cost - MemoryGovernor.SAFETY_MARGIN_BYTES) * 2 / 13

    @Test
    fun `cost counts the text, index, tokenizers, a transient copy and the margin (PERF-9, DS-19)`() {
        // Text x2, index x0.5, two tokenizers x2, one String copy for a recovery or conflict x2.
        assertEquals(10 * MB * 13 / 2 + MemoryGovernor.SAFETY_MARGIN_BYTES, governor.estimateOpenCost(10 * MB))
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
        val declined = governor.admit(40 * MB) as Admission.Declined

        assertEquals(40 * MB, declined.fileBytes)
        assertEquals(governor.estimateOpenCost(40 * MB), declined.neededBytes)
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
                if (it == ShedStep.TokenizerCache) source.usedHeap = 100 * MB
            }
        }
        source.usedHeap = 250 * MB // under 15% free

        assertEquals(listOf(ShedStep.LineLayoutCaches, ShedStep.TokenizerCache), governor.checkPressure())
        assertEquals(listOf(ShedStep.LineLayoutCaches, ShedStep.TokenizerCache), shed)
        assertTrue(governor.checkPressure().isEmpty())
    }

    @Test
    fun `trim levels set the minimum depth of the ladder`() {
        assertEquals(listOf(ShedStep.LineLayoutCaches), governor.onTrimMemory(MemoryGovernor.TRIM_UI_HIDDEN))
        assertEquals(ShedStep.entries, governor.onTrimMemory(MemoryGovernor.TRIM_BACKGROUND))
        assertEquals(2, MemoryGovernor.stepsForTrimLevel(MemoryGovernor.TRIM_RUNNING_CRITICAL))
        assertEquals(0, MemoryGovernor.stepsForTrimLevel(MemoryGovernor.TRIM_RUNNING_MODERATE))
    }

    @Test
    fun `every step on the ladder has something registered in the app that frees memory (PERF-G)`() {
        // The app registers one shedder per step (AppGraph.registerShedders); a step with none is a promise
        // the ladder cannot keep. Here: the enum holds exactly the three that free memory in this process.
        assertEquals(
            listOf(ShedStep.LineLayoutCaches, ShedStep.TokenizerCache, ShedStep.InactiveCleanBuffers),
            ShedStep.entries,
        )
    }

    @Test
    fun `a paste is admitted only while it fits the lean share of free heap (PERF-G)`() {
        // 200 MB free: the lean line is 140 MB, so 20M chars (140 MB at 7 bytes a char) is the most.
        assertTrue(governor.admitPaste(20_000_000))
        assertFalse(governor.admitPaste(21_000_000))
        assertTrue(governor.admitPaste(-1))
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
