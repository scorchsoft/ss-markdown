package com.scorchsoft.ssmarkdown.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.history.versions.StorageLimit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsTest {
    @Test
    fun `settings survive a JSON round trip`() {
        val settings =
            AppSettings(
                openWith = OpenWith.Preview,
                saveMode = SaveMode.Autosave,
                defaultZoomCode = 120,
                previewWrap = PreviewWrap.NoWrap,
                history = AppSettings().history.copy(totalLimit = StorageLimit.None, forgetUnusedAfterDays = 90),
                autosaveOverrides = mapOf("content://a" to false),
                share = ShareDefaults("content://a", AppendPosition.Top, AppendPrefix.DateHeading),
                copyFormat = CopyFormat.Formatted,
            ).withRecent(RecentFile("content://a", "a.md", "Documents", 5, pinned = true))
        assertEquals(settings, SettingsJson.decode(JSONObject(SettingsJson.encode(settings).toString())))
    }

    @Test
    fun `Direct Share is on by default, also for settings saved before it existed`() {
        assertTrue(AppSettings().share.directShare)
        val old = "{\"share\":{\"target\":\"content://a\",\"position\":\"Top\",\"prefix\":\"None\"}}"
        val decoded = SettingsJson.decode(JSONObject(old)).share
        assertTrue(decoded.directShare)
        assertEquals("content://a", decoded.lastTarget)
    }

    @Test
    fun `turning Direct Share off persists`() {
        val prefs =
            ApplicationProvider
                .getApplicationContext<Context>()
                .getSharedPreferences("settings-test", Context.MODE_PRIVATE)
        val repository = PersistedSettingsRepository(PreferencesSettingsStore(prefs))
        repository.update { SettingsReducer.reduce(it, SettingsAction.ToggleDirectShare) }
        assertFalse(repository.settings.value.share.directShare)
        assertFalse(PreferencesSettingsStore(prefs).read().share.directShare)
        repository.update { SettingsReducer.reduce(it, SettingsAction.ToggleDirectShare) }
        assertTrue(PreferencesSettingsStore(prefs).read().share.directShare)
    }

    @Test
    fun `the last Copy choice persists, and Markdown is the default`() {
        assertEquals(CopyFormat.Markdown, AppSettings().copyFormat)
        assertEquals(CopyFormat.Markdown, SettingsJson.decode(JSONObject("{}")).copyFormat)
        val prefs =
            ApplicationProvider
                .getApplicationContext<Context>()
                .getSharedPreferences("settings-copy-test", Context.MODE_PRIVATE)
        PersistedSettingsRepository(
            PreferencesSettingsStore(prefs),
        ).update { it.copy(copyFormat = CopyFormat.Formatted) }
        assertEquals(CopyFormat.Formatted, PreferencesSettingsStore(prefs).read().copyFormat)
    }

    @Test
    fun `the old A4 page flag still reads as the page`() {
        assertEquals(PreviewWrap.Page, SettingsJson.decode(JSONObject("{\"pageMode\":true}")).previewWrap)
    }

    @Test
    fun `missing or damaged fields fall back to the defaults`() {
        assertEquals(AppSettings(), SettingsJson.decode(JSONObject("{\"saveMode\":\"Nope\"}")))
    }

    @Test
    fun `drafts always autosave, files follow their override, then the mode`() {
        val s = AppSettings(autosaveOverrides = mapOf("b" to true))
        assertTrue(s.autosaves("d", isDraft = true))
        assertTrue(s.autosaves("b", isDraft = false))
        assertFalse(s.autosaves("c", isDraft = false))
        assertTrue(s.copy(saveMode = SaveMode.Autosave).autosaves("c", isDraft = false))
    }

    @Test
    fun `first view is Preview for files from other apps under Last used`() {
        assertEquals(ViewMode.Preview, AppSettings().firstViewFor(fromAnotherApp = true))
        assertEquals(ViewMode.Code, AppSettings().firstViewFor(fromAnotherApp = false))
        assertEquals(ViewMode.Code, AppSettings(openWith = OpenWith.Code).firstViewFor(fromAnotherApp = true))
    }

    @Test
    fun `recent files keep pins and the cap`() {
        var s = AppSettings()
        repeat(AppSettings.MAX_RECENT_FILES + 5) { s = s.withRecent(RecentFile("u$it", "n", "", it.toLong())) }
        assertEquals(AppSettings.MAX_RECENT_FILES, s.recentFiles.size)
        assertEquals("u24", s.lastFile)
    }

    @Test
    fun `the settings reducer clamps zoom`() {
        val s = SettingsReducer.reduce(AppSettings(), SettingsAction.StepCodeZoom(-100))
        assertEquals(50, s.defaultZoomCode)
        assertTrue(SettingsReducer.reduce(AppSettings(), SettingsAction.ToggleScrollTogether).scrollPanesTogether.not())
    }
}
