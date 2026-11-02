package com.scorchsoft.ssmarkdown.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createComposeRule
import com.scorchsoft.ssmarkdown.core.ui.SSDarkColors
import com.scorchsoft.ssmarkdown.core.ui.SSLightColors
import com.scorchsoft.ssmarkdown.editor.panes.CodePaneHost
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.ui.Themed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The app's real Code pane colours Markdown (the "plain ink" screenshot
 * question). The app screenshots used to draw a stand-in pane of plain
 * `Text`, so they could not show colouring either way; this hosts the real
 * [CodePaneHost] exactly as the editor route does (view model attach, the
 * core/ui [EditorTheme], the bundled code typeface) and reads the runs the
 * renderer draws, rather than pixels.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CodePaneColouringTest(
    private val dark: Boolean,
) {
    @get:Rule val temp = TemporaryFolder()

    @get:Rule val rule = createComposeRule()

    @Test
    fun `the app's Code pane draws headings bold, markers muted and links blue`() {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        f.vm.openDocument(f.file("notes.md", SAMPLE))
        var view: MarkdownCodeView? = null
        rule.setContent {
            Themed(dark) {
                val active by f.vm.activeDocument.collectAsState()
                CodePaneHost(
                    active,
                    wrap = true,
                    zoomPercent = 100,
                    onAttach = {
                        view = it
                        f.vm.attach(it)
                    },
                    onDetach = f.vm::detach,
                    background = { it.run() },
                )
            }
        }
        rule.waitForIdle()
        val code = checkNotNull(view) { "the Code view never attached" }
        val runs = rule.runOnIdle { DrawnRuns.of(code) }

        val colors = if (dark) SSDarkColors else SSLightColors
        val ink = colors.ink.toArgb()
        val muted = colors.inkMuted.toArgb()
        val heading = runs.single { it.text.trim() == "Release notes" }
        assertTrue("heading text is not bold: $heading", heading.bold)
        assertEquals("heading colour", ink, heading.color)
        assertTrue("the # marker is not bold", runs.first { it.text.startsWith("#") }.bold)
        assertTrue("## Checklist is not bold", runs.single { it.text.trim() == "Checklist" }.bold)
        assertEquals("the ** marker is not muted", muted, runs.first { it.text == "**" }.color)
        assertEquals("the backtick is not muted", muted, runs.first { it.text == "`" }.color)
        assertEquals("the URL is not muted", muted, runs.first { it.text.contains("example.com") }.color)
        assertEquals("the link text is not info blue", colors.info.toArgb(), runs.single { it.text == "docs" }.color)
    }

    companion object {
        private val SAMPLE =
            """
            # Release notes

            A **calm** editor with `inline code`.
            See [docs](https://example.com) for more.

            ## Checklist
            """.trimIndent()

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun themes(): List<Boolean> = listOf(false, true)
    }
}

/** One text run the Code view drew, with the paint it drew it in. */
data class DrawnRun(
    val text: String,
    val color: Int,
    val bold: Boolean,
)

/**
 * A software canvas that remembers every text run drawn on it. The Code
 * view draws a software canvas directly (no display lists), so every run
 * the renderer styled lands here with its paint.
 */
class DrawnRuns private constructor(
    bitmap: Bitmap,
) : Canvas(bitmap) {
    private val runs = ArrayList<DrawnRun>()

    override fun drawTextRun(
        text: CharArray,
        index: Int,
        count: Int,
        contextIndex: Int,
        contextCount: Int,
        x: Float,
        y: Float,
        isRtl: Boolean,
        paint: Paint,
    ) {
        runs += DrawnRun(String(text, index, count), paint.color, paint.isFakeBoldText)
        super.drawTextRun(text, index, count, contextIndex, contextCount, x, y, isRtl, paint)
    }

    /** Draws [view] once and returns the runs it drew. */
    companion object {
        fun of(view: android.view.View): List<DrawnRun> {
            val canvas = DrawnRuns(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888))
            view.draw(canvas)
            return canvas.runs
        }
    }
}
