package com.scorchsoft.ssmarkdown.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.TooLargeInfo
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.find.FindUiState
import com.scorchsoft.ssmarkdown.fixtures.Samples
import com.scorchsoft.ssmarkdown.folder.FolderEntryRow
import com.scorchsoft.ssmarkdown.folder.FolderUiState
import com.scorchsoft.ssmarkdown.navigator.NavigatorUiState
import com.scorchsoft.ssmarkdown.navigator.OutlineRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/** The editor screen in its mockup states, light and dark. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class EditorScreenRenderTest(
    private val dark: Boolean,
) {
    @get:Rule val rule = createComposeRule()

    private val actions = ArrayList<EditorAction>()

    private fun render(
        state: com.scorchsoft.ssmarkdown.editor.EditorUiState,
        fontScale: Float = 1f,
    ) {
        rule.setContent { Themed(dark, fontScale) { EditorFor(state) { actions += it } } }
    }

    @Test
    fun `saved file shows the name, the time and the clean tick`() {
        render(Samples.saved)
        rule.onNodeWithText("release-notes.md").assertIsDisplayed()
        rule.onNodeWithText("Saved · 2 min ago").assertIsDisplayed()
        rule.onNodeWithContentDescription("All saved").assertIsDisplayed()
        rule.onNodeWithContentDescription("Copy all").assertIsDisplayed()
        rule.onNodeWithText("Code").assertIsSelected()
    }

    @Test
    fun `unsaved shows the dot, the status and a Save pill that saves`() {
        render(Samples.unsaved)
        rule.onNodeWithText("Unsaved").assertIsDisplayed()
        rule.onNodeWithContentDescription("Save").performClick()
        assertTrue(EditorAction.SaveTapped in actions)
    }

    @Test
    fun `draft reads Untitled, kept on this phone, Save as`() {
        render(Samples.draft)
        rule.onNodeWithText("Untitled").assertIsDisplayed()
        rule.onNodeWithText("Draft · kept on this phone").assertIsDisplayed()
        rule.onNodeWithContentDescription("Save as…").assertIsDisplayed()
    }

    @Test
    fun `Save sits before Copy and the clean tick sits right beside it, with no hole`() {
        render(Samples.saved)
        val save = rule.onNodeWithContentDescription("All saved").getUnclippedBoundsInRoot()
        val copy = rule.onNodeWithContentDescription("Copy all").getUnclippedBoundsInRoot()
        // Save's slot ends where Copy's 48 dp target begins: Copy never moves,
        // and the right-aligned tick is one ordinary icon gap from it.
        assertEquals(save.right.value, copy.left.value, 0.5f)
    }

    @Test
    fun `a file declined only for what is open now says so, never needs 178 of 268`() {
        val mb = 1024L * 1024
        render(Samples.draft.copy(tooLarge = TooLargeInfo(21 * mb, 178 * mb, 268 * mb, 120 * mb)))
        rule.onNodeWithText("is free right now", substring = true).assertIsDisplayed()
        rule.onNodeWithText("this phone allows", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a file beyond the ceiling names the ceiling`() {
        val mb = 1024L * 1024
        render(Samples.draft.copy(tooLarge = TooLargeInfo(180 * mb, 400 * mb, 256 * mb, 150 * mb)))
        rule.onNodeWithText("this phone allows", substring = true).assertIsDisplayed()
    }

    @Test
    fun `read-only with edits names the source`() {
        render(Samples.readOnly)
        // UX2-14: the badge says read-only, so the line keeps room for the source.
        rule.onNodeWithText("READ-ONLY").assertIsDisplayed()
        rule.onNodeWithText("Unsaved · from Gmail").assertIsDisplayed()
    }

    @Test
    fun `a failed save says why and offers Save as`() {
        render(Samples.failed)
        rule.onNodeWithText("Couldn't save · storage full").assertIsDisplayed()
        // UX2-2: the banner itself says what failed and why.
        rule.onNodeWithText("Couldn't save roadmap.md · storage full").assertIsDisplayed()
        rule.onNodeWithText("Your text is safe on this phone.").assertIsDisplayed()
        rule.onNodeWithContentDescription("Retry").assertIsDisplayed()
    }

    @Test
    fun `the recovery banner has its three buttons`() {
        render(Samples.recovered)
        rule.onNodeWithText("Your unsaved edits are back").assertIsDisplayed()
        rule.onNodeWithText("Discard edits").performClick()
        rule.onNodeWithText("Got it").assertIsDisplayed()
        assertTrue(EditorAction.RecoveryDiscard in actions)
    }

    @Test
    fun `scrolled past a heading shows the current section with the save state`() {
        // A phone's title is narrower than 360 dp, so only the current section shows, never stubs.
        render(Samples.section)
        // The section and the state are two texts, so a tight line cuts the section, never "Unsaved".
        rule.onNodeWithText("› Code review", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText(" · Unsaved", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `the conflict sheet offers merge, compare, keep and use mine`() {
        render(Samples.conflict)
        rule.onNodeWithText("Merge my edits into the new file").assertIsDisplayed()
        rule.onNodeWithText("Compare").assertExists()
        rule.onNodeWithText("Keep the file's version").assertExists()
        rule.onNodeWithText("Use my version instead…").assertExists()
    }

    @Test
    fun `the zoom chip appears only away from the default and resets`() {
        render(Samples.zoomed)
        rule.onNodeWithContentDescription("Reset zoom, now 120%").performClick()
        assertTrue(actions.any { it is EditorAction.ResetZoom })
    }

    @Test
    fun `Split shows the preview slot placeholder`() {
        render(Samples.saved.copy(mode = ViewMode.Split))
        rule.onNodeWithText("Preview draws here (a stand-in where no WebView runs)").assertIsDisplayed()
    }

    @Test
    fun `the find bar counts matches under the header`() {
        render(
            Samples.unsaved.copy(
                find =
                    FindUiState(
                        query = "save",
                        matches =
                            List(12) {
                                com.scorchsoft.ssmarkdown.find.FindMatch(
                                    it,
                                    it + 1,
                                )
                            },
                        current = 2,
                    ),
            ),
        )
        rule.onNodeWithText("3 of 12").assertIsDisplayed()
        rule.onNodeWithText("release-notes.md").assertIsDisplayed()
    }

    @Test
    fun `the navigator lists the outline with you-are-here`() {
        val nav =
            NavigatorUiState(
                outline = listOf(OutlineRow(0, 1, "Team handbook"), OutlineRow(9, 2, "Code review", current = true)),
            )
        render(Samples.section.copy(navigator = nav))
        rule.onNodeWithText("Top of document").assertIsDisplayed()
        rule.onNodeWithText("You're here").assertIsDisplayed()
    }

    @Test
    fun `the folder drawer offers Save all only while files are unsaved`() {
        val rows =
            listOf(
                FolderEntryRow("a", "roadmap.md", 0, false, dirty = true),
                FolderEntryRow("b", "todo.txt", 0, false, muted = true),
            )
        render(
            Samples.unsaved.copy(
                folder = FolderUiState("product-docs", "t", rows, fileCount = 2, unsavedCount = 1, drawerOpen = true),
            ),
        )
        rule.onNodeWithText("Save all").performClick()
        rule.onNodeWithText("product-docs").assertIsDisplayed()
        assertTrue(actions.any { it is EditorAction.Folder })
    }

    @Test
    fun `at twice the font size Copy moves into the menu and Save stays`() {
        render(Samples.unsaved, fontScale = 2f)
        assertEquals(0, rule.onAllNodesWithContentDescription("Copy all").fetchSemanticsNodes().size)
        rule.onNodeWithContentDescription("Save").assertIsDisplayed()
        rule.onNodeWithContentDescription("Split").assertExists()
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun themes(): List<Boolean> = listOf(false, true)
    }
}
