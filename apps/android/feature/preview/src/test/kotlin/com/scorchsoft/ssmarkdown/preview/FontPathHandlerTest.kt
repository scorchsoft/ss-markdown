package com.scorchsoft.ssmarkdown.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The `/fonts/` handler serves font files from the store and nothing outside it. */
class FontPathHandlerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `only files under the root resolve`() {
        val root = tmp.newFolder("fonts")
        val font = root.resolve("lora/Lora-400.ttf").apply { parentFile?.mkdirs() }.apply { writeText("x") }
        tmp.newFile("secret.ttf").writeText("s")
        assertEquals(font.canonicalFile, FontPathHandler.fileUnder(root, "lora/Lora-400.ttf"))
        assertNull(FontPathHandler.fileUnder(root, "../secret.ttf"))
        assertNull(FontPathHandler.fileUnder(root, "lora/../../secret.ttf"))
        assertNull(FontPathHandler.fileUnder(root, "missing.ttf"))
        assertNull(FontPathHandler.fileUnder(root, "lora"))
    }
}
