package com.scorchsoft.ssmarkdown.editor

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextDocument
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Headless screenshots of the Code pane (Robolectric native graphics), in
 * both themes and both wrap modes, written to
 * `feature/editor/build/screenshots/<theme>/`. A review artefact, not a
 * regression test, so it only runs when asked, like the app's harness:
 *
 *     gradle -p apps/android :feature:editor:testDebugUnitTest -Pssmarkdown.screenshots=true --tests '*ScreenshotTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class EditorScreenshotTest(
    private val dark: Boolean,
) {
    @Test
    fun noWrap() = capture("code-nowrap", wrap = false)

    @Test
    fun wrap() = capture("code-wrap", wrap = true)

    private fun capture(
        name: String,
        wrap: Boolean,
    ) {
        val base = ApplicationProvider.getApplicationContext<android.content.Context>()
        val configuration =
            Configuration(base.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
        val context = base.createConfigurationContext(configuration)
        val metrics = context.resources.displayMetrics
        val view = MarkdownCodeView(context)
        view.theme = if (dark) EditorTheme.DARK else EditorTheme.LIGHT
        view.wrap = wrap
        view.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels / 2, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        view.setDocument(TextDocument(PieceTree.of(SAMPLE)))
        view.session.setSelection(SAMPLE.indexOf("calm"), SAMPLE.indexOf("calm") + 4)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/screenshots", if (dark) "dark" else "light").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
    }

    companion object {
        private const val PNG_QUALITY = 100

        private val SAMPLE =
            """
            ---
            title: Release notes
            ---
            # SS Markdown
            
            A **calm**, fast editor with *emphasis*, ~~struck~~ text and `inline code`.
            See [the docs](https://example.com/docs "Docs") or <https://scorchsoft.com>.
            
            > Quoted text with a [^1] footnote.
            
            - [x] Done task
            - [ ] Open task
              1. Nested ordered item
            
            ```kotlin
            fun main() = println("Hello")
            ```
            
            | Col | Other |
            |-----|:-----:|
            | a   | b     |
            
            A very long paragraph line that goes on well past the edge of a phone screen so that wrap mode has something to break into rows and no-wrap mode has something to scroll sideways.
            
            ${'$'}${'$'}
            e = mc^2
            ${'$'}${'$'}
            <!-- an html comment -->
            ***
            [^1]: The footnote.
            """.trimIndent()

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun themes(): List<Boolean> = listOf(false, true)
    }
}
