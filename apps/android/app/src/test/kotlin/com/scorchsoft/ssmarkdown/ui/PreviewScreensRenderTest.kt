package com.scorchsoft.ssmarkdown.ui

import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.fixtures.PreviewSamples
import com.scorchsoft.ssmarkdown.fixtures.Samples
import com.scorchsoft.ssmarkdown.fontpicker.FontPickerAction
import com.scorchsoft.ssmarkdown.fontpicker.FontPickerScreen
import com.scorchsoft.ssmarkdown.media.ExportImagesAction
import com.scorchsoft.ssmarkdown.media.MediaMenuAction
import com.scorchsoft.ssmarkdown.media.ViewerAction
import com.scorchsoft.ssmarkdown.media.ViewerScreen
import com.scorchsoft.ssmarkdown.settings.PreviewWrap
import com.scorchsoft.ssmarkdown.themes.ThemeEditorAction
import com.scorchsoft.ssmarkdown.themes.ThemeEditorScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/** The viewer, the Preview sheets, Display, the theme editor and the font picker, light and dark. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class PreviewScreensRenderTest(
    private val dark: Boolean,
) {
    @get:Rule val rule = createComposeRule()

    @Test
    fun viewer() {
        val actions = ArrayList<ViewerAction>()
        rule.setContent { Themed(dark) { ViewerScreen(PreviewSamples.viewer) { actions += it } } }
        rule.onNodeWithText("Diagram · 2 of 5").assertIsDisplayed()
        rule.onNodeWithText("Drawing…").assertIsDisplayed()
        rule.onNodeWithText("Save").performClick()
        assertTrue(ViewerAction.Save in actions)
    }

    @Test
    fun exportImages() {
        val actions = ArrayList<EditorAction>()
        rule.setContent { Themed(dark) { EditorFor(PreviewSamples.exportSheet) { actions += it } } }
        rule.onNodeWithText("Export images").assertIsDisplayed()
        rule.onNodeWithText("5 found in this file · 3 diagrams, 2 pictures").assertIsDisplayed()
        rule.onNodeWithText("Select none").performClick()
        rule.onNodeWithText("Export 5 images").performScrollTo().performClick()
        assertTrue(EditorAction.ExportImages(ExportImagesAction.ToggleAll) in actions)
        assertTrue(EditorAction.ExportImages(ExportImagesAction.Export) in actions)
    }

    @Test
    fun mermaidSheet() {
        val actions = ArrayList<EditorAction>()
        rule.setContent { Themed(dark) { EditorFor(PreviewSamples.mermaidSheet) { actions += it } } }
        rule.onNodeWithText("2× (set in Export images)").assertIsDisplayed()
        rule.onNodeWithText("Show source in Code").performScrollTo().performClick()
        assertTrue(EditorAction.MediaMenu(MediaMenuAction.ShowSource) in actions)
    }

    @Test
    fun linkChip() {
        val actions = ArrayList<EditorAction>()
        rule.setContent { Themed(dark) { EditorFor(PreviewSamples.linkChip) { actions += it } } }
        rule.onNodeWithText("https://www.scorchsoft.com").assertIsDisplayed()
        rule.onNodeWithText("Open").performClick()
        assertTrue(EditorAction.OpenLink in actions)
    }

    @Test
    fun linkChipWithTapToEditOff() {
        val actions = ArrayList<EditorAction>()
        rule.setContent { Themed(dark) { EditorFor(PreviewSamples.linkChipReadOnly) { actions += it } } }
        rule.onNodeWithText("Open").assertIsDisplayed()
        rule.onNodeWithText("Edit link").assertDoesNotExist()
        rule.onNodeWithText("Copy link").performClick()
        assertTrue(EditorAction.CopyLink in actions)
    }

    @Test
    fun linkPromptEditsAnExistingLink() {
        val actions = ArrayList<EditorAction>()
        rule.setContent { Themed(dark) { EditorFor(PreviewSamples.linkPrompt) { actions += it } } }
        rule.onNodeWithText("Edit link").assertIsDisplayed()
        // The fields are labelled for TalkBack and prefilled from the link.
        rule.onNode(hasText("Link text") and hasSetTextAction()).assertTextContains("Scorchsoft")
        val url = rule.onNode(hasText("Address (URL)") and hasSetTextAction())
        url.assertTextContains("https://www.scorchsoft.com")
        url.performTextReplacement("https://scorchsoft.com/apps")
        // The keyboard's Done on the address is the same as the Done button.
        url.performImeAction()
        assertEquals(EditorAction.LinkDone("Scorchsoft", "https://scorchsoft.com/apps"), actions.last())
        rule.onNodeWithText("Remove link").performScrollTo().performClick()
        assertEquals(EditorAction.LinkRemove, actions.last())
        rule.onNodeWithText("Cancel").performScrollTo().performClick()
        assertEquals(EditorAction.DismissOverlay, actions.last())
    }

    @Test
    fun linkPromptTakesAnAddressFromTheClipboard() {
        val actions = ArrayList<EditorAction>()
        rule.setContent {
            Themed(dark) {
                LocalClipboardManager.current.setText(AnnotatedString("https://example.com/from-clipboard"))
                EditorFor(PreviewSamples.linkPromptEmpty) { actions += it }
            }
        }
        rule.onNodeWithText("Add link").assertIsDisplayed()
        rule.onNodeWithText("Remove link").assertDoesNotExist()
        rule
            .onNode(
                hasText("Address (URL)") and hasSetTextAction(),
            ).assertTextContains("https://example.com/from-clipboard")
        rule.onNodeWithText("Done").performScrollTo().performClick()
        assertEquals(EditorAction.LinkDone("the docs", "https://example.com/from-clipboard"), actions.last())
    }

    @Test
    fun linkPromptDoneWaitsForAnAddress() {
        val actions = ArrayList<EditorAction>()
        rule.setContent {
            Themed(dark) {
                LocalClipboardManager.current.setText(AnnotatedString("just words"))
                EditorFor(PreviewSamples.linkPromptEmpty) { actions += it }
            }
        }
        rule.onNode(hasText("Address (URL)") and hasSetTextAction()).assertTextEquals("Address (URL)", "")
        rule
            .onNodeWithText("Done")
            .performScrollTo()
            .assertIsNotEnabled()
            .performClick()
        assertTrue(actions.none { it is EditorAction.LinkDone })
    }

    @Test
    fun tableMenu() {
        rule.setContent { Themed(dark) { EditorFor(PreviewSamples.tableMenu) } }
        rule.onNodeWithText("Add row below").assertIsDisplayed()
    }

    @Test
    fun display() {
        val actions = ArrayList<EditorAction>()
        rule.setContent { Themed(dark) { EditorFor(PreviewSamples.display) { actions += it } } }
        rule.onNodeWithText("Code · 15 sp").assertIsDisplayed()
        rule.onNodeWithText("A4 page").performScrollTo().performClick()
        assertTrue(EditorAction.SetPreviewWrap(PreviewWrap.Page) in actions)
    }

    @Test
    fun `Display offers No wrap for Preview`() {
        val actions = ArrayList<EditorAction>()
        rule.setContent { Themed(dark) { EditorFor(PreviewSamples.display) { actions += it } } }
        // Code's "No wrap" comes first; Preview's is the last of Fluid / A4 page / No wrap.
        val noWrap = rule.onAllNodesWithText("No wrap")
        noWrap.assertCountEquals(2)
        noWrap[1].performScrollTo().performClick()
        assertTrue(EditorAction.SetPreviewWrap(PreviewWrap.NoWrap) in actions)
    }

    @Test
    fun split() {
        rule.setContent { Themed(dark) { EditorFor(Samples.saved.copy(mode = ViewMode.Split)) } }
        rule.onNodeWithText("release-notes.md").assertIsDisplayed()
    }

    @Test
    fun themeEditorTabs() {
        val actions = ArrayList<ThemeEditorAction>()
        rule.setContent { Themed(dark) { ThemeEditorScreen(PreviewSamples.themeType) { actions += it } } }
        rule.onNodeWithText("Custom · based on Reader").assertIsDisplayed()
        rule.onNodeWithText("Body font").performScrollTo().performClick()
        rule.onNodeWithText("Use this theme").performScrollTo().assertIsDisplayed()
        assertTrue(actions.any { it is ThemeEditorAction.PickFont })
    }

    @Test
    fun themeEditorColoursWithWarnings() {
        rule.setContent { Themed(dark) { ThemeEditorScreen(PreviewSamples.themeColours) {} } }
        rule.onNodeWithText("Fix it").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun themeEditorJson() {
        rule.setContent { Themed(dark) { ThemeEditorScreen(PreviewSamples.themeJson) {} } }
        rule.onNodeWithText("JSON").assertIsDisplayed()
    }

    @Test
    fun fontPicker() {
        val actions = ArrayList<FontPickerAction>()
        rule.setContent { Themed(dark) { FontPickerScreen(PreviewSamples.fonts) { actions += it } } }
        rule.onNodeWithText("On this phone").assertIsDisplayed()
        rule
            .onNodeWithText(
                "Couldn't download Lora — you're offline. Using Source Serif 4 until then.",
            ).assertIsDisplayed()
        rule.onNodeWithText("Get").performScrollTo().performClick()
        assertTrue(FontPickerAction.Download("Lora") in actions)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun themes(): List<Boolean> = listOf(false, true)
    }
}
