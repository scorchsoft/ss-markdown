package com.scorchsoft.ssmarkdown

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scorchsoft.ssmarkdown.document.ActiveDocument
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorOverlay
import com.scorchsoft.ssmarkdown.editor.EditorUiState
import com.scorchsoft.ssmarkdown.editor.LaunchRequest
import com.scorchsoft.ssmarkdown.editor.MarkdownCodeView
import com.scorchsoft.ssmarkdown.editor.MenuItem
import com.scorchsoft.ssmarkdown.editor.PendingLeave
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.find.FindAction
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.text.EditOp
import leakcanary.DetectLeaksAfterTestSuccess
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The rotation matrix (docs/robustness.md §5a; rubric #3): "rotation,
 * folding, resizing or a theme change never loses a character, and keeps
 * the cursor, selection, scroll and undo". Each change is made the way the
 * system makes it (orientation, `wm size`, `cmd uimode`, the font-scale
 * setting), never by calling the Activity, and after each one the test
 * checks, in the Code pane:
 *
 * - the same Activity and the same buffer, with every character;
 * - the same selection (cursor and anchor);
 * - the same top line, in the Code view and in the shared anchor;
 * - the undo history: the edit made just before the change undoes exactly,
 *   and redoes.
 *
 * Folding is a resize to the folded and unfolded window: `wm size` is that
 * change on an emulator without a hinge. The last row is "Don't keep
 * activities": the one change that does build a new Activity, so it checks
 * the same things on the new one.
 *
 * The other tests rotate there and back in the states where losing
 * something is likeliest: Split, Preview (no Code view; the Preview's own
 * scroll mapping is the Playwright anchor test's), the middle of a
 * keyboard composition, and the caret inside a table cell. Find open, a
 * sheet open and the unsaved prompt open are rotated and then recreated,
 * and the open UI must still be there. Each rotates only twice, so the
 * whole class stays well under a minute or two. The `am kill` column
 * needs a process death between two runs: [BackgroundKillTest].
 */
@RunWith(AndroidJUnit4::class)
class RotationMatrixTest {
    // Outermost, so it checks for leaks after the Activity has been closed.
    @get:Rule(order = 0)
    val leaks = DetectLeaksAfterTestSuccess()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun shell(command: String) {
        val out = instrumentation.uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes() }
    }

    @After
    fun resetDevice() {
        shell("wm size reset")
        shell("cmd uimode night no")
        shell("settings put system font_scale 1.0")
        instrumentation.runOnMainSync {
            rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun active(): ActiveDocument =
        rule.activity
            .graph()
            .editor.active ?: error("nothing open")

    /** What must survive a configuration change, read on the main thread. */
    private data class Snapshot(
        val text: String,
        val selection: TextSelection,
        val codeTopLine: Int,
        val anchor: Int,
    )

    /** The Code pane's view, found in the window as the system draws it. */
    private fun codeView(): MarkdownCodeView {
        fun find(view: View): MarkdownCodeView? =
            view as? MarkdownCodeView
                ?: (view as? ViewGroup)?.let { group ->
                    (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) }
                }
        return find(rule.activity.window.decorView) ?: error("no Code view on screen")
    }

    private fun snapshot(): Snapshot =
        onMain {
            val vm = rule.activity.graph().editor
            val doc = active()
            Snapshot(doc.text.text.toString(), doc.surface.selection, codeView().anchorLine, vm.anchorLine.value)
        }

    /** Waits until the Activity's configuration satisfies [done], so the change has really landed. */
    private fun awaitConfiguration(done: (Configuration) -> Boolean) {
        rule.waitUntil(CHANGE_TIMEOUT) { onMain { done(rule.activity.resources.configuration) } }
        rule.waitForIdle()
    }

    /**
     * One row of the matrix: a fresh edit (so undo has something of its own
     * to prove), a selection and a scroll, then [change], then every check.
     */
    private fun row(
        name: String,
        recreates: Boolean = false,
        change: () -> Unit,
    ) {
        val marker = "Row $name survives\n"
        val activity = rule.activity
        onMain {
            val doc = active()
            val end = doc.text.text.length
            doc.surface.apply(listOf(EditOp(end, "", marker)), TextSelection.caret(end + marker.length))
            val start = doc.text.text.indexOf("Line 60")
            doc.surface.select(start, start + "Line 60".length)
            doc.surface.scrollToLine(TOP_LINE)
        }
        rule.waitUntil(CHANGE_TIMEOUT) {
            onMain {
                rule.activity
                    .graph()
                    .editor.anchorLine.value == TOP_LINE && codeView().anchorLine == TOP_LINE
            }
        }
        val buffer = onMain { active().text }
        val before = snapshot()

        change()

        if (recreates) {
            assertNotSame("$name kept the Activity: nothing was recreated", activity, rule.activity)
            assertTrue("$name left the old Activity alive", activity.isDestroyed)
            // The new Code view has to attach and take the shared anchor before the scroll can be read.
            rule.waitUntil(CHANGE_TIMEOUT) { onMain { codeView().anchorLine == TOP_LINE } }
        } else {
            assertSame("$name recreated the Activity", activity, rule.activity)
        }
        assertSame("$name replaced the buffer", buffer, onMain { active().text })
        val after = snapshot()
        assertEquals("$name lost characters", before.text, after.text)
        assertEquals("$name moved the selection", before.selection, after.selection)
        assertEquals("$name moved the shared anchor", before.anchor, after.anchor)
        assertEquals("$name scrolled the Code pane", TOP_LINE, after.codeTopLine)
        onMain {
            val doc = active()
            assertTrue("$name lost the undo history", doc.surface.undo())
            assertEquals(
                "$name's undo is not the last edit",
                before.text.removeSuffix(marker),
                doc.text.text.toString(),
            )
            assertTrue("$name lost the redo history", doc.surface.redo())
            assertEquals(before.text, doc.text.text.toString())
        }
    }

    @Test
    fun everyConfigurationChangeKeepsTextSelectionScrollAndUndo() {
        start((0 until LINES).joinToString("") { "Line $it of the rotation matrix.\n" })
        // The view mode is remembered between runs: another test may have left Split or Preview.
        selectMode(ViewMode.Code)

        row("landscape") {
            onMain { rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            awaitConfiguration { it.orientation == Configuration.ORIENTATION_LANDSCAPE }
        }
        row("portrait") {
            onMain { rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            awaitConfiguration { it.orientation == Configuration.ORIENTATION_PORTRAIT }
        }
        val unfolded = onMain { rule.activity.resources.configuration.screenWidthDp }
        row("resize to a folded width") {
            // Half the width in pixels: a foldable folding, or a split-screen half.
            val (width, height) =
                onMain {
                    rule.activity.resources.displayMetrics.let {
                        it.widthPixels to
                            it.heightPixels
                    }
                }
            shell("wm size ${width / 2}x$height")
            awaitConfiguration { it.screenWidthDp < unfolded }
        }
        row("resize back to unfolded") {
            shell("wm size reset")
            awaitConfiguration { it.screenWidthDp == unfolded }
        }
        row("dark theme") {
            shell("cmd uimode night yes")
            awaitConfiguration { it.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES }
        }
        row("light theme") {
            shell("cmd uimode night no")
            awaitConfiguration { it.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_NO }
        }
        row("double font size") {
            shell("settings put system font_scale 2.0")
            awaitConfiguration { it.fontScale > LARGE_FONT }
        }
        row("normal font size") {
            shell("settings put system font_scale 1.0")
            awaitConfiguration { it.fontScale < LARGE_FONT }
        }
        row("don't keep activities", recreates = true) { leaveWithActivityDestroyedAndReturn() }
    }

    /**
     * "Don't keep activities": leave the app (onPause, onStop), have the
     * Activity destroyed while it is in the background, and come back to a
     * new instance built from its saved state. That is the lifecycle the
     * developer setting produces on every departure, and what the system
     * does to a backgrounded Activity under memory pressure.
     *
     * It is driven through [androidx.test.core.app.ActivityScenario.recreate]
     * from the stopped state rather than by flipping
     * `always_finish_activities` and pressing Home: the scenario then knows
     * the new instance, so the rule's Compose hooks and LeakCanary follow
     * it, and the run cannot depend on a launcher being on the emulator.
     * The Activity is really destroyed and really rebuilt; the test checks both.
     */
    private fun leaveWithActivityDestroyedAndReturn() {
        val scenario = rule.activityRule.scenario
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.recreate()
        scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()
    }

    /**
     * Waits for the first document, then replaces all of its text with
     * [text]: the draft is reopened across tests, so whatever an earlier
     * test left in it goes, and every test starts from the same buffer.
     */
    private fun start(text: String) {
        rule.waitUntil(CHANGE_TIMEOUT) {
            rule.activity
                .graph()
                .editor.active != null
        }
        onMain {
            val doc = active()
            val old = doc.text.text.toString()
            doc.surface.apply(listOf(EditOp(0, old, text)), TextSelection.caret(0))
        }
        rule.waitForIdle()
    }

    private fun mode(): ViewMode =
        onMain {
            rule.activity
                .graph()
                .editor.uiState.value.mode
        }

    private fun selectMode(mode: ViewMode) {
        onMain {
            rule.activity
                .graph()
                .editor
                .onAction(EditorAction.SelectMode(mode))
        }
        rule.waitUntil(CHANGE_TIMEOUT) { mode() == mode }
        rule.waitForIdle()
    }

    /** Landscape, then portrait, the way the system rotates. */
    private fun rotateThereAndBack(check: (String) -> Unit) {
        val orientations =
            listOf(
                "landscape" to (ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to Configuration.ORIENTATION_LANDSCAPE),
                "portrait" to (ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to Configuration.ORIENTATION_PORTRAIT),
            )
        for ((name, change) in orientations) {
            onMain { rule.activity.requestedOrientation = change.first }
            awaitConfiguration { it.orientation == change.second }
            check(name)
        }
    }

    /**
     * Split and Preview: an edit, a selection, then rotation; the mode, the
     * buffer, every character, the selection and the undo history survive.
     */
    private fun rotateIn(mode: ViewMode) {
        start((0 until LINES).joinToString("") { "Line $it of the rotation matrix.\n\n" })
        selectMode(mode)
        val marker = "Edited in $mode\n"
        onMain {
            val doc = active()
            val end = doc.text.text.length
            doc.surface.apply(listOf(EditOp(end, "", marker)), TextSelection.caret(end + marker.length))
            val start = doc.text.text.indexOf("Line 60")
            doc.surface.select(start, start + "Line 60".length)
        }
        rule.waitForIdle()
        val activity = rule.activity
        val buffer = onMain { active().text }
        val text = onMain { buffer.text.toString() }
        val selection = onMain { active().surface.selection }
        rotateThereAndBack { name ->
            assertSame("$mode $name recreated the Activity", activity, rule.activity)
            assertEquals("$mode $name changed the mode", mode, mode())
            assertSame("$mode $name replaced the buffer", buffer, onMain { active().text })
            assertEquals("$mode $name lost characters", text, onMain { active().text.text.toString() })
            assertEquals("$mode $name moved the selection", selection, onMain { active().surface.selection })
        }
        onMain {
            val doc = active()
            assertTrue("$mode lost the undo history", doc.surface.undo())
            assertEquals(text.removeSuffix(marker), doc.text.text.toString())
            assertTrue("$mode lost the redo history", doc.surface.redo())
            assertEquals(text, doc.text.text.toString())
        }
    }

    @Test
    fun splitModeSurvivesRotation() = rotateIn(ViewMode.Split)

    @Test
    fun previewModeSurvivesRotation() = rotateIn(ViewMode.Preview)

    @Test
    fun aWordBeingComposedSurvivesRotation() {
        start("# Notes\n\nA line to type on\n")
        selectMode(ViewMode.Code)
        val at = onMain { active().text.text.length }
        // What a keyboard does mid-word: an underlined, uncommitted "wor".
        onMain {
            val view = codeView()
            view.setCaret(at)
            val connection = view.onCreateInputConnection(EditorInfo())
            connection.setComposingText("wor", 1)
        }
        val composed = onMain { active().text.text.toString() }
        assertTrue("the composing text is in the buffer", composed.endsWith("wor"))
        rotateThereAndBack { name ->
            // The system may commit the word on the way through (restartInput); it never loses it.
            assertEquals("$name lost the composing word", composed, onMain { active().text.text.toString() })
            assertEquals("$name moved the caret", TextSelection.caret(at + 3), onMain { active().surface.selection })
        }
        // And the keyboard can carry on: finishing the word keeps it, typing appends.
        onMain {
            val connection = codeView().onCreateInputConnection(EditorInfo())
            connection.finishComposingText()
            connection.commitText("d", 1)
        }
        assertTrue(onMain { active().text.text.toString() }.endsWith("word"))
    }

    @Test
    fun theCaretInATableCellSurvivesRotation() {
        val table =
            "| Name | Notes |\n|------|-------|\n" +
                (0 until TABLE_ROWS).joinToString("") { "| row $it | cell $it |\n" }
        start("# Table\n\n$table")
        selectMode(ViewMode.Code)
        // The end of the cell's text, "cell 7|", so the edit reads "cell 7 edited".
        val cell = onMain { active().text.text.indexOf("cell 7 ") + "cell 7".length }
        onMain {
            val doc = active()
            doc.surface.apply(listOf(EditOp(cell, "", " edited")), TextSelection.caret(cell + " edited".length))
        }
        rule.waitForIdle()
        val text = onMain { active().text.text.toString() }
        rotateThereAndBack { name ->
            assertEquals("$name lost characters in the table", text, onMain { active().text.text.toString() })
            assertEquals(
                "$name moved the caret out of its cell",
                TextSelection.caret(cell + " edited".length),
                onMain { active().surface.selection },
            )
        }
        onMain {
            assertTrue(active().surface.undo())
            assertEquals(text.replace("cell 7 edited", "cell 7"), active().text.text.toString())
        }
    }

    private fun state(): EditorUiState =
        onMain {
            rule.activity
                .graph()
                .editor.uiState.value
        }

    private fun act(action: EditorAction) =
        onMain {
            rule.activity
                .graph()
                .editor
                .onAction(action)
        }

    /** True once some window on screen shows [text] (sheets have windows of their own). */
    private fun showing(text: String): Boolean = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /**
     * Rotation there and back, then "Don't keep activities"; [check] runs
     * after each. The open UI under test lives in the editor's view model,
     * which is process-scoped, so a new Activity must show it again.
     */
    private fun throughRotationAndRecreation(check: (String) -> Unit) {
        rotateThereAndBack(check)
        val before = rule.activity
        leaveWithActivityDestroyedAndReturn()
        assertNotSame("nothing was recreated", before, rule.activity)
        assertTrue("the old Activity is still alive", before.isDestroyed)
        check("don't keep activities")
    }

    /**
     * Find open. robustness.md §5a rule 5: "Dialogs and sheets (find &
     * replace text, the theme editor, unsaved prompts) keep their inputs
     * … so a rotation never wipes a half-typed search". After each change
     * the bar is still open with the same query, match list and current
     * match ("3 of 11"), that match is still the selection, and the text
     * and undo history are untouched.
     */
    @Test
    fun findOpenSurvivesRotationAndRecreation() {
        start((0 until LINES).joinToString("") { "Line $it of the rotation matrix.\n" })
        selectMode(ViewMode.Code)
        val marker = "Edited before find\n"
        onMain {
            val doc = active()
            val end = doc.text.text.length
            doc.surface.apply(listOf(EditOp(end, "", marker)), TextSelection.caret(0))
        }
        try {
            act(EditorAction.Find(FindAction.Open))
            act(EditorAction.Find(FindAction.Query(QUERY)))
            rule.waitUntil(CHANGE_TIMEOUT) { state().find?.matches?.size == QUERY_MATCHES }
            act(EditorAction.Find(FindAction.Next))
            act(EditorAction.Find(FindAction.Next))
            rule.waitForIdle()
            val find = state().find ?: error("the find bar closed")
            val match = find.currentMatch ?: error("no current match")
            assertEquals(2, find.current)
            val text = onMain { active().text.text.toString() }
            val selection = TextSelection(match.start, match.end)
            assertEquals("Next did not select the match", selection, onMain { active().surface.selection })
            throughRotationAndRecreation { name ->
                rule.waitForIdle()
                assertEquals("$name changed the find bar", find, state().find)
                assertTrue("$name: the query is not on screen", showing(QUERY))
                assertEquals("$name lost characters", text, onMain { active().text.text.toString() })
                assertEquals("$name moved off the match", selection, onMain { active().surface.selection })
            }
            onMain {
                val doc = active()
                assertTrue("find lost the undo history", doc.surface.undo())
                assertEquals(text.removeSuffix(marker), doc.text.text.toString())
                assertTrue("find lost the redo history", doc.surface.redo())
                assertEquals(text, doc.text.text.toString())
            }
        } finally {
            // The view model outlives this test: leave the bar closed for the next one.
            act(EditorAction.Find(FindAction.Close))
        }
    }

    /**
     * A sheet open (⋮ → Display). By the same rule 5 it is not dismissed
     * by a rotation or a recreated Activity: after each change it is still
     * the overlay and still drawn, and the text and selection under it
     * have not moved.
     */
    @Test
    fun aSheetOpenSurvivesRotationAndRecreation() {
        start((0 until LINES).joinToString("") { "Line $it of the rotation matrix.\n" })
        selectMode(ViewMode.Code)
        val title = rule.activity.getString(R.string.display_title)
        val text = onMain { active().text.text.toString() }
        val selection = onMain { active().surface.selection }
        try {
            act(EditorAction.ChooseMenuItem(MenuItem.Display))
            rule.waitUntil(CHANGE_TIMEOUT) { showing(title) }
            throughRotationAndRecreation { name ->
                rule.waitUntil(CHANGE_TIMEOUT) { showing(title) }
                assertEquals("$name dismissed the sheet", EditorOverlay.Display, state().overlay)
                assertEquals("$name lost characters", text, onMain { active().text.text.toString() })
                assertEquals("$name moved the selection", selection, onMain { active().surface.selection })
            }
        } finally {
            act(EditorAction.DismissOverlay)
        }
    }

    /**
     * The unsaved prompt open: a real file (served by the app's own
     * FileProvider, so no system picker is needed), an edit, then Back:
     * "Save / Don't save / Keep editing". After each change the prompt is
     * still up for the same departure, the document is still unsaved, and
     * nothing in it moved; Keep editing then leaves the edit and its undo
     * intact. The file is saved and a fresh draft opened at the end, so
     * the next test starts where every other one does.
     */
    @Test
    fun theUnsavedPromptSurvivesRotationAndRecreation() {
        start("")
        val context = rule.activity
        val file = File(File(context.cacheDir, "shared"), "rotation-prompt.md")
        file.parentFile?.mkdirs()
        file.writeText("# Prompt\n\nOn disk.\n")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file).toString()
        onMain {
            rule.activity
                .graph()
                .editor
                .start(LaunchRequest(uri, writable = true, source = null, fromAnotherApp = false))
        }
        rule.waitUntil(CHANGE_TIMEOUT) { onMain { openUri() == uri } }
        selectMode(ViewMode.Code)
        val edit = "Typed, not saved.\n"
        onMain {
            val doc = active()
            val end = doc.text.text.length
            doc.surface.apply(listOf(EditOp(end, "", edit)), TextSelection.caret(end + edit.length))
        }
        rule.waitUntil(CHANGE_TIMEOUT) { state().isDirty }
        val text = onMain { active().text.text.toString() }
        val selection = onMain { active().surface.selection }
        val keep = context.getString(R.string.leave_keep)
        val prompt = EditorOverlay.DirtyPrompt(PendingLeave.Exit)
        try {
            act(EditorAction.BackPressed)
            rule.waitUntil(CHANGE_TIMEOUT) { showing(keep) }
            assertEquals(prompt, state().overlay)
            throughRotationAndRecreation { name ->
                rule.waitUntil(CHANGE_TIMEOUT) { showing(keep) }
                assertEquals("$name dismissed the prompt", prompt, state().overlay)
                assertTrue("$name lost the unsaved state", state().isDirty)
                assertEquals("$name lost characters", text, onMain { active().text.text.toString() })
                assertEquals("$name moved the selection", selection, onMain { active().surface.selection })
            }
            act(EditorAction.KeepEditing)
            rule.waitForIdle()
            assertEquals(null, state().overlay)
            onMain {
                val doc = active()
                assertTrue("the prompt lost the undo history", doc.surface.undo())
                assertEquals(text.removeSuffix(edit), doc.text.text.toString())
                assertTrue("the prompt lost the redo history", doc.surface.redo())
                assertEquals(text, doc.text.text.toString())
            }
        } finally {
            if (state().overlay != null) act(EditorAction.KeepEditing)
            act(EditorAction.SaveTapped)
            rule.waitUntil(CHANGE_TIMEOUT) { !state().isDirty }
            act(EditorAction.ChooseMenuItem(MenuItem.New))
            rule.waitUntil(CHANGE_TIMEOUT) { onMain { openUri()?.let { DocumentUri(it).isDraft } == true } }
        }
        assertEquals(text, file.readText())
    }

    private fun openUri(): String? =
        rule.activity
            .graph()
            .editor.active
            ?.open
            ?.uri
            ?.value

    private companion object {
        const val TABLE_ROWS = 20
        const val CHANGE_TIMEOUT = 10_000L
        const val LINES = 300
        const val TOP_LINE = 80
        const val LARGE_FONT = 1.5f

        /** "Line 7" and "Line 70" to "Line 79" in the first 300 lines: 11 matches, none in the marker. */
        const val QUERY = "Line 7"
        const val QUERY_MATCHES = 11
    }
}
