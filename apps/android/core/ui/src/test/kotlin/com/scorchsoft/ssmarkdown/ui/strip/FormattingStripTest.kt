package com.scorchsoft.ssmarkdown.ui.strip

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
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
    fun pinnedGroupIsExactlyItsKeysAndTheScrollingKeysRunUpToIt() {
        // Andrew, 2026-11-02: a gap before Undo made the pinned group look too wide.
        show(FormattingStripState(mode = StripMode.Preview, canUndo = true))
        val undo = node("Undo").fetchSemanticsNode().boundsInRoot
        val done = node("Done").fetchSemanticsNode().boundsInRoot
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val density = rule.density.density
        assertEquals(48f, undo.width / density, 0.5f)
        assertEquals(48f, done.width / density, 0.5f)
        assertEquals(undo.right, done.left, 0.5f)
        assertEquals(root.right, done.right, 0.5f)
        // The last visible scrolling key is cut at Undo's left edge, about half of it showing.
        // Unclipped bounds: the key cut by the edge is wider than what shows of it.
        val cut =
            listOf("Block style", "Bold", "Italic", "Code", "Link", "Bulleted list", "Table")
                .map { node(it).fetchSemanticsNode() }
                .map { it.positionInRoot.x to it.size.width.toFloat() }
                .first { (left, width) -> left + width > undo.left + 0.5f }
        val shown = (undo.left - cut.first) / cut.second
        assertEquals(0.5f, shown, 0.02f)
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
        node("Symbols").assertIsOn().performClick()
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
    fun landscapeSaveFollowsTheSaveStateTable() {
        // UX-9: with the header gone, the strip must not say "Save" for a draft or a failed save.
        show(FormattingStripState(document = StripDocument("Untitled", isDirty = true, save = StripSave.SaveAs)))
        node("Save as…").assertExists()
        node("Save").assertDoesNotExist()
    }

    @Test
    fun landscapeShowsAFailedSaveAndOffersRetry() {
        val failed =
            StripDocument(
                "notes.md",
                isDirty = true,
                save = StripSave.Retry,
                status = "Couldn't save · storage full",
                statusIsError = true,
            )
        show(FormattingStripState(document = failed))
        rule.onNodeWithText("Couldn't save · storage full").assertExists()
        node("Retry").performClick()
        assertEquals(1, saves)
    }

    @Test
    fun landscapeSavingIsNotTappable() {
        show(FormattingStripState(document = StripDocument("notes.md", isDirty = true, save = StripSave.Saving)))
        node("Saving").assertIsNotEnabled()
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
