package com.scorchsoft.ssmarkdown.ui.strip

import android.view.View
import android.view.WindowManager
import android.widget.EditText
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.scorchsoft.ssmarkdown.core.ui.SSMarkdownTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private val blocks = mutableListOf<BlockChoice>()

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
                    onBlock = { blocks += it },
                )
            }
        }

    private fun node(label: String) = rule.onNodeWithContentDescription(label)

    @Test
    fun codeStripCarriesEveryKeyAndPinsUndoAndSymbols() {
        show(FormattingStripState(canUndo = true))
        for (label in listOf("Block style", "Bold", "Italic", "Bulleted list", "Task", "Code", "Link", "Indent")) {
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
        val redo = node("Redo").fetchSemanticsNode().boundsInRoot
        val done = node("Done").fetchSemanticsNode().boundsInRoot
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val density = rule.density.density
        assertEquals(48f, undo.width / density, 0.5f)
        assertEquals(48f, done.width / density, 0.5f)
        // Undo and Redo are a pair, then Done.
        assertEquals(undo.right, redo.left, 0.5f)
        assertEquals(redo.right, done.left, 0.5f)
        assertEquals(root.right, done.right, 0.5f)
        // The last visible scrolling key is cut at Undo's left edge, about half of it showing.
        // Unclipped bounds: the key cut by the edge is wider than what shows of it.
        val cut =
            listOf(
                "Block style",
                "Bold",
                "Italic",
                "Code",
                "Link",
                "Bulleted list",
                "Numbered list",
                "Outdent",
                "Indent",
                "Table",
            ).map { node(it).fetchSemanticsNode() }
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
        node("Redo").assertIsNotEnabled()
        node("Undo").performClick()
        assertTrue(keys.isEmpty())
    }

    @Test
    fun undoIsEnabledWhenThereIsHistory() {
        show(FormattingStripState(canUndo = true, canRedo = true))
        node("Undo").assertIsEnabled()
        node("Redo").assertIsEnabled()
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
    fun blockStyleKeyOpensEveryTypeWithTheCurrentOneTicked() {
        show(FormattingStripState(block = BlockChoice.Heading2))
        node("Block style").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Heading 2"))
        node("Block style").performClick()
        val labels =
            "Paragraph|Heading 1|Heading 2|Heading 3|Bulleted list|Numbered list|Task|Quote|Code block".split(
                '|',
            )
        for (label in labels) {
            // 48 dp rows, read as radio buttons with the current one selected.
            val row = rule.onNode(hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
            row.assertHeightIsAtLeast(48.dp)
            if (label == "Heading 2") row.assertIsSelected() else row.assertIsNotSelected()
        }
        rule.onNode(hasText("Task") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).performClick()
        assertEquals(listOf(BlockChoice.Task), blocks)
        // The menu closes after a choice, and ¶ itself is not an edit key.
        rule.onNodeWithText("Code block").assertDoesNotExist()
        assertTrue(keys.isEmpty())
    }

    /**
     * Andrew, 0.2.1: ¶ put the keyboard away. The menu was a focusable popup
     * window, which takes focus from the editor's window; the keyboard went,
     * the strip (shown only with the keyboard) went with the menu, and no
     * heading could ever be chosen. The menu must be a window that cannot
     * take focus, and the editor must keep focus through a choice.
     */
    @Test
    fun blockMenuNeverTakesFocusFromTheEditor() {
        val editor = showWithEditor(FormattingStripState())
        node("Block style").performClick()
        rule.onNodeWithText("Heading 1").assertExists()
        val menu = popupWindows().single()
        val flags = (menu.layoutParams as WindowManager.LayoutParams).flags
        assertTrue(
            "the ¶ menu window must be FLAG_NOT_FOCUSABLE",
            flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0,
        )
        assertTrue(editor.hasFocus())
        rule.onNode(hasText("Heading 1") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).performClick()
        assertEquals(listOf(BlockChoice.Heading1), blocks)
        assertTrue(editor.hasFocus())
        assertTrue(popupWindows().isEmpty())
    }

    @Test
    fun tableMenuNeverTakesFocusFromTheEditor() {
        val editor = showWithEditor(FormattingStripState(inTable = true))
        node("Table actions").performClick()
        val flags = (popupWindows().single().layoutParams as WindowManager.LayoutParams).flags
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        rule.onNodeWithText("Delete row").performClick()
        assertTrue(editor.hasFocus())
    }

    /** Tapping B (or any key) leaves focus, and so the keyboard, with the editor. */
    @Test
    fun stripKeysNeverTakeFocus() {
        val editor = showWithEditor(FormattingStripState(canUndo = true))
        node("Bold").performClick()
        node("Undo").performClick()
        node("Symbols").performClick()
        assertTrue(editor.hasFocus())
        // Not even when asked (a hardware keyboard's Tab, an accessibility service): no key can take focus.
        for (label in listOf("Block style", "Bold", "Italic", "Undo", "Symbols")) {
            node(label).performSemanticsAction(SemanticsActions.RequestFocus)
            node(label).assert(SemanticsMatcher.expectValue(SemanticsProperties.Focused, false))
            assertTrue("$label took focus from the editor", editor.hasFocus())
        }
        assertFalse(keys.isEmpty())
    }

    private fun showWithEditor(state: FormattingStripState): EditText {
        lateinit var editor: EditText
        rule.setContent {
            SSMarkdownTheme(darkTheme = false) {
                Column {
                    AndroidView(factory = { EditText(it).also { e -> editor = e } })
                    FormattingStrip(
                        state = state,
                        onKey = { keys += it },
                        onSymbol = { symbols += it },
                        onCursor = { steps += it },
                        onTableAction = { actions += it },
                        onBlock = { blocks += it },
                    )
                }
            }
        }
        rule.runOnIdle { editor.requestFocus() }
        rule.waitForIdle()
        assertTrue(editor.hasFocus())
        return editor
    }

    /** Every window above the activity's own: the popups. */
    private fun popupWindows(): List<View> {
        rule.waitForIdle()
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        val views = global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance)
        val decor = rule.activity.window.decorView
        return (views as List<*>).filterIsInstance<View>().filter { it !== decor && it.isAttachedToWindow }
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
