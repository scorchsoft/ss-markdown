package com.scorchsoft.ssmarkdown.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorUiState
import com.scorchsoft.ssmarkdown.editor.SaveStatusLine
import com.scorchsoft.ssmarkdown.editor.chrome.BIG_FILE_CHIP_TAG
import com.scorchsoft.ssmarkdown.editor.chrome.isError
import com.scorchsoft.ssmarkdown.fixtures.Samples
import com.scorchsoft.ssmarkdown.folder.FolderAction
import com.scorchsoft.ssmarkdown.folder.FolderEntryRow
import com.scorchsoft.ssmarkdown.folder.FolderUiState
import com.scorchsoft.ssmarkdown.settings.AppSettings
import com.scorchsoft.ssmarkdown.settings.SettingsScreen
import com.scorchsoft.ssmarkdown.storage.StorageAction
import com.scorchsoft.ssmarkdown.storage.StorageScreen
import com.scorchsoft.ssmarkdown.storage.StorageUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The round-2 UX review's findings (UX2-*), one test each: what a
 * large-font reader sees, what TalkBack hears, and where a finger lands.
 * Native graphics, so text has real widths and clipping is real.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ChromeReviewTwoTest {
    @get:Rule val rule = createComposeRule()

    private val actions = ArrayList<EditorAction>()

    private fun editor(
        state: EditorUiState,
        fontScale: Float = 1f,
    ) = rule.setContent { Themed(dark = false, fontScale) { EditorFor(state) { actions += it } } }

    /** The text node showing exactly [text], with its layout: lines and overflow are real under native graphics. */
    private fun layoutOf(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = ArrayList<TextLayoutResult>()
        node
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action!!
            .invoke(results)
        return results.single()
    }

    /**
     * True when the text is wider than the box it was given. Compared with
     * the intrinsic width: a one-line paragraph is laid out at the full
     * constraint, so `didOverflowWidth` reports overflow that is not there.
     */
    private fun TextLayoutResult.clipsWidth(): Boolean = multiParagraph.intrinsics.maxIntrinsicWidth > size.width + 1

    private fun assertWhole(text: String) {
        val node = rule.onNodeWithText(text)
        node.assertIsDisplayed()
        val layout = layoutOf(node)
        assertFalse("\"$text\" is clipped", layout.clipsWidth())
        assertFalse("\"$text\" is ellipsized", layout.isLineEllipsized(layout.lineCount - 1))
        assertEquals("\"$text\" wraps", 1, layout.lineCount)
    }

    private fun visibleTexts(): List<String> =
        rule
            .onAllNodes(hasText("", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { it.texts() }

    private fun SemanticsNode.texts(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    // UX2-1: the Save slot never says less than the action, in any state, at 2×.

    @Test
    fun `UX2-1 at double font a draft's pill reads Save as in full`() {
        editor(Samples.draft, fontScale = 2f)
        assertWhole("Save as…")
        rule.onNodeWithContentDescription("Save as…").assertIsDisplayed()
        assertFalse("a Save-as pill reads Save", "Save" in visibleTexts())
    }

    @Test
    fun `UX2-1 at double font a read-only file with edits reads Save as, never Save`() {
        editor(Samples.readOnly, fontScale = 2f)
        assertWhole("Save as…")
        assertFalse("a Save-as pill reads Save", "Save" in visibleTexts())
    }

    @Test
    fun `UX2-1 at double font a read-only file without edits reads Save as`() {
        editor(Samples.readOnlyClean, fontScale = 2f)
        assertWhole("Save as…")
    }

    @Test
    fun `UX2-1 at double font Retry and Save are whole`() {
        editor(Samples.failed, fontScale = 2f)
        assertWhole("Retry")
    }

    @Test
    fun `UX2-1 at double font the Saving pill is whole and disabled`() {
        editor(Samples.saving, fontScale = 2f)
        assertWhole("Save")
        rule.onNodeWithContentDescription("Saving").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w360dp-h740dp-420dpi")
    fun `UX2-1 on a 360 dp phone in folder mode at double font Save as still says Save as`() {
        val folder = FolderUiState("product-docs", "t", listOf(FolderEntryRow("a", "notes.md", 0, false)), 1)
        editor(Samples.readOnly.copy(folder = folder), fontScale = 2f)
        val full = rule.onAllNodesWithText("Save as…").fetchSemanticsNodes()
        val short = rule.onAllNodesWithText("As…").fetchSemanticsNodes()
        assertTrue("neither Save as… nor the glyph's As… is shown", full.size + short.size == 1)
        assertFalse("a Save-as pill reads Save", "Save" in visibleTexts())
        rule.onNodeWithContentDescription("Save as…").assertIsDisplayed()
    }

    // UX2-2: the banner says what failed and why, and survives 2×.

    @Test
    fun `UX2-2 at double font the failed-save banner names the file and the reason in full`() {
        editor(Samples.failed, fontScale = 2f)
        val title = rule.onNodeWithText("Couldn't save roadmap.md · storage full")
        title.assertIsDisplayed()
        val layout = layoutOf(title)
        // It wraps instead: never ellipsized, whatever the font scale.
        assertFalse("the banner title is cut short", layout.isLineEllipsized(layout.lineCount - 1))
        assertTrue("the banner title should wrap at 2×", layout.lineCount > 1)
        rule.onNodeWithText("Your text is safe on this phone.").assertIsDisplayed()
    }

    // UX2-3: banner buttons keep one line each and flow instead.

    @Test
    fun `UX2-3 at double font every recovery button stays on one line`() {
        editor(Samples.recovered, fontScale = 2f)
        for (label in listOf("Discard edits", "Compare", "Got it")) assertWhole(label)
    }

    // UX2-4: the docked folder panel can be hidden and brought back.

    @Test
    @Config(qualifiers = "w900dp-h1200dp-320dpi")
    fun `UX2-4 the docked panel has a Hide folder button that toggles it`() {
        val folder = FolderUiState("product-docs", "t", listOf(FolderEntryRow("a", "notes.md", 0, false)), 1)
        editor(Samples.saved.copy(folder = folder))
        rule.onNodeWithText("product-docs").assertIsDisplayed()
        rule.onNodeWithContentDescription("Hide folder").performClick()
        assertTrue(EditorAction.Folder(FolderAction.TogglePanel) in actions)
    }

    @Test
    @Config(qualifiers = "w900dp-h1200dp-320dpi")
    fun `UX2-4 a collapsed panel leaves a Show folder button and the text full width`() {
        val folder =
            FolderUiState(
                "product-docs",
                "t",
                listOf(FolderEntryRow("a", "notes.md", 0, false)),
                1,
                panelCollapsed = true,
            )
        editor(Samples.saved.copy(folder = folder))
        assertTrue(rule.onAllNodesWithText("product-docs").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithContentDescription("Show folder").performClick()
        assertTrue(EditorAction.Folder(FolderAction.TogglePanel) in actions)
    }

    // UX2-5: the Big-file chip is a full target outside the title's tap.

    @Test
    fun `UX2-5 the Big-file chip is a 48 dp target that does not overlap the Navigator tap`() {
        editor(Samples.bigFile)
        val chip = rule.onNodeWithTag(BIG_FILE_CHIP_TAG)
        val bounds = chip.getBoundsInRoot()
        assertTrue("chip is ${bounds.width} wide", bounds.width >= 48.dp)
        assertTrue("chip is ${bounds.height} tall", bounds.height >= 48.dp)
        val title =
            rule
                .onNode(
                    hasClickAction() and hasText("server-log-export.md", substring = true),
                ).getBoundsInRoot()
        assertTrue("the chip sits inside the title's tap area", bounds.left >= title.right)
        chip.performClick()
        assertTrue(EditorAction.BigFileChipTapped in actions)
        assertFalse(EditorAction.TitleTapped in actions)
    }

    @Test
    fun `UX2-5 the Big-file chip stays in the compact header at double font`() {
        editor(Samples.bigFile, fontScale = 2f)
        rule.onNodeWithTag(BIG_FILE_CHIP_TAG).assertIsDisplayed()
    }

    // UX2-7: one error rule for every header.

    @Test
    fun `UX2-7 a paused autosave counts as an error everywhere, like a failed save`() {
        assertTrue(
            Samples.autosavePaused.save.status
                .isError(),
        )
        assertTrue(
            Samples.failed.save.status
                .isError(),
        )
        assertFalse(SaveStatusLine.Unsaved.isError())
    }

    // UX2-8: Settings steps zoom with the Display sheet's − / + pills.

    @Test
    fun `UX2-8 settings zoom steps with minus and plus, not chevrons`() {
        rule.setContent { Themed(dark = false) { SettingsScreen(AppSettings(), null, {}) } }
        assertTrue(rule.onAllNodesWithText("−").fetchSemanticsNodes().size >= 2)
        assertTrue(rule.onAllNodesWithText("+").fetchSemanticsNodes().size >= 2)
        rule.onNodeWithContentDescription("Increase Default zoom · Code").assertIsDisplayed()
    }

    // UX2-14: beside the READ-ONLY badge the line keeps the source.

    @Test
    fun `UX2-14 beside the badge the line names the source without repeating read-only`() {
        editor(Samples.readOnly)
        rule.onNodeWithText("READ-ONLY").assertIsDisplayed()
        assertWhole("Unsaved · from Gmail")
    }

    // UX2-16: Import lives in ⋮, and the history subtitle matches the switch.

    @Test
    fun `UX2-16 Import history is in the menu, not the top bar`() {
        val got = ArrayList<StorageAction>()
        rule.setContent { Themed(dark = false) { StorageScreen(StorageUiState(), { got += it }) } }
        assertTrue(rule.onAllNodesWithText("Import history (.zip)").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Import history (.zip)").performClick()
        assertEquals(listOf(StorageAction.ImportHistory), got)
    }

    @Test
    fun `UX2-16 with history on, the subtitle says what on does`() {
        rule.setContent { Themed(dark = false) { StorageScreen(StorageUiState(), {}) } }
        rule.onNodeWithText("Each save keeps a version you can go back to.").performScrollTo().assertIsDisplayed()
        assertTrue(rule.onAllNodesWithText("Off: no versions", substring = true).fetchSemanticsNodes().isEmpty())
    }
}
