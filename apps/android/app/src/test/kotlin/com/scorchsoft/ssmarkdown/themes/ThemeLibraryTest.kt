package com.scorchsoft.ssmarkdown.themes

import com.scorchsoft.ssmarkdown.theme.BuiltInThemes
import com.scorchsoft.ssmarkdown.theme.Theme
import com.scorchsoft.ssmarkdown.theme.codec.ThemeCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * SEC-L1: saving a theme never silently replaces a different one that
 * shares its name. And the theme library never reads or writes a file on the caller's thread
 * (performance review PERF-C): with the I/O dispatcher held, nothing on
 * disk is seen or changed until it runs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThemeLibraryTest {
    @get:Rule val temp = TemporaryFolder()

    private val io = StandardTestDispatcher()
    private val scope = CoroutineScope(io)
    private val mine = BuiltInThemes.CLEAN.copy(id = "mine", name = "Mine", base = BuiltInThemes.CLEAN.id)

    @Test
    fun `custom themes are read on the I-O dispatcher, never by all() or byId()`() {
        val dir = File(temp.root, "themes").apply { mkdirs() }
        File(dir, "mine.json").writeText(ThemeCodec.encode(mine))
        val library = ThemeLibrary(dir, io, scope)
        // Before: all() inside a Compose remember read every theme file on the main thread.
        assertEquals(BuiltInThemes.ALL, library.all())
        io.scheduler.advanceUntilIdle()
        assertEquals(BuiltInThemes.ALL.size + 1, library.all().size)
        val read = library.all().last()
        assertEquals("Mine", library.byId(read.id).name)
    }

    @Test
    fun `save and delete happen on the I-O dispatcher`() {
        val dir = File(temp.root, "themes")
        val library = ThemeLibrary(dir, io, scope)
        var saved: Boolean? = null
        scope.launch { saved = library.save(mine) }
        assertFalse("nothing written on the caller's thread", File(dir, "mine.json").exists())
        io.scheduler.advanceUntilIdle()
        assertEquals(true, saved)
        assertTrue(File(dir, "mine.json").exists())
        assertEquals(
            "mine",
            library.themes.value
                ?.single()
                ?.id,
        )
        scope.launch { library.delete("mine") }
        assertTrue(File(dir, "mine.json").exists())
        io.scheduler.advanceUntilIdle()
        assertFalse(File(dir, "mine.json").exists())
        assertEquals(emptyList<Any>(), library.themes.value)
    }

    @Test
    fun `SEC-L1 a second theme with the same name is saved beside the first, renamed`() {
        val library = ThemeLibrary(temp.root, io, scope)
        val first = ThemeEditorReducer.start(ThemeEditorReducer.copyOf(BuiltInThemes.DEFAULT), savedId = null).theme
        // An imported theme, or another copy, with the same name: the same id.
        val second = first.copy(fonts = first.fonts.copy(body = "Inter"))
        var saved: Theme? = null
        scope.launch {
            library.save(library.withFreeName(first, ownId = null))
            saved = library.withFreeName(second, ownId = null).also { library.save(it) }
        }
        io.scheduler.advanceUntilIdle()

        assertNotEquals(first.id, saved?.id)
        assertEquals("${first.name} (2)", saved?.name)
        val reread = ThemeLibrary(temp.root, io, scope)
        io.scheduler.advanceUntilIdle()
        assertEquals(2, reread.all().size - BuiltInThemes.ALL.size)
    }

    @Test
    fun `SEC-L1 saving a theme over itself keeps its name`() {
        val library = ThemeLibrary(temp.root, io, scope)
        val theme = ThemeEditorReducer.start(ThemeEditorReducer.copyOf(BuiltInThemes.DEFAULT), savedId = null).theme
        var kept: Theme? = null
        scope.launch {
            library.save(theme)
            kept = library.withFreeName(theme, ownId = theme.id)
        }
        io.scheduler.advanceUntilIdle()

        assertEquals(theme, kept)
    }
}
