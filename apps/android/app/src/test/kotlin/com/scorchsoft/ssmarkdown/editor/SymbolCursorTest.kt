package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.ui.strip.CursorStep
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * edge-cases "Symbols-page arrows split an emoji": the left and right keys
 * step over a surrogate pair whole, so a symbol typed next never lands
 * between its halves (which no encoder can write back).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SymbolCursorTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `the arrows step over an emoji whole`() {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        f.vm.openDocument(f.file("e.md", "a" + EMOJI + "b\n"))
        val surface = f.vm.active?.surface ?: error("nothing open")
        surface.select(1, 1)
        f.vm.onAction(EditorAction.CursorStepped(CursorStep.Right))
        assertEquals(
            TextSelection.caret(3),
            f.vm.active
                ?.surface
                ?.selection,
        )
        f.vm.onAction(EditorAction.CursorStepped(CursorStep.Left))
        assertEquals(
            TextSelection.caret(1),
            f.vm.active
                ?.surface
                ?.selection,
        )
        f.vm.onAction(EditorAction.CursorStepped(CursorStep.Right))
        f.vm.onAction(EditorAction.CursorStepped(CursorStep.Right))
        assertEquals(
            TextSelection.caret(4),
            f.vm.active
                ?.surface
                ?.selection,
        )
    }

    private companion object {
        /** U+1F600, two UTF-16 chars. */
        val EMOJI = String(Character.toChars(0x1F600))
    }
}
