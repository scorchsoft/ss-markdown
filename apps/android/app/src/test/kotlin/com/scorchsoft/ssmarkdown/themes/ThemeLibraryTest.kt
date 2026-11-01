package com.scorchsoft.ssmarkdown.themes

import com.scorchsoft.ssmarkdown.theme.BuiltInThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** SEC-L1: saving a theme never silently replaces a different one that shares its name. */
class ThemeLibraryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `SEC-L1 a second theme with the same name is saved beside the first, renamed`() {
        val library = ThemeLibrary(temp.root)
        val first = ThemeEditorReducer.start(ThemeEditorReducer.copyOf(BuiltInThemes.DEFAULT), savedId = null).theme
        library.save(library.withFreeName(first, ownId = null))
        // An imported theme, or another copy, with the same name: the same id.
        val second = first.copy(fonts = first.fonts.copy(body = "Inter"))

        val saved = library.withFreeName(second, ownId = null)
        library.save(saved)

        assertNotEquals(first.id, saved.id)
        assertEquals("${first.name} (2)", saved.name)
        assertEquals(2, ThemeLibrary(temp.root).all().size - BuiltInThemes.ALL.size)
    }

    @Test
    fun `SEC-L1 saving a theme over itself keeps its name`() {
        val library = ThemeLibrary(temp.root)
        val theme = ThemeEditorReducer.start(ThemeEditorReducer.copyOf(BuiltInThemes.DEFAULT), savedId = null).theme
        library.save(theme)

        assertEquals(theme, library.withFreeName(theme, ownId = theme.id))
    }
}
