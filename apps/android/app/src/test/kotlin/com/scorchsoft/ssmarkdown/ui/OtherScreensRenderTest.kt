package com.scorchsoft.ssmarkdown.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.scorchsoft.ssmarkdown.about.AboutScreen
import com.scorchsoft.ssmarkdown.about.Licence
import com.scorchsoft.ssmarkdown.about.LicencesScreen
import com.scorchsoft.ssmarkdown.conflict.CompareOrigin
import com.scorchsoft.ssmarkdown.conflict.CompareScreen
import com.scorchsoft.ssmarkdown.conflict.CompareUiState
import com.scorchsoft.ssmarkdown.conflict.MergeResolveScreen
import com.scorchsoft.ssmarkdown.conflict.MergeUiState
import com.scorchsoft.ssmarkdown.conflict.Overlap
import com.scorchsoft.ssmarkdown.files.storage.CategoryUsage
import com.scorchsoft.ssmarkdown.files.storage.StorageBreakdown
import com.scorchsoft.ssmarkdown.files.storage.StorageCategory
import com.scorchsoft.ssmarkdown.history.HistoryScreen
import com.scorchsoft.ssmarkdown.history.HistoryUiState
import com.scorchsoft.ssmarkdown.history.versions.ClearPreview
import com.scorchsoft.ssmarkdown.settings.AppSettings
import com.scorchsoft.ssmarkdown.settings.RecentFile
import com.scorchsoft.ssmarkdown.settings.SettingsAction
import com.scorchsoft.ssmarkdown.settings.SettingsScreen
import com.scorchsoft.ssmarkdown.share.ShareSheet
import com.scorchsoft.ssmarkdown.share.ShareUiState
import com.scorchsoft.ssmarkdown.storage.ClearKind
import com.scorchsoft.ssmarkdown.storage.StorageReducer
import com.scorchsoft.ssmarkdown.storage.StorageScreen
import com.scorchsoft.ssmarkdown.storage.StorageUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings, Storage, History, About, Merge, Compare and Share-in, light and dark. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class OtherScreensRenderTest(
    private val dark: Boolean,
) {
    @get:Rule val rule = createComposeRule()

    @Test
    fun settings() {
        val actions = ArrayList<SettingsAction>()
        rule.setContent { Themed(dark) { SettingsScreen(AppSettings(), "48.2 MB", onAction = { actions += it }) } }
        rule.onNodeWithText("When a file opens, show").assertIsDisplayed()
        rule.onNodeWithText("Storage and history").performScrollTo().performClick()
        rule.onNodeWithText("Tap to edit in Preview").performScrollTo().assertIsDisplayed()
        assertTrue(SettingsAction.OpenStorage in actions)
    }

    @Test
    fun storage() {
        val breakdown = StorageBreakdown(StorageCategory.entries.associateWith { CategoryUsage(1_000_000, 3) }, 0)
        rule.setContent { Themed(dark) { StorageScreen(StorageUiState(breakdown = breakdown), {}) } }
        rule.onNodeWithText("Free up space").assertIsDisplayed()
        rule.onNodeWithText("Keep version history").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("About Android's “Clear cache” and “Clear storage”").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `storage confirm defaults to export and clear`() {
        val confirm = StorageReducer.confirmFor(ClearKind.NotOpened, ClearPreview(642, 11, 18_400_000), days = 90)
        rule.setContent { Themed(dark) { StorageScreen(StorageUiState(confirm = confirm), {}) } }
        rule.onNodeWithText("Clear history of 11 files?").assertIsDisplayed()
        rule.onNodeWithText("This can't be undone.").assertIsDisplayed()
        rule.onNodeWithText("Export and clear").assertIsDisplayed()
    }

    @Test
    fun history() {
        rule.setContent {
            Themed(
                dark,
            ) { HistoryScreen(HistoryUiState(fileName = "roadmap.md", versionCount = 0, loading = false), {}) }
        }
        rule.onNodeWithText("Autosave this file").assertIsDisplayed()
        rule.onNodeWithText("No versions yet. One is kept each time you open or save.").assertIsDisplayed()
    }

    @Test
    fun `history off offers Turn on`() {
        rule.setContent { Themed(dark) { HistoryScreen(HistoryUiState(recording = false, loading = false), {}) } }
        rule.onNodeWithText("Version history is off").assertIsDisplayed()
        rule.onNodeWithText("Turn on").assertIsDisplayed()
    }

    @Test
    fun about() {
        rule.setContent { Themed(dark) { AboutScreen("0.1.0", {}, {}, {}) } }
        rule.onNodeWithText("Made by Scorchsoft").assertIsDisplayed()
        rule
            .onNodeWithText(
                "Provided as is, without warranty of any kind. Keep your own backups of anything important.",
            ).assertIsDisplayed()
        rule.onNodeWithText("Licensed under the Apache License 2.0").assertIsDisplayed()
    }

    @Test
    fun licences() {
        rule.setContent {
            Themed(dark) { LicencesScreen(listOf(Licence("kotlinx.coroutines", "Apache License 2.0", "github")), {}) }
        }
        rule.onNodeWithText("kotlinx.coroutines").assertIsDisplayed()
    }

    @Test
    fun merge() {
        val state =
            MergeUiState(
                "roadmap.md",
                3,
                listOf(
                    Overlap(14, "Q3", "- Ship folders, Save all and export\n", "- Ship folders + Save all (beta)\n"),
                ),
            )
        rule.setContent { Themed(dark) { MergeResolveScreen(state, {}) } }
        rule.onNodeWithText("Use the file's version").assertIsDisplayed()
        rule.onNodeWithText("Done (1 left)").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun compare() {
        val state = CompareUiState.of("roadmap.md", CompareOrigin.Conflict, "a\nb\n", "a\nc\n")
        rule.setContent { Themed(dark) { CompareScreen(state, {}) } }
        rule.onNodeWithText("1 difference").assertIsDisplayed()
        rule.onNodeWithText("Merge my edits").assertIsDisplayed()
    }

    @Test
    fun share() {
        val state =
            ShareUiState(
                "Files you can read…",
                "example.org",
                listOf(RecentFile("u", "reading-notes.md", "", 0)),
                target = "u",
            )
        rule.setContent { Themed(dark) { ShareSheet(state) {} } }
        rule.onNodeWithText("reading-notes.md").assertIsDisplayed()
        rule.onNodeWithText("Append").assertIsDisplayed()
        rule.onNodeWithText("New note").assertIsDisplayed()
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun themes(): List<Boolean> = listOf(false, true)
    }
}
