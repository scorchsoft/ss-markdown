package com.scorchsoft.ssmarkdown.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeModuleTest {
    @Test
    fun `module is wired into the build`() {
        assertEquals(":core:theme", ThemeModule.PATH)
    }
}
