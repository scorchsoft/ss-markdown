package com.scorchsoft.ssmarkdown

import android.os.Process
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scorchsoft.ssmarkdown.editor.MarkdownCodeView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.random.Random

/**
 * The kill loop on a device (docs/robustness.md Part 3, "Kill test"): the
 * real app, typed into through the Code view's real `InputConnection`,
 * killed with `Process.killProcess` (SIGKILL, as `kill -9`), relaunched,
 * and the recovered text checked.
 *
 * One iteration per instrumentation run, because the kill ends the run:
 * `apps/android/tools/kill-loop.sh` runs [step] N times and then
 * [verifyLast]. Each step first checks what the previous one left, then
 * types and dies. Skipped unless the script passes `killLoop`, so the
 * ordinary `connectedDebugAndroidTest` run is unaffected.
 *
 * Deterministic by construction, with explicit sync points instead of
 * racing the clock:
 * - **Idle**: type, wait 2.5 s (the group commit is at most 1 s behind), die. Recovered = exactly the text.
 * - **Pause**: type, move the Activity to STARTED (onPause flushes and waits), die. Exactly the text.
 * - **Burst**: type and die at once. Recovered = one of the texts the burst
 *   passed through (never anything else), and the loss is measured and logged.
 */
@RunWith(AndroidJUnit4::class)
class KillLoopTest {
    private val arguments = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.noBackupFilesDir, "kill-loop").apply { mkdirs() }
    private val expectedFile = File(dir, "expected.txt")

    private enum class Mode { Idle, Pause, Burst }

    @Test
    fun step() {
        val iteration = arguments.getString(ARG_ITERATION)?.toIntOrNull()
        assumeTrue("run by tools/kill-loop.sh", arguments.getString(ARG_LOOP) != null && iteration != null)
        val random = Random(SEED + iteration!!)
        val mode = Mode.entries[iteration % Mode.entries.size]
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val view = awaitEditor(scenario)
        verifyRecovered(textOf(scenario), iteration)

        // The text before the first key is a state too: a burst killed before any group commit comes back as it.
        val states = arrayListOf(SystemClock.uptimeMillis() to textOf(scenario))
        val connection =
            onMain(scenario) {
                view.requestFocus()
                view.onCreateInputConnection(EditorInfo())
            }
        repeat(KEYS_MIN + random.nextInt(KEYS_SPAN)) {
            val key = ALPHABET[random.nextInt(ALPHABET.length)].toString()
            onMain(scenario) { connection.commitText(key, 1) }
            states += SystemClock.uptimeMillis() to textOf(scenario)
            SystemClock.sleep(if (mode == Mode.Burst) BURST_GAP_MILLIS else KEY_GAP_MILLIS)
        }
        // A silent no-op would pass every check while proving nothing: the keys must have landed.
        assertTrue("the InputConnection typed nothing", states.last().second != states.first().second)
        when (mode) {
            Mode.Idle -> SystemClock.sleep(IDLE_MILLIS)

            // Past the 1 s group commit: everything is durable.
            Mode.Pause -> scenario.moveToState(Lifecycle.State.STARTED)

            // onPause flushes and waits for the journal.
            Mode.Burst -> Unit
        }
        writeExpected(exact = mode != Mode.Burst, states)
        Log.i(TAG, "iteration $iteration: typed ${states.size - 1} keys ($mode), dying")
        Process.killProcess(Process.myPid())
        SystemClock.sleep(DEATH_WAIT_MILLIS)
        fail("the process should have died")
    }

    @Test
    fun verifyLast() {
        assumeTrue("run by tools/kill-loop.sh", arguments.getString(ARG_VERIFY) != null)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitEditor(scenario)
        verifyRecovered(textOf(scenario), iteration = -1)
        scenario.close()
    }

    /** What the previous iteration's process left: checked against what it wrote down before dying. */
    private fun verifyRecovered(
        recovered: String,
        iteration: Int,
    ) {
        if (!expectedFile.exists()) return
        val lines = expectedFile.readLines()
        val exact = lines.first() == EXACT
        val killedAt = lines[1].toLong()
        val states =
            lines.drop(2).map { line ->
                val (time, text) = line.split('\t')
                time.toLong() to String(Base64.decode(text, Base64.NO_WRAP), Charsets.UTF_8)
            }
        if (exact) {
            assertEquals("iteration $iteration recovered", states.last().second, recovered)
            Log.i(TAG, "iteration $iteration: exact, lost 0 ms")
            return
        }
        val index = states.indexOfLast { it.second == recovered }
        assertTrue("iteration $iteration recovered text the document never had", index >= 0)
        val lostMillis = if (index == states.lastIndex) 0 else killedAt - states[index + 1].first
        Log.i(TAG, "iteration $iteration: burst, lost $lostMillis ms of typing")
        assertTrue("iteration $iteration lost $lostMillis ms", lostMillis <= LOSS_LIMIT_MILLIS)
    }

    private fun writeExpected(
        exact: Boolean,
        states: List<Pair<Long, String>>,
    ) {
        val body =
            buildString {
                appendLine(if (exact) EXACT else PREFIX)
                appendLine(SystemClock.uptimeMillis())
                states.forEach { (time, text) ->
                    append(time).append('\t').appendLine(Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP))
                }
            }
        val staging = File(dir, "expected.txt.new")
        FileOutputStream(staging).use {
            it.write(body.toByteArray())
            it.fd.sync()
        }
        check(staging.renameTo(expectedFile)) { "could not write $expectedFile" }
    }

    private fun awaitEditor(scenario: ActivityScenario<MainActivity>): MarkdownCodeView {
        val deadline = SystemClock.uptimeMillis() + EDITOR_WAIT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            val view =
                onMain(scenario) { activity ->
                    activity
                        .graph()
                        .editor.active
                        ?.let { findCodeView(activity.window.decorView) }
                }
            if (view != null) return view
            SystemClock.sleep(POLL_MILLIS)
        }
        error("the editor never appeared")
    }

    private fun textOf(scenario: ActivityScenario<MainActivity>): String =
        onMain(scenario) { activity ->
            activity
                .graph()
                .editor.active
                ?.text
                ?.text
                .toString()
        }

    private fun findCodeView(view: View): MarkdownCodeView? =
        when (view) {
            is MarkdownCodeView -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findCodeView(view.getChildAt(it)) }
            else -> null
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
        const val TAG = "KillLoop"
        const val ARG_LOOP = "killLoop"
        const val ARG_ITERATION = "killLoopIteration"
        const val ARG_VERIFY = "killLoopVerify"
        const val EXACT = "exact"
        const val PREFIX = "prefix"
        const val SEED = 20_261_101
        const val KEYS_MIN = 8
        const val KEYS_SPAN = 12
        const val KEY_GAP_MILLIS = 40L
        const val BURST_GAP_MILLIS = 15L
        const val IDLE_MILLIS = 2_500L
        const val DEATH_WAIT_MILLIS = 5_000L
        const val EDITOR_WAIT_MILLIS = 15_000L
        const val POLL_MILLIS = 50L

        /** The group commit's bound is 1 s; the rest is slack for a loaded CI emulator, so this never flakes. */
        const val LOSS_LIMIT_MILLIS = 3_000L
        const val ALPHABET = "abcdefghij klmnop\nqrstu#-*"
    }
}
