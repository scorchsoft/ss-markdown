package com.scorchsoft.ssmarkdown.ui

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.fixtures.Samples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * edge-cases "Tiny split-screen window (e.g. 300 dp high)": stacked Split
 * would leave each pane a few lines, so the page keeps the Code pane and
 * folds Preview into its one-line strip, as it does while the keyboard is
 * up; both bars stay. A normal phone still gets both panes.
 */
@RunWith(RobolectricTestRunner::class)
class TinySplitWindowTest {
    @get:Rule val rule = createComposeRule()

    private val split = Samples.unsaved.copy(mode = ViewMode.Split)

    private fun previewPanes(): Int = rule.onAllNodes(hasPreviewPlaceholder()).fetchSemanticsNodes().size

    private fun hasPreviewPlaceholder() =
        androidx.compose.ui.test
            .hasText(PLACEHOLDER, substring = true)

    @Test
    @Config(qualifiers = "w412dp-h300dp-420dpi")
    fun `a 300 dp high window shows the Code pane and the Preview strip, with both bars`() {
        rule.setContent { Themed(dark = false) { EditorFor(split) } }
        assertEquals(0, previewPanes())
        // A window this shape gets the landscape bar, where the modes are icons named for TalkBack.
        val splitMode = hasText("Split") or hasContentDescription("Split")
        assertTrue(rule.onAllNodes(splitMode).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithContentDescription("Copy all").assertExists()
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-420dpi")
    fun `a phone-sized window still stacks both panes`() {
        rule.setContent { Themed(dark = false) { EditorFor(split) } }
        assertEquals(1, previewPanes())
    }

    private companion object {
        const val PLACEHOLDER = "Preview draws here"
    }
}
