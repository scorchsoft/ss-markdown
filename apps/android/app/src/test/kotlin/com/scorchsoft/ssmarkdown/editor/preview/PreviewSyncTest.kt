package com.scorchsoft.ssmarkdown.editor.preview

import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.Pane
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.preview.bridge.HostMessage
import com.scorchsoft.ssmarkdown.preview.bridge.TextEdit
import com.scorchsoft.ssmarkdown.settings.AppSettings
import com.scorchsoft.ssmarkdown.text.EditOp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The editor and the Preview page over a recording page: Split's linked
 * scrolling, the anchor, and both directions of text (host edits as line
 * edits; page edits applied once and acked).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreviewSyncTest {
    @get:Rule val temp = TemporaryFolder()

    private val page = ArrayList<HostMessage>()

    private fun fixture(linked: Boolean = true): EditorFixture {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher(), AppSettings(scrollPanesTogether = linked))
        f.vm.openDocument(f.file("a.md", (1..60).joinToString("") { "line $it\n" }))
        f.vm.preview.attachPage { page += it }
        return f
    }

    private fun scrolls() = page.filterIsInstance<HostMessage.ScrollToLine>().map { it.line }

    @Test
    fun `the page opens at the anchor`() {
        fixture()
        assertTrue(page.first() is HostMessage.Load)
    }

    @Test
    fun `in Split the touched pane leads and the other follows`() {
        val f = fixture()
        f.vm.onAction(EditorAction.SelectMode(ViewMode.Split))
        page.clear()

        f.vm.onPaneTopLine(Pane.Code, 12)
        assertEquals(12, f.vm.anchorLine.value)
        assertEquals(listOf(12), scrolls())

        // The preview following reports its own top line: ignored, so nothing bounces back.
        f.vm.onPaneTopLine(Pane.Preview, 11)
        assertEquals(12, f.vm.anchorLine.value)

        f.vm.onAction(EditorAction.PaneTouched(Pane.Preview))
        f.vm.onPaneTopLine(Pane.Preview, 30)
        assertEquals(30, f.vm.anchorLine.value)
        assertEquals(listOf(12), scrolls())
    }

    @Test
    fun `with scrolling unlinked only the anchor moves`() {
        val f = fixture(linked = false)
        f.vm.onAction(EditorAction.SelectMode(ViewMode.Split))
        page.clear()
        f.vm.onPaneTopLine(Pane.Code, 20)
        assertEquals(20, f.vm.anchorLine.value)
        assertEquals(emptyList<Int>(), scrolls())
    }

    @Test
    fun `switching to Preview lands it on the anchor`() {
        val f = fixture()
        f.vm.onPaneTopLine(Pane.Code, 25)
        page.clear()
        f.vm.onAction(EditorAction.SelectMode(ViewMode.Preview))
        assertEquals(listOf(25), scrolls())
    }

    @Test
    fun `a host edit reaches the page as a line edit with its ops`() {
        val f = fixture()
        page.clear()
        f.vm.active
            ?.surface
            ?.apply(listOf(EditOp(0, "", "X")), TextSelection.caret(1))
        val edit = page.filterIsInstance<HostMessage.Edit>().single()
        assertEquals(listOf(TextEdit(0, 0, "X")), edit.ops)
        assertEquals(
            "Xline 1",
            edit.changes
                .single()
                .lines
                .first(),
        )
    }

    @Test
    fun `a page edit is applied once and acknowledged`() {
        val f = fixture()
        val rev = f.vm.preview.rev
        page.clear()
        f.vm.preview.onSourceEdits(rev, listOf(TextEdit(0, 4, "LINE")))
        assertTrue(
            f.vm.active
                ?.text
                ?.text
                .toString()
                .startsWith("LINE 1\n"),
        )
        assertEquals(listOf(HostMessage.Ack(rev + 1)), page)
        assertTrue(f.vm.uiState.value.isDirty)
    }

    @Test
    fun `undo in Preview goes back to the page as a host edit`() {
        val f = fixture()
        f.vm.preview.onSourceEdits(f.vm.preview.rev, listOf(TextEdit(0, 0, "Y")))
        f.vm.onAction(EditorAction.SelectMode(ViewMode.Preview))
        page.clear()
        f.vm.onAction(EditorAction.StripKeyTapped(com.scorchsoft.ssmarkdown.ui.strip.StripKey.Undo))
        assertEquals(listOf(TextEdit(0, 1, "")), page.filterIsInstance<HostMessage.Edit>().single().ops)
    }
}
