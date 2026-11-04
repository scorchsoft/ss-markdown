package com.scorchsoft.ssmarkdown.ui

import android.view.View
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.scorchsoft.ssmarkdown.document.ActiveDocument
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorEvent
import com.scorchsoft.ssmarkdown.editor.EditorOverlay
import com.scorchsoft.ssmarkdown.editor.EditorReducer
import com.scorchsoft.ssmarkdown.editor.LineChange
import com.scorchsoft.ssmarkdown.editor.chrome.MENU_ROWS_TAG
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.fixtures.Samples
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
import org.robolectric.RuntimeEnvironment

/**
 * The ⋮ menu (ux-spec "Header"): Undo · Redo as a pinned icon row on top,
 * the rows below never cut off on a small or sideways screen or at 2×
 * font, and opening it puts the keyboard away first.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class OverflowMenuTest {
    private companion object {
        const val KEYBOARD_DP = 300
    }

    @get:Rule val rule = createComposeRule()

    @get:Rule val temp = TemporaryFolder()

    private val actions = ArrayList<EditorAction>()

    private val open = Samples.saved.copy(overlay = EditorOverlay.Menu, canUndo = true, canRedo = false)

    private fun show(
        qualifiers: String,
        fontScale: Float = 1f,
    ) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        rule.setContent { Themed(dark = false, fontScale = fontScale) { EditorFor(open) { actions += it } } }
    }

    @Test
    fun `Undo and Redo sit on top as 48 dp icons, Redo disabled with nothing to redo`() {
        show("w412dp-h915dp-420dpi")
        rule
            .onNodeWithContentDescription("Undo")
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        rule.onNodeWithContentDescription("Redo").assertIsDisplayed().assertIsNotEnabled()
        rule.onNodeWithContentDescription("Undo").performClick()
        assertEquals(EditorAction.MenuHistory(redo = false), actions.last())
        assertEquals(
            "the menu stays open for another tap",
            EditorOverlay.Menu,
            EditorReducer.reduce(open, EditorAction.MenuHistory(redo = false)).overlay,
        )
    }

    private fun everyRowReachable() {
        rule.onNodeWithContentDescription("Undo").assertIsDisplayed()
        for (row in listOf("New", "Find & replace", "Export PDF", "Settings")) {
            rule.onNodeWithText(row).performScrollTo().assertIsDisplayed()
        }
        // The icon row is pinned: still there after scrolling to the last row.
        rule.onNodeWithContentDescription("Undo").assertIsDisplayed()
    }

    @Test
    fun `on a 360 x 640 phone every row is reachable`() {
        show("w360dp-h640dp-xhdpi")
        everyRowReachable()
    }

    @Test
    fun `on its side every row is reachable`() {
        show("w640dp-h360dp-land-xhdpi")
        everyRowReachable()
    }

    @Test
    fun `at 2x font every row is reachable`() {
        show("w360dp-h640dp-xhdpi", fontScale = 2f)
        everyRowReachable()
    }

    /**
     * Andrew, 2026-11-04: with the keyboard still up, the rows ran under it
     * and could not be scrolled to. They are held above it and scroll.
     */
    @Test
    fun `with the keyboard up the rows fit above it and every row is reachable`() {
        RuntimeEnvironment.setQualifiers("w412dp-h915dp-420dpi")
        lateinit var root: View
        rule.setContent {
            root = LocalView.current
            Themed(dark = false) { EditorFor(open) { actions += it } }
        }
        rule.waitForIdle()
        rule.runOnIdle { keyboard(root, up = true) }
        rule.waitForIdle()
        val rows =
            rule
                .onNodeWithTag(MENU_ROWS_TAG)
                .fetchSemanticsNode()
                .size.height / root.resources.displayMetrics.density
        // 915 dp, less the keyboard, the menu's margins and its icon row: well under the menu's full height.
        assertTrue("rows are $rows dp tall", rows <= 915 - KEYBOARD_DP - 112 - 48 + 1)
        // The formatting strip, up with the keyboard, has an Undo of its own: check the rows by name.
        for (row in listOf("New", "Find & replace", "Export PDF", "Settings")) {
            rule.onNodeWithText(row).performScrollTo().assertIsDisplayed()
        }
    }

    /** Dispatches a [KEYBOARD_DP] keyboard's inset to [root], or takes it away. */
    private fun keyboard(
        root: View,
        up: Boolean,
    ) {
        val ime = if (up) (KEYBOARD_DP * root.resources.displayMetrics.density).toInt() else 0
        val insets =
            WindowInsetsCompat
                .Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
                .setVisible(WindowInsetsCompat.Type.ime(), up)
                .build()
        ViewCompat.dispatchApplyWindowInsets(root, insets)
    }

    /** The editor with the keyboard up, then ⋮ pressed; the clock is driven by hand. */
    private fun openOverKeyboard(): View {
        RuntimeEnvironment.setQualifiers("w412dp-h915dp-420dpi")
        lateinit var root: View
        val state = mutableStateOf(Samples.saved)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            root = LocalView.current
            Themed(dark = false) { EditorFor(state.value) { actions += it } }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.runOnIdle { keyboard(root, up = true) }
        rule.mainClock.advanceTimeByFrame()
        state.value = open
        repeat(3) { rule.mainClock.advanceTimeByFrame() }
        return root
    }

    private fun menuShown(): Boolean = rule.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty()

    /**
     * Andrew, 2026-11-04: the menu showed while the keyboard was leaving, was
     * laid out in the room above it, and stayed small until touched. It waits
     * for the keyboard to go and then opens once, at full size.
     */
    @Test
    fun `the menu waits for the keyboard to go, then opens at full size`() {
        val root = openOverKeyboard()
        assertFalse("not drawn over the leaving keyboard", menuShown())
        rule.runOnIdle { keyboard(root, up = false) }
        repeat(3) { rule.mainClock.advanceTimeByFrame() }
        assertTrue("open once the keyboard has gone", menuShown())
        val rows =
            rule
                .onNodeWithTag(MENU_ROWS_TAG)
                .fetchSemanticsNode()
                .size.height / root.resources.displayMetrics.density
        assertTrue("rows are $rows dp tall: the full screen's room", rows > 915 - KEYBOARD_DP - 112 - 48)
    }

    @Test
    fun `a keyboard that stays only delays the menu briefly`() {
        openOverKeyboard()
        assertFalse(menuShown())
        rule.mainClock.advanceTimeBy(500)
        assertTrue("open after the wait, above the keyboard", menuShown())
    }

    private fun ActiveDocument.typeAtEnd(typed: String) {
        val at = text.text.length
        val line = text.text.positionAt(at).line
        text.replace(at, 0, typed)
        onTextEdited(LineChange(line, 0, typed.count { it == '\n' }), text.version)
    }

    @Test
    fun `opening the menu puts the keyboard away, and Undo and Redo work from it`() {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        f.vm.start()
        val doc = f.vm.active ?: error("nothing open")
        doc.typeAtEnd("typed")
        f.vm.onAction(EditorAction.OpenMenu)
        assertTrue("the keyboard is hidden for the menu", EditorEvent.HideKeyboard in f.events)

        f.vm.onAction(EditorAction.MenuHistory(redo = false))
        assertFalse(
            doc.text.text
                .toString()
                .contains("typed"),
        )
        assertTrue(f.vm.uiState.value.canRedo)
        assertEquals(EditorOverlay.Menu, f.vm.uiState.value.overlay)

        f.vm.onAction(EditorAction.MenuHistory(redo = true))
        assertTrue(
            doc.text.text
                .toString()
                .contains("typed"),
        )
        assertFalse(f.vm.uiState.value.canRedo)
    }
}
