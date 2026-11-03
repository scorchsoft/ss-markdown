package com.scorchsoft.ssmarkdown.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.fixtures.Samples
import com.scorchsoft.ssmarkdown.navigator.NavigatorAction
import com.scorchsoft.ssmarkdown.navigator.NavigatorUiState
import com.scorchsoft.ssmarkdown.navigator.OutlineRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The Navigator is a deliberate surface (ux-spec "Navigator"): on a phone
 * it fills the pane area down to the mode bar, a short outline leaves the
 * sheet's own background below it, and it always has a visible way out.
 * It used to stop at its content's height over the editor, which read as a
 * layout fault, and had no close control.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class NavigatorSurfaceTest {
    @get:Rule val rule = createComposeRule()

    @get:Rule val temp = TemporaryFolder()

    private val actions = ArrayList<EditorAction>()

    /** Two headings: far shorter than the screen, the case that showed a half-height sheet. */
    private val shortOutline =
        NavigatorUiState(outline = listOf(OutlineRow(0, 1, "Team handbook"), OutlineRow(9, 2, "Code review")))

    private fun show(widthDp: Int) {
        RuntimeEnvironment.setQualifiers("w${widthDp}dp-h${HEIGHT_DP}dp-420dpi")
        rule.setContent {
            Themed(dark = false) {
                EditorFor(Samples.section.copy(navigator = shortOutline)) {
                    actions +=
                        it
                }
            }
        }
    }

    private val navigator = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Navigator")

    private fun sheetReachesTheModeBar(widthDp: Int) {
        show(widthDp)
        val sheet = rule.onNode(navigator).getUnclippedBoundsInRoot()
        // The mode bar's Code segment: the sheet ends at the bar that holds it.
        val modeBar = rule.onNodeWithText("Code").getUnclippedBoundsInRoot()
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        assertTrue("the sheet stops above the mode bar at $widthDp dp", sheet.bottom <= modeBar.top)
        assertTrue(
            "the sheet reaches the mode bar at $widthDp dp (${sheet.bottom} vs ${modeBar.top})",
            modeBar.top - sheet.bottom < MODE_BAR_PADDING,
        )
        assertEquals("it spans the width", root.right.value, sheet.right.value, 0.5f)
    }

    @Test
    fun `on a 360 dp phone the sheet fills the height down to the mode bar`() = sheetReachesTheModeBar(360)

    @Test
    fun `on a 412 dp phone the sheet fills the height down to the mode bar`() = sheetReachesTheModeBar(412)

    @Test
    fun `on a tablet it stays a 420 dp popover`() {
        show(840)
        rule.onNode(navigator).assertWidthIsEqualTo(420.dp)
        val sheet = rule.onNode(navigator).getUnclippedBoundsInRoot()
        assertTrue("a popover, not a full-height sheet", sheet.bottom - sheet.top <= 560.dp)
    }

    @Test
    fun `the close button is a labelled 48 dp target that closes the sheet`() {
        show(412)
        rule
            .onNodeWithContentDescription("Close navigator")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(EditorAction.Navigator(NavigatorAction.Close), actions.last())
    }

    /**
     * The title stays above the open sheet and closes it again; TalkBack
     * hears its state. Run against the real view model, so the toggle is
     * the app's, not the test's.
     */
    @Test
    fun `tapping the title opens the Navigator and tapping it again closes it`() {
        RuntimeEnvironment.setQualifiers("w412dp-h${HEIGHT_DP}dp-420dpi")
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        f.vm.start()
        rule.setContent {
            val state by f.vm.uiState.collectAsState()
            Themed(dark = false) { EditorFor(state, f.vm::onAction) }
        }
        val title = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Navigator, collapsed")
        rule.onNode(title).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertNotNull("the first tap opens it", f.vm.uiState.value.navigator)
        rule.onNode(navigator).assertExists()

        val open = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Navigator, expanded")
        rule.onNode(open).assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertNull("the second tap closes it", f.vm.uiState.value.navigator)
        rule.onNode(navigator).assertDoesNotExist()
    }

    @Test
    fun `Back closes the Navigator first`() {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        f.vm.start()
        f.vm.onAction(EditorAction.TitleTapped)
        assertNotNull(f.vm.uiState.value.navigator)
        f.vm.onAction(EditorAction.BackPressed)
        assertNull(f.vm.uiState.value.navigator)
    }

    private companion object {
        const val HEIGHT_DP = 800

        /** The mode bar's own padding above its segments, at most. */
        val MODE_BAR_PADDING = 24.dp
    }
}
