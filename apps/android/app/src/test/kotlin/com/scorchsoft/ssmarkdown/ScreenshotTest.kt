package com.scorchsoft.ssmarkdown

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.scorchsoft.ssmarkdown.about.AboutScreen
import com.scorchsoft.ssmarkdown.editor.EditorUiState
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.fixtures.Samples
import com.scorchsoft.ssmarkdown.settings.AppSettings
import com.scorchsoft.ssmarkdown.settings.SettingsScreen
import com.scorchsoft.ssmarkdown.storage.StorageScreen
import com.scorchsoft.ssmarkdown.storage.StorageUiState
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

    @Test fun settings() = shot("settings") { SettingsScreen(AppSettings(), "48.2 MB", {}) }

    @Test fun storage() = shot("storage") { StorageScreen(StorageUiState(), {}) }

    @Test fun about() = shot("about") { AboutScreen("0.1.0", {}, {}, {}) }

    private fun editor(
        name: String,
        state: EditorUiState,
    ) = shot(name) { EditorFor(state) }

    private fun shot(
        name: String,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        rule.setContent { Themed(dark, fontScale, content) }
        rule.waitForIdle()
        capture(name)
    }

    // Robolectric's paused looper never services the frame-commit callback
    // that compose-test's captureToImage() waits on, so it deadlocks. Drawing
    // the decor view into a software bitmap works instead: the native
    // graphics runtime rasterises text and shapes for real.
    private fun capture(name: String) {
        val view = rule.activity.window.decorView
        check(view.width > 0 && view.height > 0) { "decor view not laid out" }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/screenshots", if (dark) "dark" else "light").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
    }

    companion object {
        private const val PNG_QUALITY = 100

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun themes(): List<Boolean> = listOf(false, true)
    }
}
