package com.scorchsoft.ssmarkdown

import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.MarkdownCodeView
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.text.EditOp
import leakcanary.DetectLeaksAfterTestSuccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The rotation matrix's `am kill` column (docs/robustness.md Part 3):
 * the process dies while the app is in the background, and the user comes
 * back. What comes back is what edge-cases.md promises for "Process death
 * in the background": the same file, view and position, with every
 * character. The undo stack is in memory only and is not promised across a
 * process death (robustness.md §1: "Process death is handled by the
 * journal"), so this test does not ask for it.
 *
 * Two instrumentation runs, because the death ends the first one:
 * `apps/android/tools/background-kill.sh` runs [leaveAndDie] and then
 * [comeBack]. Skipped unless the script passes `backgroundKill`, so the
 * ordinary `connectedDebugAndroidTest` run is unaffected.
 *
 * [leaveAndDie] puts the app in the background the way Home does (onPause,
 * onStop) and is then killed with SIGKILL, which is what `am kill` and the
 * low-memory killer send. It kills itself rather than calling `am kill`
 * from outside because a process under instrumentation is never "safe to
 * kill" for `am kill`. [comeBack] is a cold launch: instrumentation
 * force-stops the package between runs, so the system's saved Activity
 * state (which the app does not use: robustness.md §1) is gone too.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundKillTest {
    @get:Rule(order = 0)
    val leaks = DetectLeaksAfterTestSuccess()

    private val arguments = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val expectedFile = File(File(context.noBackupFilesDir, "background-kill"), "expected.txt")

    @Test
    fun leaveAndDie() {
        assumeTrue("run by tools/background-kill.sh", arguments.getString(ARG_STEP) == STEP_LEAVE)
        expectedFile.delete()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitCodeView(scenario)
        onMain(scenario) { it.graph().editor.onAction(EditorAction.SelectMode(ViewMode.Code)) }
        // Every run starts from the same buffer, whatever the draft held.
        onMain(scenario) {
            val doc = it.graph().editor.active ?: error("nothing open")
            doc.surface.apply(listOf(EditOp(0, doc.text.text.toString(), TEXT)), TextSelection.caret(0))
        }
        awaitCodeView(scenario)
        onMain(scenario) {
            val doc = it.graph().editor.active ?: error("nothing open")
            val end = doc.text.text.length
            doc.surface.apply(listOf(EditOp(end, "", MARKER)), TextSelection.caret(end + MARKER.length))
            val start = doc.text.text.indexOf(SELECTED)
            doc.surface.select(start, start + SELECTED.length)
            doc.surface.scrollToLine(TOP_LINE)
        }
        await("the Code pane never scrolled") {
            onMain(scenario) {
                it
                    .graph()
                    .editor.anchorLine.value == TOP_LINE && codeView(it)?.anchorLine == TOP_LINE
            }
        }
        val (text, selection) =
            onMain(scenario) {
                val doc = it.graph().editor.active ?: error("nothing open")
                doc.text.text.toString() to doc.surface.selection
            }
        // Home: onPause flushes the journal and the view state, onStop follows.
        scenario.moveToState(Lifecycle.State.CREATED)
        expectedFile.parentFile?.mkdirs()
        expectedFile.writeText("${selection.start}\n${selection.end}\n$text")
        Log.i(TAG, "in the background with ${text.length} chars, dying")
        Process.killProcess(Process.myPid())
        SystemClock.sleep(DEATH_WAIT_MILLIS)
        error("the process should have died")
    }

    @Test
    fun comeBack() {
        assumeTrue("run by tools/background-kill.sh", arguments.getString(ARG_STEP) == STEP_RETURN)
        assertTrue("the first step left nothing to check", expectedFile.exists())
        val lines = expectedFile.readText().split('\n', limit = 3)
        val selection = TextSelection(lines[0].toInt(), lines[1].toInt())
        val text = lines[2]
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitCodeView(scenario)
        await("the Code pane did not come back at line $TOP_LINE") {
            onMain(scenario) {
                it
                    .graph()
                    .editor.anchorLine.value == TOP_LINE && codeView(it)?.anchorLine == TOP_LINE
            }
        }
        onMain(scenario) {
            val vm = it.graph().editor
            val doc = vm.active ?: error("nothing open")
            assertEquals("characters were lost", text, doc.text.text.toString())
            assertEquals("the view mode changed", ViewMode.Code, vm.uiState.value.mode)
            assertEquals("the selection moved", selection, doc.surface.selection)
        }
        Log.i(TAG, "came back with every character, the selection and line $TOP_LINE")
        scenario.close()
        expectedFile.delete()
    }

    private fun awaitCodeView(scenario: ActivityScenario<MainActivity>) =
        await("the editor never appeared") {
            onMain(scenario) { it.graph().editor.active != null && codeView(it) != null }
        }

    private fun await(
        failure: String,
        done: () -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            if (done()) return
            SystemClock.sleep(POLL_MILLIS)
        }
        error(failure)
    }

    private fun codeView(activity: MainActivity): MarkdownCodeView? {
        fun find(view: View): MarkdownCodeView? =
            view as? MarkdownCodeView
                ?: (view as? ViewGroup)?.let { group ->
                    (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) }
                }
        return find(activity.window.decorView)
    }

    private fun <T> onMain(
        scenario: ActivityScenario<MainActivity>,
        block: (MainActivity) -> T,
    ): T {
        var result: Result<T>? = null
        scenario.onActivity { result = runCatching { block(it) } }
        return result!!.getOrThrow()
    }

    private companion object {
        const val TAG = "BackgroundKill"
        const val ARG_STEP = "backgroundKill"
        const val STEP_LEAVE = "leave"
        const val STEP_RETURN = "return"
        const val TOP_LINE = 80
        const val SELECTED = "Line 60"
        const val MARKER = "Typed just before leaving\n"
        val TEXT = (0 until 300).joinToString("") { "Line $it, killed in the background.\n" }
        const val WAIT_MILLIS = 15_000L
        const val POLL_MILLIS = 50L
        const val DEATH_WAIT_MILLIS = 5_000L
    }
}
