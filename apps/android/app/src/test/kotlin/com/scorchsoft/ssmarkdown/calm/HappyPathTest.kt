package com.scorchsoft.ssmarkdown.calm

import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import com.scorchsoft.ssmarkdown.editor.EditorEvent
import com.scorchsoft.ssmarkdown.editor.SaveButton
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.fixtures.LiveEditor
import com.scorchsoft.ssmarkdown.fixtures.TapCounter
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.text.EditOp
import com.scorchsoft.ssmarkdown.ui.Themed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Rubric #2, "no dialogs on the happy path", walked through the real view
 * model and the real screen: open a file, edit it, Save, switch through
 * Preview, Split and back to Code, and Copy. After every step nothing
 * modal is up: no dialog or popup window, and no overlay in the state.
 * (docs/quality-bar.md anti-goal: "No modal dialog on the happy path".)
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class HappyPathTest {
    @get:Rule val rule = createComposeRule()

    @get:Rule val temp = TemporaryFolder()

    private fun assertCalm(
        f: EditorFixture,
        step: String,
    ) {
        rule.waitForIdle()
        assertNull("$step left an overlay up", f.vm.uiState.value.overlay)
        assertTrue("$step opened a dialog", rule.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
        assertTrue("$step opened a popup", rule.onAllNodes(isPopup()).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `open, edit, save, switch mode and copy without one dialog or sheet`() {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        rule.setContent { Themed(dark = false) { LiveEditor(f) } }
        val taps = TapCounter(rule)

        f.vm.openDocument(f.file("notes.md", "# Notes\n\nHello\n"))
        assertCalm(f, "Opening")

        rule.runOnIdle {
            val doc = f.vm.active ?: error("nothing open")
            doc.surface.apply(listOf(EditOp(14, "", " world")), TextSelection.caret(20))
        }
        assertEquals(SaveButton.Save, f.vm.uiState.value.save.button)
        assertCalm(f, "Editing")

        taps.tap("Save")
        assertEquals("# Notes\n\nHello world\n", f.disk("notes.md"))
        assertEquals(SaveButton.AllSaved, f.vm.uiState.value.save.button)
        assertCalm(f, "Saving")

        for (mode in listOf(ViewMode.Preview, ViewMode.Split, ViewMode.Code)) {
            taps.tap(mode.name)
            assertEquals(mode, f.vm.uiState.value.mode)
            assertCalm(f, "Switching to $mode")
        }

        taps.tap("Copy all")
        assertTrue(f.events.any { it is EditorEvent.CopyText })
        assertCalm(f, "Copying")

        // Save, three mode switches and Copy: one tap each, nothing in between.
        assertEquals(5, taps.taps)
    }
}
