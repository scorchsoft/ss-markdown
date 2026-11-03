package com.scorchsoft.ssmarkdown.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorOverlay
import com.scorchsoft.ssmarkdown.editor.EditorUiState
import com.scorchsoft.ssmarkdown.editor.MenuItem
import com.scorchsoft.ssmarkdown.editor.sheets.SectionMenuSheet
import com.scorchsoft.ssmarkdown.fixtures.Samples
import com.scorchsoft.ssmarkdown.navigator.NavigatorAction
import com.scorchsoft.ssmarkdown.settings.CopyFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Copy chooser (ux-spec "Top bar"): two rows, the last-used first, each
 * a 48 dp+ target that TalkBack reads by its title and purpose; long-press
 * Copy is named for what it repeats; ⋮ offers "Copy as…"; the section menu
 * offers the same two choices in the same order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class CopyChooserTest {
    @get:Rule val rule = createComposeRule()

    private val actions = ArrayList<EditorAction>()

    private fun show(state: EditorUiState) {
        rule.setContent { Themed(dark = false) { EditorFor(state) { actions += it } } }
    }

    private fun top(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot().top

    @Test
    fun `both choices show, each with what it is for, at least 48 dp tall`() {
        show(Samples.saved.copy(overlay = EditorOverlay.CopyChooser(CopyFormat.Markdown)))
        rule.onNodeWithText("Copy Markdown").assertIsDisplayed()
        rule.onNodeWithText("The source, exactly as written").assertIsDisplayed()
        rule.onNodeWithText("Copy formatted text").assertIsDisplayed()
        rule.onNodeWithText("For Gmail, Google Docs, Word…").assertIsDisplayed()
        rule.onNodeWithTag("copy-markdown").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("copy-formatted").assertHeightIsAtLeast(48.dp)
        // TalkBack reads the row as one item: its title and its purpose together.
        val row = rule.onNodeWithTag("copy-formatted").fetchSemanticsNode()
        assertTrue(row.config.contains(SemanticsActions.OnClick))
    }

    @Test
    fun `the last-used choice is listed first`() {
        show(Samples.saved.copy(overlay = EditorOverlay.CopyChooser(CopyFormat.Formatted)))
        assertTrue(top("copy-formatted") < top("copy-markdown"))
    }

    @Test
    fun `Markdown first by default`() {
        show(Samples.saved.copy(overlay = EditorOverlay.CopyChooser(CopyFormat.Markdown)))
        assertTrue(top("copy-markdown") < top("copy-formatted"))
    }

    @Test
    fun `a row copies as its format`() {
        show(Samples.saved.copy(overlay = EditorOverlay.CopyChooser(CopyFormat.Markdown)))
        rule.onNodeWithText("Copy formatted text").performClick()
        assertEquals(EditorAction.CopyAs(CopyFormat.Formatted), actions.last())
    }

    @Test
    fun `Copy opens the chooser, and its long-press is named for the choice it repeats`() {
        show(Samples.saved.copy(copyFormat = CopyFormat.Formatted))
        rule.onNodeWithContentDescription("Copy all").performClick()
        assertEquals(EditorAction.CopyTapped, actions.last())
        val longClick =
            rule
                .onNodeWithContentDescription("Copy all")
                .fetchSemanticsNode()
                .config[SemanticsActions.OnLongClick]
        assertEquals("Copy formatted text again", longClick.label)
    }

    @Test
    fun `the menu offers Copy as…`() {
        show(Samples.saved.copy(overlay = EditorOverlay.Menu))
        rule.onNodeWithText("Copy as…").performClick()
        assertEquals(EditorAction.ChooseMenuItem(MenuItem.CopyAs), actions.last())
        assertEquals(0, rule.onAllNodesWithText("Copy as rich text").fetchSemanticsNodes().size)
    }

    @Test
    fun `the section menu offers both copies, the last-used first`() {
        val menu = EditorOverlay.SectionMenu(4, "Installing", 3, null, copyFirst = CopyFormat.Formatted)
        rule.setContent { Themed(dark = false) { SectionMenuSheet(menu) { actions += it } } }
        val formatted = rule.onNodeWithText("Copy section as formatted text").getUnclippedBoundsInRoot().top
        val markdown = rule.onNodeWithText("Copy section as Markdown").getUnclippedBoundsInRoot().top
        assertTrue(formatted < markdown)
        rule.onNodeWithText("Copy section as formatted text").performClick()
        assertEquals(EditorAction.Navigator(NavigatorAction.CopySection(4, CopyFormat.Formatted)), actions.last())
    }
}
