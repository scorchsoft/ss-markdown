package com.scorchsoft.ssmarkdown.editor

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.scorchsoft.ssmarkdown.core.ui.components.FastScrollDefaults
import com.scorchsoft.ssmarkdown.editor.panes.CodePaneHost
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.ui.Themed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Code pane's fast-scroll thumb in the real [CodePaneHost]
 * (docs/ux-spec.md, "Fast scroll"): a swipe shows it, dragging it moves
 * the view by line with the section in the bubble and leaves the caret
 * alone, and a sideways pan in No wrap never shows it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h800dp")
class CodeFastScrollThumbTest {
    @get:Rule val temp = TemporaryFolder()

    @get:Rule val rule = createComposeRule()

    private var view: MarkdownCodeView? = null

    private fun open(
        text: String,
        wrap: Boolean = true,
    ): EditorFixture {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        f.vm.openDocument(f.file("long.md", text))
        rule.setContent {
            Themed(dark = false) {
                val active by f.vm.activeDocument.collectAsState()
                CodePaneHost(
                    active,
                    wrap = wrap,
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
        return f
    }

    private fun thumbShown() =
        rule
            .onAllNodes(
                SemanticsMatcher.expectValue(SemanticsProperties.TestTag, FastScrollDefaults.THUMB_TAG),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            .isNotEmpty()

    private val headed =
        (0 until LINES).joinToString("\n") {
            if (it % SECTION ==
                0
            ) {
                "# Part ${it / SECTION}"
            } else {
                "line $it"
            }
        }

    @Test
    fun `a swipe shows the thumb, and dragging it moves the view by line, names the section, and leaves the caret`() {
        open(headed)
        val code = checkNotNull(view)
        assertFalse("not at rest", thumbShown())
        rule.onRoot().performTouchInput { swipeUp() }
        rule.waitForIdle()
        assertTrue("the swipe scrolled", code.scrollY > 0)
        assertTrue("and showed the thumb", thumbShown())
        val caret = code.session.caret

        val tops = ArrayList<Int>()
        val thumb = rule.onNodeWithTag(FastScrollDefaults.THUMB_TAG, useUnmergedTree = true)
        thumb.performTouchInput { down(center) }
        repeat(STEPS) {
            thumb.performTouchInput { moveBy(Offset(0f, STEP_PX)) }
            rule.waitForIdle()
            tops += code.anchorLine
        }
        assertTrue("forwards only: $tops", tops.zipWithNext().all { (a, b) -> b >= a })
        assertTrue("well down the file: $tops", tops.last() > SECTION * 2)
        val section = code.anchorLine / SECTION
        rule.onNodeWithText("Part $section", useUnmergedTree = true).assertIsDisplayed()

        thumb.performTouchInput { up() }
        rule.waitForIdle()
        val rested = code.anchorLine
        rule.waitForIdle()
        assertEquals("let go, the view stays", rested, code.anchorLine)
        assertEquals("the caret never moved", caret, code.session.caret)
    }

    @Test
    fun `with no heading above the top, the bubble shows the line number`() {
        open((0 until LINES).joinToString("\n") { "line $it" })
        val code = checkNotNull(view)
        rule.onRoot().performTouchInput { swipeUp() }
        rule.waitForIdle()
        val thumb = rule.onNodeWithTag(FastScrollDefaults.THUMB_TAG, useUnmergedTree = true)
        thumb.performTouchInput {
            down(center)
            moveBy(Offset(0f, STEP_PX * 3))
        }
        rule.waitForIdle()
        rule.onNodeWithText("Line ${code.anchorLine + 1}", useUnmergedTree = true).assertIsDisplayed()
        thumb.performTouchInput { up() }
    }

    @Test
    fun `a sideways pan in No wrap never shows the thumb`() {
        open((0 until LINES).joinToString("\n") { "wide line $it " + "x".repeat(WIDE) }, wrap = false)
        val code = checkNotNull(view)
        rule.onRoot().performTouchInput { down(center) }
        rule.runOnIdle { code.scrollTo(SIDEWAYS_PX, code.scrollY) }
        rule.waitForIdle()
        assertEquals(SIDEWAYS_PX, code.scrollX)
        assertFalse(thumbShown())
        rule.onRoot().performTouchInput { cancel() }
    }

    @Test
    fun `a short file never shows it`() {
        open((0 until SHORT).joinToString("\n") { "line $it" })
        rule.onRoot().performTouchInput { swipeUp() }
        rule.waitForIdle()
        assertTrue("it scrolled", checkNotNull(view).scrollY > 0)
        assertFalse(thumbShown())
    }

    private companion object {
        const val LINES = 3_000
        const val SECTION = 100
        const val STEPS = 8
        const val STEP_PX = 60f
        const val WIDE = 300
        const val SIDEWAYS_PX = 200
        const val SHORT = 60
    }
}
