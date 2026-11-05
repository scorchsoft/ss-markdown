package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.preview.FormattedCopy
import com.scorchsoft.ssmarkdown.preview.PrintOutcome
import com.scorchsoft.ssmarkdown.preview.PrintTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ⋮ → Export… (Andrew, 2026-11-04): a chooser of PDF, HTML and plain text.
 * PDF prints as before; HTML and plain text come from the page's formatted
 * copy of the whole document and are saved with the right type and name.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportControllerTest {
    @get:Rule val temp = TemporaryFolder()

    private val f by lazy { EditorFixture(temp.root, UnconfinedTestDispatcher(TestCoroutineScheduler())) }

    private inner class FakePage : PrintTarget {
        override var isOnScreen = true
        val printed = ArrayList<String>()
        val asked = ArrayList<Pair<Int, Int>>()
        var copy: FormattedCopy? =
            FormattedCopy("<h1>Title</h1><p>Body &amp; more</p>", "Title\n=====\n\nBody & more\n\n")

        override suspend fun print(jobName: String): PrintOutcome {
            printed += jobName
            return PrintOutcome.Opened
        }

        override suspend fun exportFormatted(
            fromLine: Int,
            toLine: Int,
            maxChars: Int,
        ): FormattedCopy? {
            asked += fromLine to toLine
            return copy
        }
    }

    private val page = FakePage()

    private fun openInPreview() {
        f.vm.openDocument(f.file("notes.md", DOC))
        f.vm.onAction(EditorAction.SelectMode(ViewMode.Preview))
        f.vm.preview.attachPage(page) {}
    }

    private fun export(format: ExportFormat) {
        f.vm.onAction(EditorAction.ChooseMenuItem(MenuItem.Export))
        assertEquals(EditorOverlay.ExportChooser, f.vm.uiState.value.overlay)
        f.vm.onAction(EditorAction.ExportAs(format))
        assertEquals(null, f.vm.uiState.value.overlay)
    }

    private fun picked() = f.events.filterIsInstance<EditorEvent.PickExportLocation>().last()

    @Test
    fun `PDF prints this file`() {
        openInPreview()
        export(ExportFormat.Pdf)
        assertEquals(listOf("notes"), page.printed)
    }

    @Test
    fun `HTML is one page of the whole document, saved as notes html`() {
        openInPreview()
        export(ExportFormat.Html)
        assertEquals(listOf(0 to DOC.lines().size), page.asked)
        val event = picked()
        assertEquals("notes.html", event.suggestedName)
        assertEquals("text/html", event.mimeType)
        assertTrue(event.text.startsWith("<!doctype html>"))
        assertTrue(event.text.contains("<meta charset=\"utf-8\">"))
        assertTrue(event.text.contains("<title>notes</title>"))
        assertTrue(event.text.contains("<h1>Title</h1><p>Body &amp; more</p>"))
    }

    @Test
    fun `plain text has no Markdown symbols and ends in one line break`() {
        openInPreview()
        export(ExportFormat.Text)
        val event = picked()
        assertEquals("notes.txt", event.suggestedName)
        assertEquals("text/plain", event.mimeType)
        assertEquals("Title\n=====\n\nBody & more\n", event.text)
    }

    @Test
    fun `from Code the page is read and Code comes back`() {
        f.vm.openDocument(f.file("notes.md", DOC))
        f.vm.preview.attachPage(page) {}
        assertEquals(ViewMode.Code, f.vm.uiState.value.mode)
        export(ExportFormat.Text)
        assertEquals("notes.txt", picked().suggestedName)
        assertEquals(ViewMode.Code, f.vm.uiState.value.mode)
    }

    @Test
    fun `no page answers, so it says so and saves nothing`() {
        openInPreview()
        page.copy = null
        export(ExportFormat.Html)
        assertTrue(f.events.none { it is EditorEvent.PickExportLocation })
        assertEquals(
            MessageKind.ExportFailed,
            f.vm.uiState.value.message
                ?.kind,
        )
    }

    @Test
    fun `a title with markup is escaped in the page`() {
        val html = ExportController.page("a <b> & c", FormattedCopy("<p>x</p>", "x"))
        assertTrue(html.contains("<title>a &lt;b&gt; &amp; c</title>"))
    }

    private companion object {
        const val DOC = "# Title\n\n**Body** & more\n"
    }
}
