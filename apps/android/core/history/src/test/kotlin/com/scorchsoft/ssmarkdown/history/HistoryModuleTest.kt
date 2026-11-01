package com.scorchsoft.ssmarkdown.history

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryModuleTest {
    @Test
    fun `module is wired into the build`() {
        assertEquals(":core:history", HistoryModule.PATH)
    }
}
