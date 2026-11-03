package com.scorchsoft.ssmarkdown.editor

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.scorchsoft.ssmarkdown.editor.panes.CodePane
import com.scorchsoft.ssmarkdown.fixtures.Samples
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextDocument
import com.scorchsoft.ssmarkdown.ui.EditorWith
import com.scorchsoft.ssmarkdown.ui.Themed
import com.scorchsoft.ssmarkdown.ui.strip.StripRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings → Formatting bar in the editor: two rows where there is room,
 * one in landscape and above a short editor, and the caret kept on screen
 * when the strip grows (docs/edge-cases.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class StripRowsScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val rows = mutableStateOf(StripRows.Two)
    private var codeView: MarkdownCodeView? = null

    /** The editor with the real Code pane over the sample, and the keyboard up ([KEYBOARD_DP]). */
    private fun showWithKeyboard() {
        lateinit var root: View
        rule.setContent {
            root = LocalView.current
            Themed(dark = false) {
                val text = remember { TextDocument(PieceTree.of(Samples.codeSample)) }
                EditorWith(
                    Samples.unsaved,
                    codePane = { modifier ->
                        CodePane(text, wrap = true, zoomPercent = 100, modifier = modifier, onViewReady = {
                            codeView =
                                it
                        })
                    },
                    stripRows = rows.value,
                )
            }
        }
        rule.waitForIdle()
        rule.runOnIdle {
            val ime = (KEYBOARD_DP * root.resources.displayMetrics.density).toInt()
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
                    .setVisible(WindowInsetsCompat.Type.ime(), true)
                    .build()
            ViewCompat.dispatchApplyWindowInsets(root, insets)
        }
        rule.waitForIdle()
    }

    private fun stripHeightDp(): Float =
        rule
            .onNodeWithContentDescription("Formatting")
            .fetchSemanticsNode()
            .size.height / rule.density.density

    @Test
    fun aPortraitPhoneGetsTwoRows() {
        showWithKeyboard()
        assertEquals(96f, stripHeightDp(), 0.5f)
    }

    @Test
    fun oneRowIsTheDefault() {
        rows.value = StripRows.One
        showWithKeyboard()
        assertEquals(48f, stripHeightDp(), 0.5f)
    }

    @Test
    @Config(qualifiers = "w915dp-h412dp-land-420dpi")
    fun landscapeAlwaysUsesOneRow() {
        showWithKeyboard()
        assertEquals(48f, stripHeightDp(), 0.5f)
    }

    @Test
    @Config(qualifiers = "w600dp-h560dp-420dpi")
    fun aWideShortWindowUsesOneRow() {
        // A split-screen half or a fold's outer screen: wider than tall.
        showWithKeyboard()
        assertEquals(48f, stripHeightDp(), 0.5f)
    }

    @Test
    @Config(qualifiers = "w412dp-h600dp-420dpi")
    fun aShortPortraitWindowUsesOneRow() {
        // With a 300 dp keyboard, two rows would leave the editor under 200 dp.
        showWithKeyboard()
        assertEquals(48f, stripHeightDp(), 0.5f)
    }

    @Test
    fun theCaretOnTheLastVisibleLineStaysVisibleWhenTheStripGrowsToTwoRows() {
        rows.value = StripRows.One
        showWithKeyboard()
        val view = codeView ?: error("no Code view")
        val before = view.height
        val caret = view.offsetAt(LEFT_PX, before - 2f)
        rule.runOnIdle { view.setCaret(caret) }
        rule.waitForIdle()
        val top = view.yForOffset(caret)
        assertTrue("the caret starts on screen ($top of $before)", top >= 0f && top < before)
        val rowHeight = top - view.yForOffset(view.offsetAt(LEFT_PX, top - 1f))
        assertTrue(rowHeight > 0f)

        rows.value = StripRows.Two
        rule.waitForIdle()

        val after = view.height
        // The pane gave up exactly the strip's measured second row.
        assertEquals(48f, (before - after) / rule.density.density, 1f)
        val moved = view.yForOffset(caret)
        assertTrue(
            "the caret row ($moved..${moved + rowHeight}) is inside 0..$after",
            moved >= 0f && moved + rowHeight <= after + 0.5f,
        )
        assertEquals(96f, stripHeightDp(), 0.5f)
    }

    private companion object {
        const val KEYBOARD_DP = 300
        const val LEFT_PX = 120f
    }
}
