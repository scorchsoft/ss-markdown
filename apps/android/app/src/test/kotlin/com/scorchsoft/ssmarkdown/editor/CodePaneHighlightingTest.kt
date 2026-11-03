package com.scorchsoft.ssmarkdown.editor

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createComposeRule
import com.scorchsoft.ssmarkdown.core.ui.SSDarkColors
import com.scorchsoft.ssmarkdown.core.ui.SSLightColors
import com.scorchsoft.ssmarkdown.editor.panes.CodePaneHost
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.theme.BuiltInThemes
import com.scorchsoft.ssmarkdown.theme.SyntaxColors
import com.scorchsoft.ssmarkdown.theme.Theme
import com.scorchsoft.ssmarkdown.theme.ThemeColor
import com.scorchsoft.ssmarkdown.theme.contrast.Contrast
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
 * The app's Code pane highlights fenced code in the Preview theme's code
 * colours (the same palette Preview's highlight.js uses), and every one of
 * them reads at AA on the Code pane's code band.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CodePaneHighlightingTest(
    private val dark: Boolean,
) {
    @get:Rule val temp = TemporaryFolder()

    @get:Rule val rule = createComposeRule()

    private fun drawn(theme: Theme): List<DrawnRun> {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        f.vm.openDocument(f.file("code.md", SAMPLE))
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
                    codeTheme = theme,
                )
            }
        }
        rule.waitForIdle()
        val code = checkNotNull(view) { "the Code view never attached" }
        return rule.runOnIdle { DrawnRuns.of(code) }
    }

    @Test
    fun `fenced Python and TypeScript are coloured like Preview`() {
        val runs = drawn(BuiltInThemes.CLEAN)
        val syntax = if (dark) SyntaxColors.DARK else SyntaxColors.LIGHT

        fun colourOf(text: String) = runs.first { it.text == text }.color and RGB

        assertEquals("def is a keyword", syntax.keyword.rgb, colourOf("def"))
        assertEquals("greet is a function name", syntax.title.rgb, colourOf("greet"))
        assertEquals("the comment", syntax.comment.rgb, colourOf("# say hi"))
        assertEquals("the string", syntax.string.rgb, colourOf("\"hi\""))
        assertEquals("the number", syntax.number.rgb, colourOf("42"))
        assertEquals("interface is a keyword", syntax.keyword.rgb, colourOf("interface"))
        assertEquals("string is a type", syntax.keyword.rgb, colourOf("string"))
        // The fence keeps the Markdown styling (muted), and the text after the closing fence is prose (ink).
        val colors = if (dark) SSDarkColors else SSLightColors
        assertEquals(colors.inkMuted.toArgb(), runs.first { it.text.startsWith("```") }.color)
        assertEquals(colors.ink.toArgb(), runs.single { it.text == "After the code." }.color)
    }

    @Test
    fun `the Contrast theme's AAA code colours reach the Code pane`() {
        val runs = drawn(BuiltInThemes.CONTRAST)
        val syntax = if (dark) SyntaxColors.CONTRAST_DARK else SyntaxColors.CONTRAST_LIGHT
        assertEquals(syntax.keyword.rgb, runs.first { it.text == "def" }.color and RGB)
        assertEquals(syntax.comment.rgb, runs.first { it.text == "# say hi" }.color and RGB)
    }

    @Test
    fun `every code colour reads at AA on the Code pane's code band, in every built-in theme`() {
        val colors = if (dark) SSDarkColors else SSLightColors
        for (theme in BuiltInThemes.ALL) {
            val variant = if (dark) theme.dark else theme.light
            val editor = EditorTheme.fromColors(colors, syntax = variant.syntax)
            val band = ThemeColor.of(editor.codeBlockBackground)
            for (color in editor.codeColours()) {
                val ratio = Contrast.ratio(ThemeColor.of(color), band)
                assertTrue(
                    "${theme.name}: ${Integer.toHexString(color)} is $ratio:1",
                    ratio >= EditorTheme.MIN_CODE_CONTRAST,
                )
            }
        }
    }

    private fun EditorTheme.codeColours() =
        listOf(
            codeComment,
            codeKeyword,
            codeString,
            codeNumber,
            codeTitle,
            codeAttr,
            codeBuiltIn,
            codeMeta,
            codeDeletion,
        )

    companion object {
        private const val RGB = 0xFFFFFF

        private val SAMPLE =
            """
            # Code

            ```python
            def greet(name):
                # say hi
                return "hi", 42
            ```

            ```ts
            interface User { name: string }
            ```

            After the code.
            """.trimIndent()

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun themes(): List<Boolean> = listOf(false, true)
    }
}
