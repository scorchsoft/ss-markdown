package com.scorchsoft.ssmarkdown

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.graphics.withTranslation
import com.scorchsoft.ssmarkdown.about.AboutScreen
import com.scorchsoft.ssmarkdown.editor.EditorUiState
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.fixtures.PreviewSamples
import com.scorchsoft.ssmarkdown.fixtures.Samples
import com.scorchsoft.ssmarkdown.fontpicker.FontPickerScreen
import com.scorchsoft.ssmarkdown.media.ViewerScreen
import com.scorchsoft.ssmarkdown.settings.AppSettings
import com.scorchsoft.ssmarkdown.settings.SettingsScreen
import com.scorchsoft.ssmarkdown.storage.StorageScreen
import com.scorchsoft.ssmarkdown.storage.StorageUiState
import com.scorchsoft.ssmarkdown.themes.ThemeEditorScreen
import com.scorchsoft.ssmarkdown.ui.EditorFor
import com.scorchsoft.ssmarkdown.ui.Themed
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Headless screenshot harness (Robolectric native graphics). Renders the
 * key screens in both themes at the reference viewport (412 x 915 dp) and
 * writes `app/build/screenshots/<theme>/<name>.png`. A review artefact,
 * not a regression test, so it only runs when asked:
 *
 *     gradle -p apps/android :app:testDebugUnitTest -Pssmarkdown.screenshots=true --tests '*ScreenshotTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ScreenshotTest(
    private val dark: Boolean,
) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun editorSaved() = editor("editor-saved", Samples.saved)

    @Test fun editorUnsaved() = editor("editor-unsaved", Samples.section)

    @Test fun editorDraft() = editor("editor-draft", Samples.draft)

    @Test fun editorReadOnly() = editor("editor-read-only", Samples.readOnly)

    @Test fun editorFailed() = editor("editor-save-failed", Samples.failed)

    @Test fun editorRecovered() = editor("editor-recovered", Samples.recovered)

    @Test fun editorSplit() = editor("editor-split", Samples.zoomed.copy(mode = ViewMode.Split))

    @Test fun editorLargeFont() = shot("editor-font-200", fontScale = 2f) { EditorFor(Samples.unsaved) }

    @Test fun preview() = editor("preview", Samples.saved.copy(mode = ViewMode.Preview))

    @Test fun splitPortrait() = editor("split-portrait", Samples.saved.copy(mode = ViewMode.Split))

    @Test fun viewer() = shot("viewer") { ViewerScreen(PreviewSamples.viewer, {}) }

    @Test fun exportImages() = sheet("export-images", PreviewSamples.exportSheet)

    @Test fun mermaidSheet() = sheet("mermaid-sheet", PreviewSamples.mermaidSheet)

    @Test fun display() = sheet("display", PreviewSamples.display)

    @Test fun themeEditor() = shot("theme-editor") { ThemeEditorScreen(PreviewSamples.themeType, {}) }

    @Test fun themeEditorColours() = shot("theme-editor-colours") { ThemeEditorScreen(PreviewSamples.themeColours, {}) }

    @Test fun fontPicker() = shot("font-picker") { FontPickerScreen(PreviewSamples.fonts, {}) }

    @Test fun settings() = shot("settings") { SettingsScreen(AppSettings(), "48.2 MB", {}) }

    @Test fun storage() = shot("storage") { StorageScreen(StorageUiState(), {}) }

    @Test fun about() = shot("about") { AboutScreen("0.1.0", {}, {}, {}) }

    private fun editor(
        name: String,
        state: EditorUiState,
    ) = shot(name) { EditorFor(state) }

    /** An editor state with a modal sheet open: the sheet is its own window, and must be in the shot. */
    private fun sheet(
        name: String,
        state: EditorUiState,
    ) {
        render { EditorFor(state) }
        val windows = capture(name)
        check(windows > 1) { "$name: the sheet's window was not captured" }
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
    // its position on screen.
    /** Writes `<theme>/<name>.png`; returns how many windows were drawn into it. */
    private fun capture(name: String): Int {
        val decor = rule.activity.window.decorView
        check(decor.width > 0 && decor.height > 0) { "decor view not laid out" }
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val windows = rootViews().filter { it.isShown && it.width > 0 && it.height > 0 }
        windows.forEach { root ->
            val at = IntArray(2).also(root::getLocationOnScreen)
            canvas.withTranslation(at[0].toFloat(), at[1].toFloat()) { root.draw(this) }
        }
        val dir = File(SCREENSHOTS, if (dark) "dark" else "light").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
        return windows.size
    }

    /**
     * Every window's root view, in the order they were added (bottom to
     * top). Android has no public API for this; WindowManagerGlobal keeps
     * the list, and under Robolectric it is plain JVM reflection.
     */
    private fun rootViews(): List<View> {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        val views = global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance)
        return (views as List<*>).filterIsInstance<View>()
    }

    companion object {
        private const val PNG_QUALITY = 100
        private const val SCREENSHOTS = "build/screenshots"

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun themes(): List<Boolean> = listOf(false, true)
    }
}
