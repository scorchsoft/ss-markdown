package com.scorchsoft.ssmarkdown.editor

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.scorchsoft.ssmarkdown.document.CodeViewSurface
import com.scorchsoft.ssmarkdown.editor.panes.CodePaneHost
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.ui.Themed
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

/**
 * edge-cases "The selection reset to the top when the Code view was
 * rebuilt" (found by the rotation matrix's "Don't keep activities" row on
 * the CI emulator): the cursor and selection lived only in the Code view,
 * so a new view (a recreated Activity, or the Code pane leaving the screen
 * for Preview and coming back) started at offset 0. The document now keeps
 * the selection when its view goes and hands it to the next one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SelectionAcrossViewsTest {
    @get:Rule val temp = TemporaryFolder()

    @get:Rule val rule = createComposeRule()

    @Test
    fun `a new Code view takes the selection the old one had`() {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        f.vm.openDocument(f.file("notes.md", "# Notes\n\nfirst line\nsecond line\n"))
        val doc = f.vm.active ?: error("nothing open")
        var shown by mutableStateOf(true)
        rule.setContent {
            Themed(dark = false) {
                val active by f.vm.activeDocument.collectAsState()
                // Leaving and re-entering composition builds a new view, as a recreated Activity does.
                if (shown) {
                    CodePaneHost(
                        active,
                        wrap = true,
                        zoomPercent = 100,
                        f.vm::attach,
                        f.vm::detach,
                        { it.run() },
                    )
                }
            }
        }
        rule.waitForIdle()
        assertTrue("the Code view never attached", doc.surface is CodeViewSurface)
        rule.runOnIdle { doc.surface.select(10, 20) }

        rule.runOnIdle { shown = false }
        rule.waitForIdle()
        assertFalse("the view is still attached", doc.surface is CodeViewSurface)
        assertEquals("the selection went with the view", TextSelection(10, 20), doc.surface.selection)

        rule.runOnIdle { shown = true }
        rule.waitForIdle()
        assertTrue("the new view never attached", doc.surface is CodeViewSurface)
        assertEquals("the new view started elsewhere", TextSelection(10, 20), doc.surface.selection)
    }

    /**
     * edge-cases "A switched-away document held the Code view" (found by
     * LeakCanary in the rotation matrix's unsaved-prompt cell): the same
     * view moves on to the next document without a detach, and the Preview
     * keeps the previous binding a while for late page edits (DS-13), so
     * the old document kept the view, and with it a destroyed Activity.
     * Releasing a document lets go of its view.
     */
    @Test
    fun `a document switched away from lets go of the Code view`() {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        f.vm.openDocument(f.file("first.md", "# First\n"))
        val first = f.vm.active ?: error("nothing open")
        rule.setContent {
            Themed(dark = false) {
                val active by f.vm.activeDocument.collectAsState()
                CodePaneHost(active, wrap = true, zoomPercent = 100, f.vm::attach, f.vm::detach, { it.run() })
            }
        }
        rule.waitForIdle()
        assertTrue("the Code view never attached", first.surface is CodeViewSurface)

        f.vm.openDocument(f.file("second.md", "# Second\n"))
        rule.waitForIdle()
        val second = f.vm.active ?: error("nothing open")
        assertTrue("the view did not move to the new document", second.surface is CodeViewSurface)
        assertFalse("the old document still holds the view", first.surface is CodeViewSurface)
    }
}
