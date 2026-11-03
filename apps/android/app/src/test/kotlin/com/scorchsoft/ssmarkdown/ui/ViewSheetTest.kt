package com.scorchsoft.ssmarkdown.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorOverlay
import com.scorchsoft.ssmarkdown.editor.EditorReducer
import com.scorchsoft.ssmarkdown.editor.EditorUiState
import com.scorchsoft.ssmarkdown.editor.MenuItem
import com.scorchsoft.ssmarkdown.editor.Pane
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.editor.sheets.Emphasised
import com.scorchsoft.ssmarkdown.fixtures.Samples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The View sheet (ux-spec "View sheet"), found from ⋮'s "Aa" icon, ⋮ → View
 * or a long-press on a mode: one fixed layout (Lines, Appearance, Text
 * size, Preview theme) whatever the entry, the long-pressed pane's Lines
 * row emphasised, the mode left as it was, and no scrolling on a 412 × 915
 * phone at 1× font.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ViewSheetTest {
    @get:Rule val rule = createComposeRule()

    private val actions = ArrayList<EditorAction>()

    private fun show(
        state: EditorUiState,
        fontScale: Float = 1f,
    ) {
        rule.setContent { Themed(dark = false, fontScale = fontScale) { EditorFor(state) { actions += it } } }
    }

    private fun top(text: String) = rule.onNodeWithText(text).getUnclippedBoundsInRoot().top

    private fun emphasised(label: String): Boolean =
        rule
            .onAllNodesWithText(label)
            .fetchSemanticsNodes()
            .any { node ->
                generateSequence(node) { it.parent }.any { it.config.getOrElseNullable(Emphasised) { null } == true }
            }

    @Test
    fun `the layout is the same from every entry point`() {
        val sections = listOf("View", "Lines", "Appearance", "Text size · this file")
        var highlight: Pane? by mutableStateOf(null)
        rule.setContent {
            Themed(dark = false) {
                EditorFor(Samples.saved.copy(overlay = EditorOverlay.Display(highlight))) { actions += it }
            }
        }
        for (entry in listOf(null, Pane.Code, Pane.Preview)) {
            highlight = entry
            rule.waitForIdle()
            val tops = sections.map(::top)
            assertEquals("$entry: $tops", tops.sorted(), tops)
            // Lines: Code's row above Preview's, both before Appearance.
            assertTrue(top("Fluid") > top("Wrap"))
            assertTrue(top("Fluid") < top("Appearance"))
        }
    }

    @Test
    fun `nothing is emphasised from the menu`() {
        show(Samples.saved.copy(overlay = EditorOverlay.Display()))
        assertFalse(emphasised("Wrap"))
        assertFalse(emphasised("Fluid"))
    }

    @Test
    fun `a long-press on Code emphasises Code's row, and on Preview Preview's`() {
        show(Samples.saved.copy(overlay = EditorOverlay.Display(Pane.Code)))
        assertTrue(emphasised("Wrap"))
        assertFalse(emphasised("Fluid"))
    }

    @Test
    fun `Preview's row is emphasised for Preview`() {
        show(Samples.saved.copy(overlay = EditorOverlay.Display(Pane.Preview)))
        assertTrue(emphasised("Fluid"))
        assertFalse(emphasised("Wrap"))
    }

    @Test
    fun `it fits a 412 x 915 phone at 1x font without scrolling`() {
        show(Samples.saved.copy(overlay = EditorOverlay.Display()))
        val scrollers =
            rule
                .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
                .fetchSemanticsNodes()
                .map { it.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() }
        assertTrue("the sheet scrolls: $scrollers", scrollers.all { it == 0f })
    }

    @Test
    fun `the menu's Aa icon opens the sheet, and the View row says what is in it`() {
        show(Samples.saved.copy(overlay = EditorOverlay.Menu))
        rule
            .onNodeWithContentDescription("View options")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(EditorAction.OpenView(null), actions.last())
        assertEquals(EditorOverlay.Display(null), EditorReducer.reduce(Samples.saved, actions.last()).overlay)
        rule.onNodeWithText("Wrap · A4 page · theme · size").assertIsDisplayed()
        rule.onNodeWithText("View").performClick()
        assertEquals(EditorAction.ChooseMenuItem(MenuItem.Display), actions.last())
    }

    @Test
    fun `long-press a mode opens the sheet for it and leaves the mode alone`() {
        show(Samples.saved)
        val expected = mapOf("Code" to Pane.Code, "Split" to null, "Preview" to Pane.Preview)
        for ((label, pane) in expected) {
            actions.clear()
            rule.onNodeWithText(label).performTouchInput { longClick() }
            assertEquals(label, listOf(EditorAction.OpenView(pane)), actions)
            val state = EditorReducer.reduce(Samples.saved, actions.single())
            assertEquals(ViewMode.Code, state.mode)
            assertEquals(EditorOverlay.Display(pane), state.overlay)
        }
    }

    @Test
    fun `TalkBack offers each mode's long-press as View options`() {
        show(Samples.saved)
        for (label in listOf("Code", "Split", "Preview")) {
            val node =
                rule
                    .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
                    .fetchSemanticsNodes()
                    .first { n ->
                        n.config.getOrElseNullable(SemanticsProperties.Text) { null }?.any { it.text == label } == true
                    }
            assertEquals("View options for $label", node.config[SemanticsActions.OnLongClick].label)
        }
    }

    @Test
    fun `the icon-only bar at large fonts has the same long-press`() {
        show(Samples.saved, fontScale = 2f)
        rule.onNodeWithContentDescription("Preview").performTouchInput { longClick() }
        assertEquals(EditorAction.OpenView(Pane.Preview), actions.last())
        val node = rule.onNodeWithContentDescription("Preview").fetchSemanticsNode()
        assertEquals("View options for Preview", node.config[SemanticsActions.OnLongClick].label)
    }
}
