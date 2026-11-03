package com.scorchsoft.ssmarkdown

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.graphics.Insets
import androidx.core.graphics.withTranslation
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.scorchsoft.ssmarkdown.about.AboutScreen
import com.scorchsoft.ssmarkdown.editor.EditorOverlay
import com.scorchsoft.ssmarkdown.editor.EditorUiState
import com.scorchsoft.ssmarkdown.editor.Pane
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.fixtures.PreviewSamples
import com.scorchsoft.ssmarkdown.fixtures.Samples
import com.scorchsoft.ssmarkdown.fixtures.ScreenSamples
import com.scorchsoft.ssmarkdown.fontpicker.FontPickerScreen
import com.scorchsoft.ssmarkdown.history.HistoryScreen
import com.scorchsoft.ssmarkdown.media.ViewerScreen
import com.scorchsoft.ssmarkdown.settings.AppSettings
import com.scorchsoft.ssmarkdown.settings.CopyFormat
import com.scorchsoft.ssmarkdown.settings.SettingsScreen
import com.scorchsoft.ssmarkdown.share.ShareSheet
import com.scorchsoft.ssmarkdown.storage.StorageScreen
import com.scorchsoft.ssmarkdown.storage.StorageUiState
import com.scorchsoft.ssmarkdown.themes.ThemeEditorScreen
import com.scorchsoft.ssmarkdown.ui.EditorWith
import com.scorchsoft.ssmarkdown.ui.RealCodePane
import com.scorchsoft.ssmarkdown.ui.Themed
import com.scorchsoft.ssmarkdown.ui.strip.StripPage
import com.scorchsoft.ssmarkdown.ui.strip.StripRows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Headless screenshot harness (Robolectric native graphics). Renders the
 * key screens in both themes at the reference viewport (412 x 915 dp), at
 * the rubric's 360 / 600 / 840 / 1280 dp widths, and at 2× font for every
 * Save state, and writes `app/build/screenshots/<theme>/<name>.png`. A
 * review artefact, not a regression test, so it only runs when asked:
 *
 *     gradle -p apps/android :app:testDebugUnitTest -Pssmarkdown.screenshots=true --tests '*ScreenshotTest*'
 *
 * The list of surfaces is also the accessibility scanner's:
 * [AccessibilityScanTest] inherits every test here and overrides [capture]
 * to run the scanner's checks instead of writing a PNG, in the normal suite.
 * A screen added here is scanned too.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
open class ScreenshotTest(
    protected val dark: Boolean,
) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun editorSaved() = editor("editor-saved", Samples.saved)

    @Test fun editorUnsaved() = editor("editor-unsaved", Samples.section)

    @Test fun editorDraft() = editor("editor-draft", Samples.draft)

    @Test fun editorReadOnly() = editor("editor-read-only", Samples.readOnly)

    @Test fun editorFailed() = editor("editor-save-failed", Samples.failed)

    @Test fun editorRecovered() = editor("editor-recovered", Samples.recovered)

    @Test fun editorSplit() = editor("editor-split", Samples.zoomed.copy(mode = ViewMode.Split))

    @Test fun editorLargeFont() = shot("editor-font-200", fontScale = 2f) { EditorShot(Samples.unsaved) }

    // UX-7: the header at a large font, with a breadcrumb and with a failed save.
    @Test fun editorLargeFontSection() =
        shot(
            "editor-font-200-breadcrumb",
            fontScale = 2f,
        ) { EditorShot(Samples.section) }

    @Test fun editorLargeFontFailed() = shot("editor-font-200-failed", fontScale = 2f) { EditorShot(Samples.failed) }

    @Test
    @Config(qualifiers = "w915dp-h412dp-land-420dpi")
    fun editorLandscapeFont130() = shot("editor-font-130-landscape", fontScale = 1.3f) { EditorShot(Samples.section) }

    @Test
    @Config(qualifiers = "w915dp-h412dp-land-420dpi")
    fun editorLandscapeFont200() = shot("editor-font-200-landscape", fontScale = 2f) { EditorShot(Samples.failed) }

    // UX-8: segmented controls at a large font fall back to radio rows rather than clipping.
    @Test fun settingsLargeFont() =
        shot("settings-font-200", fontScale = 2f) {
            SettingsScreen(AppSettings(), "48.2 MB", {})
        }

    @Test
    fun themeEditorLargeFont() =
        shot("theme-editor-font-200", fontScale = 2f) {
            ThemeEditorScreen(PreviewSamples.themeColours, {})
        }

    // UX2-1 / UX2-13: every Save state at 2× font, so a clipped pill can never hide again.
    @Test fun saveDraftLargeFont() = shot("save-font-200-draft", fontScale = 2f) { EditorShot(Samples.draft) }

    @Test fun saveReadOnlyLargeFont() = shot("save-font-200-read-only", fontScale = 2f) { EditorShot(Samples.readOnly) }

    @Test
    fun saveReadOnlyCleanLargeFont() =
        shot("save-font-200-read-only-clean", fontScale = 2f) { EditorShot(Samples.readOnlyClean) }

    @Test fun saveRetryLargeFont() = shot("save-font-200-retry", fontScale = 2f) { EditorShot(Samples.failed) }

    @Test fun saveSavingLargeFont() = shot("save-font-200-saving", fontScale = 2f) { EditorShot(Samples.saving) }

    @Test fun savePausedLargeFont() =
        shot(
            "save-font-200-paused",
            fontScale = 2f,
        ) { EditorShot(Samples.autosavePaused) }

    @Test fun recoveredLargeFont() = shot("editor-font-200-recovered", fontScale = 2f) { EditorShot(Samples.recovered) }

    @Test fun bigFile() = editor("editor-big-file", Samples.bigFile)

    // UX2-13: the rubric's widths (#8).
    @Test
    @Config(qualifiers = "w360dp-h780dp-420dpi")
    fun width360() = editor("width-360-folder", ScreenSamples.longName)

    @Test
    @Config(qualifiers = "w360dp-h780dp-420dpi")
    fun width360LargeFont() =
        shot("width-360-font-200-read-only", fontScale = 2f) {
            EditorShot(Samples.readOnly.copy(folder = ScreenSamples.folder))
        }

    @Test
    @Config(qualifiers = "w360dp-h780dp-420dpi")
    fun width360Drawer() = editor("width-360-drawer", ScreenSamples.drawer)

    @Test
    @Config(qualifiers = "w600dp-h960dp-320dpi")
    fun width600() = editor("width-600-split", ScreenSamples.split600)

    @Test
    @Config(qualifiers = "w840dp-h1200dp-320dpi")
    fun width840() = editor("width-840-docked", ScreenSamples.docked)

    @Test
    @Config(qualifiers = "w840dp-h1200dp-320dpi")
    fun width840Collapsed() =
        editor(
            "width-840-collapsed",
            ScreenSamples.docked.copy(folder = ScreenSamples.folder.copy(panelCollapsed = true)),
        )

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-240dpi")
    fun width1280() = editor("width-1280-split", ScreenSamples.wideSplit)

    // UX2-13: the surfaces the review had to render by hand.
    @Test fun navigator() = editor("navigator", ScreenSamples.navigator)

    @Test fun find() = editor("find", ScreenSamples.find)

    @Test fun findLargeFont() = shot("find-font-200", fontScale = 2f) { EditorShot(ScreenSamples.find) }

    @Test fun conflict() = sheet("conflict-sheet", Samples.conflict)

    @Test fun sectionMenu() = sheet("section-menu", ScreenSamples.sectionMenu)

    /** The Copy chooser, anchored to Copy, with the last-used choice (formatted text) first. */
    @Test
    fun copyChooser() =
        sheet("copy-chooser", Samples.saved.copy(overlay = EditorOverlay.CopyChooser(CopyFormat.Formatted)))

    @Test fun history() = shot("history") { HistoryScreen(ScreenSamples.history, {}) }

    @Test fun share() = shot("share-sheet") { ShareSheet(ScreenSamples.share) {} }

    @Test fun shareAlreadyThere() = shot("share-sheet-already-there") { ShareSheet(ScreenSamples.shareAlreadyThere) {} }

    @Test fun shareInterrupted() = shot("share-sheet-interrupted") { ShareSheet(ScreenSamples.shareInterrupted) {} }

    @Test
    fun shareAlreadyThereLargeFont() =
        shot("share-sheet-already-there-font-200", fontScale = 2f) { ShareSheet(ScreenSamples.shareAlreadyThere) {} }

    @Test fun strip() = keyboard("formatting-strip", Samples.unsaved)

    @Test fun stripLargeFont() = keyboard("formatting-strip-font-200", Samples.unsaved, fontScale = 2f)

    // Settings → Formatting bar → Two rows: the formatting page and the symbols page at 412 dp.
    @Test fun stripTwoRows() = keyboard("formatting-strip-two-rows", Samples.unsaved, rows = StripRows.Two)

    @Test
    fun stripTwoRowsSymbols() =
        keyboard(
            "formatting-strip-two-rows-symbols",
            Samples.unsaved.copy(strip = Samples.unsaved.strip.copy(page = StripPage.Symbols)),
            rows = StripRows.Two,
        )

    @Test
    @Config(qualifiers = "w360dp-h780dp-420dpi")
    fun stripTwoRows360() = keyboard("formatting-strip-two-rows-360", Samples.unsaved, rows = StripRows.Two)

    @Test
    fun stripTwoRowsLargeFont() =
        keyboard("formatting-strip-two-rows-font-200", Samples.unsaved, fontScale = 2f, rows = StripRows.Two)

    @Test fun preview() = editor("preview", Samples.saved.copy(mode = ViewMode.Preview))

    @Test fun splitPortrait() = editor("split-portrait", Samples.saved.copy(mode = ViewMode.Split))

    @Test fun viewer() = shot("viewer") { ViewerScreen(PreviewSamples.viewer, {}) }

    @Test fun exportImages() = sheet("export-images", PreviewSamples.exportSheet)

    @Test fun mermaidSheet() = sheet("mermaid-sheet", PreviewSamples.mermaidSheet)

    @Test fun display() = sheet("display", PreviewSamples.display)

    /** The View sheet from a long-press on Code: the same layout, Code's Lines row tinted. */
    @Test
    fun viewFromCode() = sheet("view-from-code", Samples.saved.copy(overlay = EditorOverlay.Display(Pane.Code)))

    @Test fun linkPrompt() = sheet("link-prompt", PreviewSamples.linkPrompt)

    @Test fun themeEditor() = shot("theme-editor") { ThemeEditorScreen(PreviewSamples.themeType, {}) }

    @Test fun themeEditorColours() = shot("theme-editor-colours") { ThemeEditorScreen(PreviewSamples.themeColours, {}) }

    @Test fun fontPicker() = shot("font-picker") { FontPickerScreen(PreviewSamples.fonts, {}) }

    @Test fun settings() = shot("settings") { SettingsScreen(AppSettings(), "48.2 MB", {}) }

    @Test fun storage() = shot("storage") { StorageScreen(StorageUiState(), {}) }

    @Test fun about() = shot("about") { AboutScreen("0.1.0", {}, {}, {}) }

    private fun editor(
        name: String,
        state: EditorUiState,
    ) = shot(name) { EditorShot(state) }

    /** An editor state with a modal sheet open: the sheet is its own window, and must be in the shot. */
    private fun sheet(
        name: String,
        state: EditorUiState,
    ) {
        render { EditorShot(state) }
        val windows = capture(name)
        check(windows > 1) { "$name: the sheet's window was not captured" }
    }

    /**
     * The editor with the keyboard up: an IME inset is dispatched to the
     * root, as the platform does, so the formatting strip replaces the mode bar.
     */
    private fun keyboard(
        name: String,
        state: EditorUiState,
        fontScale: Float = 1f,
        rows: StripRows = StripRows.One,
    ) {
        lateinit var view: View
        render(fontScale) {
            view = LocalView.current
            EditorShot(state, rows)
        }
        rule.runOnIdle {
            val ime = (KEYBOARD_DP * view.resources.displayMetrics.density).toInt()
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
                    .setVisible(WindowInsetsCompat.Type.ime(), true)
                    .build()
            ViewCompat.dispatchApplyWindowInsets(view, insets)
        }
        rule.waitForIdle()
        capture(name)
    }

    private fun shot(
        name: String,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        render(fontScale, content)
        capture(name)
    }

    private fun render(
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        rule.setContent { Themed(dark, fontScale, content) }
        rule.waitForIdle()
    }

    // Robolectric's paused looper never services the frame-commit callback
    // that compose-test's captureToImage() waits on, so it deadlocks. Drawing
    // the decor view into a software bitmap works instead: the native
    // graphics runtime rasterises text and shapes for real.
    //
    // A modal sheet or dialog lives in a window of its own, which the
    // activity's decor view does not contain (UX-24: the Display, Export
    // images and Mermaid shots used to show only the editor behind the
    // sheet). So every window the app has open is drawn, bottom to top, at
    // its position on screen. Writes `<theme>/<name>.png` and returns how
    // many windows were drawn into it.
    protected open fun capture(name: String): Int {
        val decor = rule.activity.window.decorView
        check(decor.width > 0 && decor.height > 0) { "decor view not laid out" }
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val windows = openWindows()
        windows.forEach { root ->
            val at = IntArray(2).also(root::getLocationOnScreen)
            canvas.withTranslation(at[0].toFloat(), at[1].toFloat()) { root.draw(this) }
        }
        val dir = File(SCREENSHOTS, if (dark) "dark" else "light").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
        return windows.size
    }

    /**
     * The root view of every window on screen, in the order they were added
     * (bottom to top). Android has no public API for this; WindowManagerGlobal
     * keeps the list, and under Robolectric it is plain JVM reflection.
     */
    protected fun openWindows(): List<View> {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        val views = global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance)
        return (views as List<*>).filterIsInstance<View>().filter { it.isShown && it.width > 0 && it.height > 0 }
    }

    /** The editor in [state] with the real Code pane, so shots show the app's syntax colouring. */
    @Composable
    private fun EditorShot(
        state: EditorUiState,
        rows: StripRows = StripRows.One,
    ) = EditorWith(state, codePane = { RealCodePane(state, it) }, stripRows = rows)

    companion object {
        private const val PNG_QUALITY = 100
        private const val KEYBOARD_DP = 300
        private const val SCREENSHOTS = "build/screenshots"

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun themes(): List<Boolean> = listOf(false, true)
    }
}
