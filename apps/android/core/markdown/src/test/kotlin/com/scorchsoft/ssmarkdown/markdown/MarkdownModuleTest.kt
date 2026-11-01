package com.scorchsoft.ssmarkdown.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownModuleTest {
    @Test
    fun `module is wired into the build`() {
        assertEquals(":core:markdown", MarkdownModule.PATH)
    }
}
