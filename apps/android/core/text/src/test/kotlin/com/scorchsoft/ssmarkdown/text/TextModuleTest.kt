package com.scorchsoft.ssmarkdown.text

import org.junit.Assert.assertEquals
import org.junit.Test

class TextModuleTest {
    @Test
    fun `module is wired into the build`() {
        assertEquals(":core:text", TextModule.PATH)
    }
}
