package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.folder.FolderAction
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.text.EditOp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * J7 "Tidy and Save all": every unsaved file is saved except those that
 * changed in another app, which are skipped, named with their reason, and
 * one tap away from review; nothing anyone else wrote is overwritten.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SaveAllTest {
    @get:Rule val temp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    private fun EditorFixture.openAndType(
        name: String,
        text: String,
        typed: String,
    ) {
        vm.openDocument(file(name, text))
        val doc = checkNotNull(vm.active)
        doc.surface.apply(listOf(EditOp(0, "", typed)), TextSelection.caret(typed.length))
    }

    @Test
    fun `save all saves the rest, skips and lists the conflicted file, and Review opens it`() {
        val f = EditorFixture(temp.root, dispatcher)
        f.openAndType("a.md", "alpha\n", "A ")
        f.openAndType("b.md", "beta\n", "B ")
        f.openAndType("c.md", "gamma\n", "C ")
        f.externalWrite("b.md", "beta from another app\n")

        f.vm.onAction(EditorAction.Folder(FolderAction.SaveAll))

        assertEquals("A alpha\n", f.disk("a.md"))
        assertEquals("C gamma\n", f.disk("c.md"))
        assertEquals("the other app's text is kept", "beta from another app\n", f.disk("b.md"))
        val banner = f.vm.uiState.value.banner
        assertEquals(
            EditorBanner.SaveAllResult(
                saved = 2,
                skipped = listOf(SkippedSave(EditorFixture.SCHEME + "b.md", "b.md", SaveProblem.ChangedOutside)),
            ),
            banner,
        )
        assertTrue(EditorEvent.ErrorTick in f.events)

        // Review goes to the skipped file, not to whatever file happens to be on screen.
        f.vm.onAction(EditorAction.ReviewConflict)
        assertEquals(
            EditorFixture.SCHEME + "b.md",
            f.vm.active
                ?.open
                ?.uri
                ?.value,
        )
        assertTrue(f.vm.uiState.value.overlay is EditorOverlay.Conflict)
    }
}
