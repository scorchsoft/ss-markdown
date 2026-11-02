package com.scorchsoft.ssmarkdown.core.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * On the host JVM android.jar's stubs throw on any `android.os.Trace`
 * call, so the sections must be off there, never a crash in every test
 * that types a key.
 */
class PerfTraceTest {
    @Test
    fun onTheHostJvmTracingIsOffAndSectionsStillRun() {
        assertFalse(PerfTrace.isTracing)
        assertEquals(42, PerfTrace.section(PerfTrace.COMMIT) { 42 })
        PerfTrace.beginAsync(PerfTrace.OPEN, 1)
        PerfTrace.endAsync(PerfTrace.OPEN, 1)
    }

    @Test(expected = IllegalStateException::class)
    fun aFailingBlockStillFails() {
        PerfTrace.section(PerfTrace.COMMIT) { error("boom") }
    }
}
