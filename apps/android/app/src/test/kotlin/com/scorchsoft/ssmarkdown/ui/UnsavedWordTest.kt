package com.scorchsoft.ssmarkdown.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import com.scorchsoft.ssmarkdown.editor.EditorUiState
import com.scorchsoft.ssmarkdown.fixtures.Samples
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "No signal relies on colour alone" (rubric #10): the word "Unsaved" is
 * on screen, whole, wherever the header has an unsaved file, including at
 * double font (the one-line header) and in Big-file mode, where the chip
 * and a section breadcrumb crowd the second line. The section name gives
 * way first; "Unsaved" never does.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class UnsavedWordTest {
    @get:Rule val rule = createComposeRule()

    private fun editor(
        state: EditorUiState,
        fontScale: Float = 1f,
    ) = rule.setContent { Themed(dark = false, fontScale) { EditorFor(state) } }

    private fun assertUnsavedWhole() {
        val node = rule.onNodeWithText("Unsaved", substring = true, useUnmergedTree = true)
        node.assertIsDisplayed()
        val results = ArrayList<TextLayoutResult>()
        node
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action!!
            .invoke(results)
        val layout = results.single()
        assertFalse("\"Unsaved\" is ellipsized", layout.isLineEllipsized(layout.lineCount - 1))
        assertFalse(
            "\"Unsaved\" is clipped",
            layout.multiParagraph.intrinsics.maxIntrinsicWidth > layout.size.width + 1,
        )
    }

    @Test
    fun `at double font the one-line header still says Unsaved`() {
        editor(Samples.unsaved, fontScale = 2f)
        assertUnsavedWhole()
    }

    @Test
    fun `in Big-file mode with a breadcrumb the section gives way, not Unsaved`() {
        editor(Samples.bigFile)
        assertUnsavedWhole()
    }
}
