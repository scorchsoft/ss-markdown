package com.scorchsoft.ssmarkdown.editor

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.scorchsoft.ssmarkdown.document.CodeViewSurface
import com.scorchsoft.ssmarkdown.editor.panes.CodePaneHost
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.ui.Themed
import com.scorchsoft.ssmarkdown.viewstate.FileViewState
import com.scorchsoft.ssmarkdown.viewstate.InMemoryViewStates
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * edge-cases "Process death in the background, or a kill at any moment"
 * (found by the emulator kill loop): a file reopened at start-up with a
 * remembered cursor crashed, because the real Code view was handed to the
 * editor before it was bound to the document, and the remembered cursor
 * was set on the view's empty placeholder text (IndexOutOfBounds in
 * `PieceTree.positionAt`). The view is now bound first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RememberedCursorTest {
    @get:Rule val temp = TemporaryFolder()

    @get:Rule val rule = createComposeRule()

    @Test
    fun `a remembered cursor is restored on the real Code view when the file opens at start-up`() {
        val states = InMemoryViewStates()
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher(), viewStates = states)
        val uri = f.file("notes.md", "# Notes\n\nfirst line\n")
        states.write(uri, FileViewState(selectionStart = 10, selectionEnd = 14, anchorLine = 2))
        f.vm.openDocument(uri)
        val doc = f.vm.active ?: error("nothing open")

        rule.setContent {
            Themed(dark = false) {
                val active by f.vm.activeDocument.collectAsState()
                CodePaneHost(active, wrap = true, zoomPercent = 100, f.vm::attach, f.vm::detach, { it.run() })
            }
        }
        rule.waitForIdle()

        assertTrue("the Code view never attached", doc.surface is CodeViewSurface)
        assertEquals(10, doc.surface.selection.start)
        assertEquals(14, doc.surface.selection.end)
    }
}
