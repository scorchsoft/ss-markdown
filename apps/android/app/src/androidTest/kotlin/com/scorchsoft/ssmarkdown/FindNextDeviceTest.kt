package com.scorchsoft.ssmarkdown

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.MarkdownCodeView
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.find.FindAction
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.text.EditOp
import leakcanary.DetectLeaksAfterTestSuccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Find next on a device (edge-cases "Find next does not move"): type a
 * query in the find bar, tap Next three times, and after each tap the
 * match is the Code pane's selection and is on screen, even at the end of
 * a paragraph many wrapped rows long. If the keyboard was up, it stays up.
 */
@RunWith(AndroidJUnit4::class)
class FindNextDeviceTest {
    @get:Rule(order = 0)
    val leaks = DetectLeaksAfterTestSuccess()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun act(action: EditorAction) =
        onMain {
            rule.activity
                .graph()
                .editor
                .onAction(action)
        }

    private fun codeView(): MarkdownCodeView {
        fun find(view: View): MarkdownCodeView? =
            view as? MarkdownCodeView
                ?: (view as? ViewGroup)?.let { group ->
                    (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) }
                }
        return find(rule.activity.window.decorView) ?: error("no Code view on screen")
    }

    private fun keyboardUp(): Boolean =
        onMain {
            ViewCompat
                .getRootWindowInsets(rule.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }

    @Test
    fun nextSelectsEachMatchAndScrollsItIntoView() {
        val paragraph = "The quick brown fox jumps over the lazy dog. ".repeat(PARAGRAPH_REPEATS)
        val text = (0 until PARAGRAPHS).joinToString("") { "$paragraph$QUERY $it.\n\n" }
        val starts = Regex(QUERY).findAll(text).map { it.range.first }.toList()
        rule.waitUntil(TIMEOUT) {
            rule.activity
                .graph()
                .editor.active != null
        }
        onMain {
            val doc =
                rule.activity
                    .graph()
                    .editor.active ?: error("nothing open")
            doc.surface.apply(listOf(EditOp(0, doc.text.text.toString(), text)), TextSelection.caret(0))
        }
        act(EditorAction.SelectMode(ViewMode.Code))
        try {
            act(EditorAction.Find(FindAction.Open))
            rule.waitForIdle()
            rule.onNodeWithText("Find").performTextInput(QUERY)
            rule.waitUntil(TIMEOUT) {
                onMain {
                    rule.activity
                        .graph()
                        .editor.uiState.value.find
                        ?.matches
                        ?.size
                } ==
                    PARAGRAPHS
            }
            val keyboard = keyboardUp()
            for (step in 0..STEPS) {
                rule.waitForIdle()
                onMain {
                    val find =
                        rule.activity
                            .graph()
                            .editor.uiState.value.find ?: error("the find bar closed")
                    val view = codeView()
                    assertEquals("counter at step $step", step, find.current)
                    assertEquals("selection at step $step", starts[step], view.session.selectionStart)
                    assertEquals(starts[step] + QUERY.length, view.session.selectionEnd)
                    val y = view.yForOffset(starts[step])
                    assertTrue(
                        "match $step off screen: y=$y of ${view.height}",
                        y >= 0 && y + view.rowHeight <= view.height,
                    )
                }
                if (keyboard) assertTrue("Next closed the keyboard", keyboardUp())
                if (step < STEPS) rule.onNodeWithContentDescription("Next match").performClick()
            }
        } finally {
            act(EditorAction.Find(FindAction.Close))
        }
    }

    private companion object {
        const val QUERY = "needle"
        const val PARAGRAPHS = 8
        const val PARAGRAPH_REPEATS = 40
        const val STEPS = 3
        const val TIMEOUT = 10_000L
    }
}
