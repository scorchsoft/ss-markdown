package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.markdown.edit.ExistingLink
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.preview.bridge.HostMessage
import com.scorchsoft.ssmarkdown.preview.bridge.PreviewFormatRequest
import com.scorchsoft.ssmarkdown.preview.editing.FormatAction
import com.scorchsoft.ssmarkdown.ui.strip.StripKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The strip's Link key asks where the link goes (docs/ux-spec.md,
 * "Formatting strip"): every path from tap to text, in Code and Preview.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkPromptTest {
    @get:Rule val temp = TemporaryFolder()

    private fun fixture(text: String): EditorFixture {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        f.vm.openDocument(f.file("l.md", text))
        return f
    }

    private fun EditorFixture.select(
        start: Int,
        end: Int,
    ) = vm.active?.surface?.select(start, end)

    private fun EditorFixture.text() =
        vm.active
            ?.text
            ?.text
            ?.toString()

    private fun EditorFixture.prompt(): EditorOverlay.LinkPrompt = vm.uiState.value.overlay as EditorOverlay.LinkPrompt

    @Test
    fun `prefill takes the selection, an address in it, an existing link, or the clipboard`() {
        assertEquals(LinkFields("words", ""), LinkPrompt.fields("words", null))
        assertEquals(LinkFields("https://a.b", "https://a.b"), LinkPrompt.fields("https://a.b", null))
        assertEquals(
            LinkFields("old", "https://o.example", editing = true),
            LinkPrompt.fields("", ExistingLink("old", "https://o.example")),
        )
        // The clipboard fills an empty address only, and only with an address.
        assertEquals(
            LinkFields("words", "https://c.example"),
            LinkPrompt.withClipboard(LinkFields("words", ""), " https://c.example "),
        )
        assertEquals(LinkFields("words", ""), LinkPrompt.withClipboard(LinkFields("words", ""), "not a link"))
        assertEquals(
            LinkFields("w", "https://a.b"),
            LinkPrompt.withClipboard(LinkFields("w", "https://a.b"), "https://c.example"),
        )
        assertEquals(LinkFields("w", ""), LinkPrompt.withClipboard(LinkFields("w", ""), null))
    }

    @Test
    fun `Link on a selection asks first, and Done writes the link`() {
        val f = fixture("Read the docs today\n")
        f.select(9, 13)
        f.vm.onAction(EditorAction.StripKeyTapped(StripKey.Link))
        assertEquals("Read the docs today\n", f.text())
        assertEquals(LinkFields("docs", ""), f.prompt().fields)
        f.vm.onAction(EditorAction.LinkDone("docs", "https://d.example"))
        assertEquals("Read the [docs](https://d.example) today\n", f.text())
        assertNull(f.vm.uiState.value.overlay)
        // The sheet's fields had the keyboard: it goes back to the text, the caret after the link.
        assertTrue(EditorEvent.ReturnKeyboard in f.events)
        assertEquals(
            TextSelection.caret(34),
            f.vm.active
                ?.surface
                ?.selection,
        )
    }

    @Test
    fun `with nothing selected Done writes the address as its own text, or the text given`() {
        val f = fixture("See \n")
        f.select(4, 4)
        f.vm.onAction(EditorAction.StripKeyTapped(StripKey.Link))
        assertEquals(LinkFields("", ""), f.prompt().fields)
        f.vm.onAction(EditorAction.LinkDone("", "https://a.b"))
        assertEquals("See [https://a.b](https://a.b)\n", f.text())

        val g = fixture("See \n")
        g.select(4, 4)
        g.vm.onAction(EditorAction.StripKeyTapped(StripKey.Link))
        g.vm.onAction(EditorAction.LinkDone("this", "https://a.b"))
        assertEquals("See [this](https://a.b)\n", g.text())
    }

    @Test
    fun `inside a link the prompt edits it, and Remove link keeps its text`() {
        val f = fixture("A [link](https://old.example) here\n")
        f.select(4, 4)
        f.vm.onAction(EditorAction.StripKeyTapped(StripKey.Link))
        assertEquals(LinkFields("link", "https://old.example", editing = true), f.prompt().fields)
        f.vm.onAction(EditorAction.LinkDone("link", "https://new.example"))
        assertEquals("A [link](https://new.example) here\n", f.text())

        f.select(4, 4)
        f.vm.onAction(EditorAction.StripKeyTapped(StripKey.Link))
        f.vm.onAction(EditorAction.LinkRemove)
        assertEquals("A link here\n", f.text())
        // One undo step brings the link back.
        f.vm.onAction(EditorAction.StripKeyTapped(StripKey.Undo))
        assertEquals("A [link](https://new.example) here\n", f.text())
    }

    @Test
    fun `Cancel changes nothing`() {
        val f = fixture("Read the docs today\n")
        f.select(9, 13)
        f.vm.onAction(EditorAction.StripKeyTapped(StripKey.Link))
        f.vm.onAction(EditorAction.DismissOverlay)
        assertNull(f.vm.uiState.value.overlay)
        assertEquals("Read the docs today\n", f.text())
        // The keyboard and the selection go back to the text as they were.
        assertTrue(EditorEvent.ReturnKeyboard in f.events)
        assertEquals(
            TextSelection(9, 13),
            f.vm.active
                ?.surface
                ?.selection,
        )
        assertTrue(
            f.vm.active
                ?.text
                ?.canUndo != true,
        )
    }

    @Test
    fun `in Preview the prompt opens on the page's selection and writes the same link`() {
        val f = fixture("Read the docs today\n")
        val page = ArrayList<HostMessage>()
        f.vm.preview.attachPage { page += it }
        val rev = page.filterIsInstance<HostMessage.Load>().last().rev
        f.vm.preview.onFormatRequest(PreviewFormatRequest(FormatAction.LINK, 9, 13, rev, false))
        // Asking first: nothing written yet.
        assertEquals("Read the docs today\n", f.text())
        assertEquals(EditorOverlay.LinkPrompt(LinkFields("docs", ""), TextSelection(9, 13)), f.vm.uiState.value.overlay)
        f.vm.onAction(EditorAction.LinkDone("docs", "https://d.example"))
        assertEquals("Read the [docs](https://d.example) today\n", f.text())
        // The page hears about it as a host edit.
        assertTrue(page.any { it is HostMessage.Edit })
    }
}
