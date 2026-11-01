package com.scorchsoft.ssmarkdown

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.text.EditOp
import leakcanary.DetectLeaksAfterTestSuccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The rotation matrix basics (robustness §5a): text typed before a
 * rotation or a mode switch is still there after it, in the same buffer,
 * because views never own text.
 */
@RunWith(AndroidJUnit4::class)
class RotationTest {
    // Outermost, so it checks for leaks after the Activity has been closed.
    @get:Rule(order = 0)
    val leaks = DetectLeaksAfterTestSuccess()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun typeIntoActive(text: String) {
        instrumentation.runOnMainSync {
            val doc =
                rule.activity
                    .graph()
                    .editor.active ?: error("nothing open")
            val end = doc.text.text.length
            doc.surface.apply(listOf(EditOp(end, "", text)), TextSelection.caret(end + text.length))
        }
    }

    private fun activeText(): String {
        var text = ""
        instrumentation.runOnMainSync {
            text =
                rule.activity
                    .graph()
                    .editor.active
                    ?.text
                    ?.text
                    ?.toString()
                    .orEmpty()
        }
        return text
    }

    @Test
    fun rotatingKeepsTheSameBufferAndText() {
        rule.waitUntil(TIMEOUT_MILLIS) {
            rule.activity
                .graph()
                .editor.active != null
        }
        typeIntoActive("Rotation survives\n")
        val before =
            rule.activity
                .graph()
                .editor.active
                ?.text
        val orientations = listOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        orientations.forEach { orientation ->
            instrumentation.runOnMainSync { rule.activity.requestedOrientation = orientation }
            rule.waitForIdle()
            assertSame(
                before,
                rule.activity
                    .graph()
                    .editor.active
                    ?.text,
            )
            assertEquals(true, activeText().endsWith("Rotation survives\n"))
        }
    }

    @Test
    fun switchingModesKeepsTheText() {
        rule.waitUntil(TIMEOUT_MILLIS) {
            rule.activity
                .graph()
                .editor.active != null
        }
        typeIntoActive("Modes survive\n")
        ViewMode.entries.forEach { mode ->
            instrumentation.runOnMainSync {
                rule.activity
                    .graph()
                    .editor
                    .onAction(EditorAction.SelectMode(mode))
            }
            rule.waitForIdle()
            assertEquals(true, activeText().endsWith("Modes survive\n"))
        }
    }

    /**
     * Split hosts the Preview with movableContentOf, and the Activity owns
     * the one PreviewView: rotating (top/bottom ↔ side by side) moves the
     * same view, still attached to the session, and the text survives.
     */
    @Test
    fun rotatingInSplitKeepsTheSamePreviewView() {
        rule.waitUntil(TIMEOUT_MILLIS) {
            rule.activity
                .graph()
                .editor.active != null
        }
        typeIntoActive("Split survives\n")
        instrumentation.runOnMainSync {
            rule.activity
                .graph()
                .editor
                .onAction(EditorAction.SelectMode(ViewMode.Split))
        }
        rule.waitForIdle()
        val view = rule.activity.previewView
        val orientations = listOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        orientations.forEach { orientation ->
            instrumentation.runOnMainSync { rule.activity.requestedOrientation = orientation }
            rule.waitForIdle()
            assertSame(view, rule.activity.previewView)
            assertSame(
                view,
                rule.activity
                    .graph()
                    .editor.preview.view,
            )
            assertNotNull(view.parent)
            assertEquals(true, activeText().endsWith("Split survives\n"))
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
    }
}
