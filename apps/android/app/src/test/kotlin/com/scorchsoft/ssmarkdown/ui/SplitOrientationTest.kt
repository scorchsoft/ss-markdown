package com.scorchsoft.ssmarkdown.ui

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.editor.panes.splitSideBySide
import com.scorchsoft.ssmarkdown.fixtures.Samples
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Rubric #8, "Split orientation follows the window shape" (ux-spec
 * "Layouts"): a window taller than it is wide stacks Code over Preview, a
 * wider one puts them side by side. The 600 dp tablet is the case a width
 * breakpoint alone got wrong: 600 x 960 dp went side by side, two 300 dp
 * columns on a tall screen.
 */
@RunWith(RobolectricTestRunner::class)
class SplitOrientationTest {
    @get:Rule val rule = createComposeRule()

    private val split = Samples.unsaved.copy(mode = ViewMode.Split)

    /** True when the Preview pane sits right of the page's centre (side by side), false when it is below it. */
    private fun previewIsBeside(): Boolean {
        rule.setContent { Themed(dark = false) { EditorFor(split) } }
        val page = rule.onRoot().getBoundsInRoot()
        val preview = rule.onNode(hasText(PLACEHOLDER, substring = true)).getBoundsInRoot()
        val centreX = (preview.left + preview.right) / 2
        val pageCentreX = (page.left + page.right) / 2
        return centreX > pageCentreX + 1.dp
    }

    @Test
    @Config(qualifiers = "w600dp-h960dp-320dpi")
    fun `a 600 x 960 dp portrait window stacks the panes`() {
        assertFalse(previewIsBeside())
    }

    @Test
    @Config(qualifiers = "w960dp-h600dp-land-320dpi")
    fun `a 960 x 600 dp landscape window puts them side by side`() {
        assertTrue(previewIsBeside())
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-420dpi")
    fun `a phone in portrait stacks the panes`() {
        assertFalse(previewIsBeside())
    }

    @Test
    @Config(qualifiers = "w915dp-h412dp-land-420dpi")
    fun `a phone in landscape puts them side by side`() {
        assertTrue(previewIsBeside())
    }

    @Test
    fun `the rule is wider than tall, with room for two panes`() {
        assertTrue(splitSideBySide(960.dp, 960.dp, 600.dp))
        assertFalse(splitSideBySide(600.dp, 600.dp, 960.dp))
        // Square counts as wide: two halves of a square are no worse than two stacked halves.
        assertTrue(splitSideBySide(800.dp, 800.dp, 800.dp))
        // Wide but small (a 500 x 300 dp split-screen window): 250 dp columns would be too thin.
        assertFalse(splitSideBySide(500.dp, 500.dp, 300.dp))
        // A docked folder panel narrows the page below 600 dp: stacked even in landscape.
        assertFalse(splitSideBySide(560.dp, 900.dp, 600.dp))
    }

    private companion object {
        const val PLACEHOLDER = "Preview draws here"
    }
}
