package com.scorchsoft.ssmarkdown

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scorchsoft.ssmarkdown.document.ActiveDocument
import com.scorchsoft.ssmarkdown.editor.MarkdownCodeView
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.text.EditOp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
 * change on an emulator without a hinge.
 */
@RunWith(AndroidJUnit4::class)
class RotationMatrixTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

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

        assertSame("$name recreated the Activity", activity, rule.activity)
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
        rule.waitUntil(CHANGE_TIMEOUT) {
            rule.activity
                .graph()
                .editor.active != null
        }
        onMain {
            val doc = active()
            val lines = (0 until LINES).joinToString("") { "Line $it of the rotation matrix.\n" }
            doc.surface.apply(listOf(EditOp(0, "", lines)), TextSelection.caret(0))
        }
        rule.waitForIdle()

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
    }

    private companion object {
        const val CHANGE_TIMEOUT = 10_000L
        const val LINES = 300
        const val TOP_LINE = 80
        const val LARGE_FONT = 1.5f
    }
}
