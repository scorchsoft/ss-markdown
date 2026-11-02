package com.scorchsoft.ssmarkdown.ui

import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorUiState
import com.scorchsoft.ssmarkdown.editor.SaveButton
import com.scorchsoft.ssmarkdown.editor.chrome.toStrip
import com.scorchsoft.ssmarkdown.fixtures.PreviewSamples
import com.scorchsoft.ssmarkdown.fixtures.Samples
import com.scorchsoft.ssmarkdown.folder.FolderEntryRow
import com.scorchsoft.ssmarkdown.folder.FolderUiState
import com.scorchsoft.ssmarkdown.media.ViewerScreen
import com.scorchsoft.ssmarkdown.navigator.FileRow
import com.scorchsoft.ssmarkdown.navigator.FileRowKind
import com.scorchsoft.ssmarkdown.navigator.NavigatorTab
import com.scorchsoft.ssmarkdown.navigator.NavigatorUiState
import com.scorchsoft.ssmarkdown.settings.AppSettings
import com.scorchsoft.ssmarkdown.settings.SettingsAction
import com.scorchsoft.ssmarkdown.settings.SettingsScreen
import com.scorchsoft.ssmarkdown.themes.ThemeEditorScreen
import com.scorchsoft.ssmarkdown.ui.strip.StripSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The UX and accessibility review's findings, one test each: what
 * TalkBack is told, and what survives a large font. Native graphics so
 * text has real sizes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class AccessibilityRenderTest {
    @get:Rule val rule = createComposeRule()

    private val actions = ArrayList<EditorAction>()

    private fun editor(
        state: EditorUiState,
        fontScale: Float = 1f,
    ) = rule.setContent { Themed(dark = false, fontScale) { EditorFor(state) { actions += it } } }

    @Test
    fun `UX-3 switch rows tell TalkBack whether they are on`() {
        val settings = ArrayList<SettingsAction>()
        rule.setContent {
            Themed(dark = false) {
                val values = AppSettings(lineNumbers = true, scrollPanesTogether = false)
                SettingsScreen(values, null, { settings += it })
            }
        }
        rule
            .onNodeWithText("Line numbers")
            .performScrollTo()
            .assertIsOn()
            .performClick()
        rule.onNodeWithText("Scroll panes together").performScrollTo().assertIsOff()
        assertEquals(listOf(SettingsAction.ToggleLineNumbers), settings)
    }

    @Test
    fun `UX-15 the zoom steppers say what they change`() {
        rule.setContent { Themed(dark = false) { SettingsScreen(AppSettings(), null, {}) } }
        rule.onNodeWithContentDescription("Increase Default zoom · Code").assertIsDisplayed()
        rule.onNodeWithContentDescription("Decrease Default zoom · Preview").assertIsDisplayed()
        rule.onNodeWithText("100% (12.5 sp)").assert(isLiveRegion())
    }

    @Test
    fun `UX-16 settings no longer promise themes that already exist`() {
        rule.setContent { Themed(dark = false) { SettingsScreen(AppSettings(), null, {}) } }
        assertEquals(0, rule.onAllNodesWithText("Chosen in Display once themes arrive").fetchSemanticsNodes().size)
    }

    @Test
    fun `UX-6 an unsaved file in the drawer reads Unsaved, not black circle`() {
        val rows = listOf(FolderEntryRow("a", "roadmap.md", 0, false, dirty = true, current = true))
        editor(Samples.unsaved.copy(folder = FolderUiState("docs", "t", rows, 1, 1, drawerOpen = true)))
        val row = rule.onNodeWithText("roadmap.md")
        row.assertContentDescriptionContains("Unsaved")
        assertEquals(0, rule.onAllNodesWithText("●").fetchSemanticsNodes().size)
        // UX-19: the open file is selected, not only tinted.
        row.assertIsSelected()
    }

    @Test
    fun `UX-6 an unsaved recent file in the Navigator reads Unsaved`() {
        val nav =
            NavigatorUiState(
                tab = NavigatorTab.Files,
                recent = listOf(FileRow("u", "notes.md", "Documents", FileRowKind.Recent, dirty = true)),
            )
        editor(Samples.saved.copy(navigator = nav))
        rule.onNodeWithText("notes.md").assertContentDescriptionContains("Unsaved")
        // UX-13: the sheet announces itself.
        rule.onNode(hasPaneTitle("Navigator")).assertExists()
    }

    @Test
    fun `UX-13 the folder drawer is a titled pane with a labelled scrim, and hides the editor`() {
        val rows = listOf(FolderEntryRow("a", "roadmap.md", 0, false))
        editor(Samples.saved.copy(folder = FolderUiState("product-docs", "t", rows, 1, drawerOpen = true)))
        rule.onNode(hasPaneTitle("product-docs")).assertExists()
        // TalkBack's double-tap is the click action; a finger at the scrim's centre would land on the panel.
        rule.onNodeWithContentDescription("Close the file list").performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(actions.any { it is EditorAction.Folder })
        // The editor behind is out of the accessibility tree while the drawer is open.
        assertEquals(0, rule.onAllNodesWithText("release-notes.md").fetchSemanticsNodes().size)
    }

    @Test
    fun `UX-7 at double font the header folds to one line and keeps a failed save visible`() {
        editor(Samples.failed, fontScale = 2f)
        rule.onNodeWithText("roadmap.md").assertIsDisplayed()
        val status = rule.onNodeWithText("· Couldn't save · storage full", substring = true)
        status.assertExists()
        val name = rule.onNodeWithText("roadmap.md").getBoundsInRoot()
        assertEquals("same line as the name", name.top, status.getBoundsInRoot().top)
        assertTrue("the header starts on screen", name.top >= 0.dp)
    }

    @Test
    @Config(qualifiers = "w915dp-h412dp-land-420dpi")
    fun `UX-7 the landscape bar grows rather than clipping at a large font`() {
        editor(Samples.failed, fontScale = 1.5f)
        val name = rule.onNodeWithText("roadmap.md").assertIsDisplayed().getBoundsInRoot()
        rule.onNodeWithText("· Couldn't save · storage full", substring = true).assertExists()
        rule.onNodeWithContentDescription("Retry").assertIsDisplayed()
        assertTrue(name.top >= 0.dp)
    }

    @Test
    fun `UX-30 the title says Unsaved once`() {
        editor(Samples.section)
        val title = rule.onNodeWithText("handbook.md").fetchSemanticsNode()
        val spoken =
            (
                title.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                    title.config
                        .getOrNull(SemanticsProperties.Text)
                        .orEmpty()
                        .map { it.text }
            ).joinToString(" ")
        assertEquals(spoken, 1, Regex("Unsaved").findAll(spoken).count())
    }

    @Test
    fun `UX-14 a read-only file with edits shows the read-only badge`() {
        editor(Samples.readOnly)
        rule.onNodeWithText("READ-ONLY").assertIsDisplayed()
    }

    @Test
    fun `UX-9 the strip's Save follows every row of the Save-state table`() {
        assertEquals(StripSave.Saved, SaveButton.AllSaved.toStrip())
        assertEquals(StripSave.Save, SaveButton.Save.toStrip())
        assertEquals(StripSave.Saving, SaveButton.Saving.toStrip())
        assertEquals(StripSave.SaveAs, SaveButton.SaveAsDraft.toStrip())
        assertEquals(StripSave.SaveAs, SaveButton.SaveAsReadOnly.toStrip())
        assertEquals(StripSave.SaveAsRequired, SaveButton.SaveAsRequired.toStrip())
        assertEquals(StripSave.Retry, SaveButton.Retry.toStrip())
    }

    @Test
    fun `UX-20 viewer thumbnails are named tabs with a selected one, and zoom has buttons`() {
        rule.setContent { Themed(dark = false) { ViewerScreen(PreviewSamples.viewer, {}) } }
        rule.onNodeWithContentDescription("Architecture").assertIsSelected()
        rule.onNodeWithContentDescription("Save flow").assertIsNotSelected()
    }

    @Test
    fun `UX-11 the filmstrip keeps full-size thumbnails above a 3-button navigation bar`() {
        lateinit var view: View
        rule.setContent {
            view = LocalView.current
            Themed(dark = false) { ViewerScreen(PreviewSamples.viewer, {}) }
        }
        rule.runOnIdle {
            val navBar = (NAV_BAR_DP * view.resources.displayMetrics.density).toInt()
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navBar))
                    .build()
            ViewCompat.dispatchApplyWindowInsets(view, insets)
        }
        rule.waitForIdle()
        val thumb = rule.onNodeWithContentDescription("Save flow").getBoundsInRoot()
        assertTrue("thumbnail is ${thumb.bottom - thumb.top} tall", thumb.bottom - thumb.top >= 55.dp)
        val screen = rule.onRoot().getBoundsInRoot()
        assertTrue("thumbnail clears the nav bar", thumb.bottom <= screen.bottom - NAV_BAR_DP.dp)
    }

    @Test
    fun `UX-23 theme colour fields are named for people, not JSON keys`() {
        rule.setContent { Themed(dark = false) { ThemeEditorScreen(PreviewSamples.themeColours, {}) } }
        rule.onNodeWithContentDescription("Code background").performScrollTo().assertExists()
        assertEquals(0, rule.onAllNodesWithText("codeBg").fetchSemanticsNodes().size)
    }

    @Test
    fun `UX-8 the theme editor's four tabs fit a phone`() {
        rule.setContent { Themed(dark = false) { ThemeEditorScreen(PreviewSamples.themeColours, {}) } }
        val json = rule.onNodeWithText("JSON").getBoundsInRoot()
        assertTrue("JSON ends at ${json.right}", json.right <= 412.dp)
    }

    private companion object {
        const val NAV_BAR_DP = 48
    }

    private fun hasPaneTitle(title: String) = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title)

    private fun isLiveRegion() = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)
}
