package com.scorchsoft.ssmarkdown.find

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorScreen
import com.scorchsoft.ssmarkdown.editor.MarkdownCodeView
import com.scorchsoft.ssmarkdown.editor.panes.CodePaneHost
import com.scorchsoft.ssmarkdown.editor.panes.PreviewPlaceholder
import com.scorchsoft.ssmarkdown.editor.sheets.ThemeControlsPlaceholder
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.fixtures.Samples
import com.scorchsoft.ssmarkdown.fixtures.TapCounter
import com.scorchsoft.ssmarkdown.settings.Appearance
import com.scorchsoft.ssmarkdown.ui.Themed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The phone's journey (edge-cases "Find next does not move"): the real
 * editor screen with the real Code pane, wrapped as the app wraps it.
 * Type a query, then step with the find bar's arrow and the keyboard's
 * search key: each match is selected and on screen above the bar's edge.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class FindNavigationScreenTest {
    @get:Rule val rule = createComposeRule()

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `typing a query then tapping next three times selects and shows each match`() {
        // Long paragraphs, each many wrapped rows, with the needle at their ends.
        val para = "The quick brown fox jumps over the lazy dog. ".repeat(60)
        val text = (0 until 12).joinToString("") { "$para needle $it.\n\n" }
        val starts = Regex("needle").findAll(text).map { it.range.first }.toList()
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        f.vm.openDocument(f.file("f.md", text))
        var codeView: MarkdownCodeView? = null
        rule.setContent {
            Themed(dark = false) {
                val state by f.vm.uiState.collectAsState()
                val active by f.vm.activeDocument.collectAsState()
                EditorScreen(
                    state = state,
                    onAction = f.vm::onAction,
                    codePane = { m ->
                        CodePaneHost(active, state.codeWrap, state.codeZoom, {
                            codeView = it
                            f.vm.attach(it)
                        }, f.vm::detach, { it.run() }, m)
                    },
                    previewPane = { PreviewPlaceholder(it) },
                    previewState = Samples.previewState,
                    appearance = Appearance.System,
                    themeControls = { ThemeControlsPlaceholder() },
                )
            }
        }
        rule.runOnIdle { f.vm.onAction(EditorAction.Find(FindAction.Open)) }
        rule.waitForIdle()
        rule.onNodeWithText("Find").performTextInput("needle")
        rule.waitForIdle()
        val taps = TapCounter(rule)
        for (step in 0..3) {
            val i = step % starts.size
            val view = checkNotNull(codeView)
            rule.runOnIdle {
                assertEquals(
                    "counter at $step",
                    i,
                    f.vm.uiState.value.find
                        ?.current,
                )
                assertEquals("selection at $step", starts[i], view.session.selectionStart)
                assertEquals("selection end at $step", starts[i] + 6, view.session.selectionEnd)
                val y = view.yForOffset(starts[i])
                assertTrue(
                    "match $i on screen at $step: y=$y h=${view.height}",
                    y >= 0 && y + view.rowHeight <= view.height,
                )
            }
            if (step == 1) {
                rule.onNodeWithText("needle").performImeAction()
                rule.waitForIdle()
            } else {
                taps.tap("Next match")
            }
        }
    }
}
