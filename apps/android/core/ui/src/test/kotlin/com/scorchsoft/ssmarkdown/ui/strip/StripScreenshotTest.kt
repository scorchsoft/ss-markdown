package com.scorchsoft.ssmarkdown.ui.strip

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.scorchsoft.ssmarkdown.core.ui.SSMarkdownTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the strip's states to `core/ui/build/screenshots/<theme>/`,
 * with the same harness as :app's ScreenshotTest. A review artefact, so
 * it only runs when asked:
 *
 *     gradle -p apps/android :core:ui:testDebugUnitTest -Pssmarkdown.screenshots=true --tests '*StripScreenshotTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h240dp-420dpi")
class StripScreenshotTest(
    private val dark: Boolean,
) {
    private val activity = ComposeActivityRule()

    @get:Rule val rules = activity.chain

    @Test
    fun portrait() =
        capture(
            "strip-portrait",
            FormattingStripState(canUndo = true, pressed = setOf(StripKey.Bold)),
            FormattingStripState(page = StripPage.Symbols, canUndo = true),
            FormattingStripState(mode = StripMode.Preview, canUndo = true, pressed = setOf(StripKey.Italic)),
        )

    /** Settings → Formatting bar → Two rows: Code, symbols, Preview and table mode. */
    @Test
    fun twoRows() =
        capture(
            "strip-two-rows",
            FormattingStripState(canUndo = true, pressed = setOf(StripKey.Bold)),
            FormattingStripState(page = StripPage.Symbols, canUndo = true),
            FormattingStripState(mode = StripMode.Preview, canUndo = true, pressed = setOf(StripKey.Italic)),
            FormattingStripState(canUndo = true, inTable = true),
            rows = StripRows.Two,
        )

    @Test
    fun table() =
        capture(
            "strip-table",
            FormattingStripState(canUndo = true, inTable = true),
            FormattingStripState(mode = StripMode.Preview, canUndo = true, inTable = true),
        )

    @Test
    @Config(qualifiers = "w844dp-h240dp-land-420dpi")
    fun landscape() =
        capture(
            "strip-landscape",
            FormattingStripState(canUndo = true, document = StripDocument("release-notes.md", isDirty = true)),
            FormattingStripState(canUndo = true, document = StripDocument("release-notes.md", isDirty = false)),
            FormattingStripState(canUndo = true, inTable = true, document = StripDocument("budget.md", isDirty = true)),
            // UX-9: the rest of the Save-state table, since the header is gone.
            FormattingStripState(canUndo = true, document = StripDocument("Untitled", true, StripSave.SaveAs)),
            FormattingStripState(
                canUndo = true,
                document =
                    StripDocument(
                        "release-notes.md",
                        isDirty = true,
                        save = StripSave.Retry,
                        status = "Couldn't save · storage full",
                        statusIsError = true,
                    ),
            ),
            FormattingStripState(canUndo = true, document = StripDocument("release-notes.md", true, StripSave.Saving)),
        )

    private fun capture(
        name: String,
        vararg states: FormattingStripState,
        rows: StripRows = StripRows.One,
    ) {
        activity.compose.setContent {
            SSMarkdownTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column {
                        states.forEach {
                            FormattingStrip(
                                it,
                                onKey = {},
                                onSymbol = {},
                                onCursor = {},
                                rows = rows,
                            )
                        }
                    }
                }
            }
        }
        activity.compose.waitForIdle()
        // captureToImage() deadlocks under Robolectric's paused looper; drawing the decor view does not.
        val view = activity.compose.activity.window.decorView
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
