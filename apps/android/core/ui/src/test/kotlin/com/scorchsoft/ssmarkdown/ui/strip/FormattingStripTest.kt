package com.scorchsoft.ssmarkdown.ui.strip

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.core.ui.SSMarkdownTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp")
class FormattingStripTest {
    private val activity = ComposeActivityRule()

    @get:Rule val rules = activity.chain
    private val rule get() = activity.compose

    private val keys = mutableListOf<StripKey>()
    private val symbols = mutableListOf<String>()
    private val steps = mutableListOf<CursorStep>()
    private var saves = 0
    private val actions = mutableListOf<TableAction>()

    private fun show(state: FormattingStripState) =
        rule.setContent {
            SSMarkdownTheme(darkTheme = false) {
                FormattingStrip(
                    state = state,
                    onKey = { keys += it },
                    onSymbol = { symbols += it },
                    onCursor = { steps += it },
                    onSave = { saves++ },
                    onTableAction = { actions += it },
                )
            }
        }

    private fun node(label: String) = rule.onNodeWithContentDescription(label)

    @Test
    fun codeStripCarriesEveryKeyAndPinsUndoAndSymbols() {
        show(FormattingStripState(canUndo = true))
        for (label in listOf("Heading", "Bold", "Italic", "Bulleted list", "Task", "Code", "Link", "Indent")) {
            node(label).assertExists()
        }
        // Past the peek, reachable by scrolling.
        node("Outdent").performScrollTo().performClick()
        node("Undo").performClick()
        node("Symbols").performClick()
        assertEquals(listOf(StripKey.Outdent, StripKey.Undo, StripKey.Symbols), keys)
        node("Done").assertDoesNotExist()
    }

    @Test
    fun togglesReportTheirPressedState() {
        show(FormattingStripState(pressed = setOf(StripKey.Bold, StripKey.Link)))
        node("Bold").assertIsOn()
        node("Italic").assertIsOff()
        node("Bold").performClick()
        assertEquals(listOf(StripKey.Bold), keys)
    }

    @Test
    fun undoAndRedoFollowTheHistory() {
        show(FormattingStripState(canUndo = false, canRedo = false))
        node("Undo").assertIsNotEnabled()
        node("Redo").performScrollTo().assertIsNotEnabled()
        node("Undo").performClick()
        assertTrue(keys.isEmpty())
    }

    @Test
    fun undoIsEnabledWhenThereIsHistory() {
        show(FormattingStripState(canUndo = true, canRedo = true))
        node("Undo").assertIsEnabled()
        node("Redo").performScrollTo().assertIsEnabled()
    }

    @Test
    fun symbolsPageTypesSymbolsAndFlipsBack() {
        show(FormattingStripState(page = StripPage.Symbols))
        node("Bold").assertDoesNotExist()
        node("Open bracket").performClick()
        node("Backtick").performClick()
        assertEquals(listOf("[", "`"), symbols)
        node("Back to formatting").assertIsOn().performClick()
        assertEquals(listOf(StripKey.Symbols), keys)
    }

    @Test
    fun cursorKeyStepsOnceOnATap() {
        show(FormattingStripState(page = StripPage.Symbols))
        node("Cursor left").performScrollTo().performClick()
        assertEquals(listOf(CursorStep.Left), steps)
    }

    @Test
    fun cursorKeyRepeatsWhileHeld() {
        show(FormattingStripState(page = StripPage.Symbols))
        node("Cursor right").performScrollTo()
        rule.mainClock.autoAdvance = false
        node("Cursor right").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(HOLD_MS)
        node("Cursor right").performTouchInput { up() }
        rule.mainClock.autoAdvance = true
        assertTrue("held for ${HOLD_MS}ms, stepped ${steps.size} times", steps.size > 2)
        assertTrue(steps.all { it == CursorStep.Right })
    }

    @Test
    fun previewPinsUndoAndDoneAndHasNoSymbols() {
        show(FormattingStripState(mode = StripMode.Preview))
        node("Block style").assertExists()
        node("Symbols").assertDoesNotExist()
        node("Task").assertDoesNotExist()
        node("Done").performClick()
        assertEquals(listOf(StripKey.Done), keys)
    }

    @Test
    fun landscapeCarriesTheFileAndSave() {
        show(FormattingStripState(document = StripDocument("release.md", isDirty = true)))
        rule.onNodeWithText("release.md").assertExists()
        node("Unsaved").assertExists()
        node("Save").performClick()
        assertEquals(1, saves)
    }

    @Test
    fun landscapeCleanShowsTheTick() {
        show(FormattingStripState(document = StripDocument("release.md", isDirty = false)))
        node("Unsaved").assertDoesNotExist()
        node("Saved").assertExists()
    }

    @Test
    fun tableModePinsPrevAndNextCellAsFullSizeKeys() {
        show(FormattingStripState(canUndo = true, inTable = true))
        node("Previous cell").assertIsEnabled().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        node("Next cell").assertWidthIsAtLeast(48.dp).performClick()
        node("Previous cell").performClick()
        assertEquals(listOf(StripKey.NextCell, StripKey.PrevCell), keys)
        // Undo stays pinned beside them, and the formatting keys still scroll.
        node("Undo").assertExists()
        node("Bold").assertExists()
    }

    @Test
    fun tableActionsLiveBehindTheOverflowKey() {
        show(FormattingStripState(inTable = true))
        rule.onNodeWithText("Delete row").assertDoesNotExist()
        node("Table actions").performClick()
        rule.onNodeWithText("Add column right").assertExists()
        rule.onNodeWithText("Delete row").performClick()
        assertEquals(listOf(TableAction.DeleteRow), actions)
        // The menu closes after a choice, and ⋯ itself is not an edit key.
        rule.onNodeWithText("Add column right").assertDoesNotExist()
        assertTrue(keys.isEmpty())
    }

    @Test
    fun outsideATableThereAreNoCellKeys() {
        show(FormattingStripState())
        node("Previous cell").assertDoesNotExist()
        node("Next cell").assertDoesNotExist()
        node("Table actions").assertDoesNotExist()
    }

    private companion object {
        const val HOLD_MS = 700L
    }
}
