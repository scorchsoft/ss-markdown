package com.scorchsoft.ssmarkdown.editor.preview

import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.MarkdownCodeView
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.preview.bridge.HostMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Split, editing in Preview, then a tap into the Code pane: the Code view
 * takes focus and the strip must follow it, or Bold goes to the page's old
 * selection (edge-cases "Split: after editing in Preview, a tap into the
 * Code pane left the strip driving the page"). This is the call site: the
 * view the editor attaches reports its focus.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SplitFocusTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `the attached Code view taking focus ends Preview editing`() {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        f.vm.openDocument(f.file("a.md", "Some words.\n"))
        val page = ArrayList<HostMessage>()
        f.vm.preview.attachPage { page += it }
        val view = MarkdownCodeView(ApplicationProvider.getApplicationContext())
        view.setDocument(f.vm.active?.text ?: error("nothing open"))
        f.vm.attach(view)
        f.vm.onAction(EditorAction.SelectMode(ViewMode.Split))
        f.vm.preview.onEditingChanged(true)

        view.onFocusChangeListener.onFocusChange(view, true)

        assertFalse(f.vm.state.previewTyping)
        assertTrue(page.contains(HostMessage.EndEditing))
    }
}
