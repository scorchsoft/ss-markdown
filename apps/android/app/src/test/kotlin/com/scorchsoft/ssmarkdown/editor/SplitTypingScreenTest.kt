package com.scorchsoft.ssmarkdown.editor

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.scorchsoft.ssmarkdown.editor.panes.CodePane
import com.scorchsoft.ssmarkdown.editor.panes.PreviewPlaceholder
import com.scorchsoft.ssmarkdown.editor.panes.SPLIT_PANES_TAG
import com.scorchsoft.ssmarkdown.fixtures.Samples
import com.scorchsoft.ssmarkdown.settings.Appearance
import com.scorchsoft.ssmarkdown.settings.SplitTyping
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextDocument
import com.scorchsoft.ssmarkdown.ui.Themed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Split while typing (ux-spec "Split while typing", docs/edge-cases.md):
 * with the keyboard up for one pane, that pane fills the page and the other
 * slides out of view; when typing ends both come back. Through all of it
 * both views stay attached to the window, because a focused view that
 * leaves the window takes the keyboard with it ("Split: the keyboard showed
 * and went away"). Real views stand in for both panes, so a detach is seen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SplitTypingScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val split = Samples.unsaved.copy(mode = ViewMode.Split)
    private val state = mutableStateOf(split)
    private val setting = mutableStateOf(SplitTyping.EditedPane)
    private var codeView: MarkdownCodeView? = null
    private var pageView: View? = null
    private var detaches = 0
    private lateinit var root: View

    private val counter =
        object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(v: View) {
                detaches++
            }
        }

    private fun show() {
        rule.setContent {
            root = LocalView.current
            Themed(dark = false) {
                val text = remember { TextDocument(PieceTree.of(Samples.codeSample)) }
                EditorScreen(
                    state = state.value,
                    onAction = {},
                    codePane = { modifier ->
                        CodePane(text, wrap = true, zoomPercent = 100, modifier = modifier, onViewReady = {
                            if (codeView !== it) it.addOnAttachStateChangeListener(counter)
                            codeView = it
                        })
                    },
                    previewPane = {
                        // A real view as the page, so leaving the window is counted.
                        AndroidView(factory = { context ->
                            View(context).also {
                                it.addOnAttachStateChangeListener(counter)
                                pageView = it
                            }
                        })
                    },
                    previewState = Samples.previewState,
                    appearance = Appearance.System,
                    splitTyping = setting.value,
                )
            }
        }
        rule.waitForIdle()
    }

    private fun keyboard(up: Boolean) {
        rule.runOnIdle {
            val ime = if (up) (KEYBOARD_DP * root.resources.displayMetrics.density).toInt() else 0
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
                    .setVisible(WindowInsetsCompat.Type.ime(), up)
                    .build()
            ViewCompat.dispatchApplyWindowInsets(root, insets)
        }
        rule.waitForIdle()
    }

    /** The keyboard opens for [pane]: what the view model does on the device. */
    private fun typeIn(pane: Pane) {
        keyboard(up = true)
        state.value = EditorReducer.typingIn(state.value.copy(keyboardUp = true), pane)
        rule.waitForIdle()
    }

    /** The keyboard closes: the reducer's answer to ImeVisibilityChanged(false). */
    private fun closeKeyboard() {
        keyboard(up = false)
        state.value = EditorReducer.reduce(state.value, EditorAction.ImeVisibilityChanged(false))
        rule.waitForIdle()
    }

    /**
     * Whether some of [view] is inside Split's pane area: a pane moved out
     * of view stays attached (that is the fix), so only its place says.
     * Outside stacked or side-by-side Split (a folded window) the whole
     * window counts.
     */
    private fun onScreen(view: View?): Boolean {
        val v = view ?: return false
        rule.waitForIdle()
        val tagged = rule.onAllNodesWithTag(SPLIT_PANES_TAG).fetchSemanticsNodes()
        val area = tagged.firstOrNull()?.boundsInWindow ?: return false
        var shown = false
        rule.runOnIdle {
            val at = IntArray(2)
            v.getLocationInWindow(at)
            val horizontal = minOf(at[0] + v.width.toFloat(), area.right) - maxOf(at[0].toFloat(), area.left)
            val vertical = minOf(at[1] + v.height.toFloat(), area.bottom) - maxOf(at[1].toFloat(), area.top)
            shown = v.isAttachedToWindow && horizontal > 1f && vertical > 1f
        }
        return shown
    }

    @Test
    fun typingInCodeHidesThePageAndClosingTheKeyboardBringsItBack() {
        show()
        val code = codeView
        assertTrue(onScreen(codeView) && onScreen(pageView))
        val before = codeView!!.height
        typeIn(Pane.Code)
        assertTrue("Code is on screen", onScreen(codeView))
        assertFalse("the page left the screen", onScreen(pageView))
        assertTrue("Code took the room (${codeView!!.height} vs $before)", codeView!!.height > before / 2)
        closeKeyboard()
        assertTrue(onScreen(codeView) && onScreen(pageView))
        assertEquals("Split is still the mode", ViewMode.Split, state.value.mode)
        assertSame("the same Code view throughout", code, codeView)
        assertEquals("no pane left the window", 0, detaches)
    }

    @Test
    fun typingInPreviewHidesCodeAndClosingTheKeyboardBringsItBack() {
        show()
        typeIn(Pane.Preview)
        assertTrue("the page is on screen", onScreen(pageView))
        assertFalse("Code left the screen", onScreen(codeView))
        closeKeyboard()
        assertTrue(onScreen(codeView) && onScreen(pageView))
        assertEquals("no pane left the window", 0, detaches)
    }

    @Test
    fun keepBothPanesKeepsThemWhileTyping() {
        setting.value = SplitTyping.BothPanes
        show()
        typeIn(Pane.Code)
        assertTrue(onScreen(codeView) && onScreen(pageView))
        typeIn(Pane.Preview)
        assertTrue(onScreen(codeView) && onScreen(pageView))
        assertEquals("no pane left the window", 0, detaches)
    }

    @Test
    fun theKeyboardAloneMovesNoPaneOutOfTheWindow() {
        // The 0.2.2 bug's mechanism: the keyboard's arrival rebuilt stacked Split and detached both views.
        setting.value = SplitTyping.BothPanes
        show()
        keyboard(up = true)
        state.value = state.value.copy(keyboardUp = true)
        rule.waitForIdle()
        keyboard(up = false)
        assertEquals("no pane left the window", 0, detaches)
    }

    @Test
    @Config(qualifiers = "w915dp-h412dp-land-420dpi")
    fun sideBySideKeepsBothPanesWhileTyping() {
        show()
        typeIn(Pane.Code)
        assertTrue(onScreen(codeView) && onScreen(pageView))
        typeIn(Pane.Preview)
        assertTrue(onScreen(codeView) && onScreen(pageView))
    }

    @Test
    fun aModeSwitchEndsTyping() {
        show()
        typeIn(Pane.Code)
        val code = EditorReducer.reduce(state.value, EditorAction.SelectMode(ViewMode.Code))
        val back = EditorReducer.reduce(code, EditorAction.SelectMode(ViewMode.Split))
        state.value = back
        rule.waitForIdle()
        assertTrue(onScreen(codeView) && onScreen(pageView))
    }

    @Test
    fun theCaretStaysVisibleWhenCodeFillsThePage() {
        show()
        keyboard(up = true)
        state.value = state.value.copy(keyboardUp = true)
        rule.waitForIdle()
        val view = codeView ?: error("no Code view")
        val before = view.height
        // The caret on the last line the half-height pane shows.
        val caret = view.offsetAt(LEFT_PX, before - 2f)
        rule.runOnIdle { view.setCaret(caret) }
        rule.waitForIdle()
        val top = view.yForOffset(caret)
        assertTrue("the caret starts on screen ($top of $before)", top >= 0f && top < before)

        state.value = EditorReducer.typingIn(state.value, Pane.Code)
        rule.waitForIdle()

        val after = view.height
        assertTrue("Code grew ($before -> $after)", after > before)
        val moved = view.yForOffset(caret)
        assertTrue("the caret ($moved) is inside 0..$after", moved >= 0f && moved < after)
        assertEquals("the top line did not move", top, moved, 0.5f)
    }

    @Test
    @Config(qualifiers = "w412dp-h300dp-420dpi")
    fun aTooShortWindowShowsThePageBeingTypedIn() {
        // Too short to stack folds Preview into its strip; typing in it must not leave it folded away.
        setting.value = SplitTyping.BothPanes
        show()
        assertFalse(onScreen(pageView))
        // A hardware keyboard: no inset, so the page keeps its height.
        state.value = EditorReducer.typingIn(state.value.copy(keyboardUp = true), Pane.Preview)
        rule.waitForIdle()
        assertTrue(onScreen(pageView))
        assertEquals(0, detaches)
    }

    @Test
    fun aHiddenPaneIsNotOfferedToAccessibility() {
        rule.setContent {
            Themed(dark = false) {
                EditorScreen(
                    state = split.copy(keyboardUp = true, typingPane = Pane.Code),
                    onAction = {},
                    codePane = {
                        com.scorchsoft.ssmarkdown.fixtures
                            .FakeCodePane(it)
                    },
                    previewPane = { PreviewPlaceholder(it) },
                    previewState = Samples.previewState,
                    appearance = Appearance.System,
                    modifier = Modifier,
                )
            }
        }
        val placeholder =
            androidx.compose.ui.test
                .hasText("Preview draws here", substring = true)
        assertEquals(0, rule.onAllNodes(placeholder).fetchSemanticsNodes().size)
    }

    private companion object {
        const val KEYBOARD_DP = 300
        const val LEFT_PX = 120f
    }
}
