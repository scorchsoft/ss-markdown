package com.scorchsoft.ssmarkdown.editor.preview

import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.MenuItem
import com.scorchsoft.ssmarkdown.editor.MessageKind
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.preview.PrintOutcome
import com.scorchsoft.ssmarkdown.preview.PrintTarget
import com.scorchsoft.ssmarkdown.preview.bridge.HostMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * edge-cases "Export PDF from Code did nothing, then printed the next file"
 * (found on a phone in 0.2.0): ⋮ → Export PDF in Code view on A did nothing,
 * and opening B then opened the print dialog for B. Every action deferred
 * until the page answers (PDF, diagram exports) belongs to the document it
 * was asked for, waits for a page that is drawn, and ends with a message
 * rather than lingering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeferredPageActionsTest {
    @get:Rule val temp = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val f by lazy { EditorFixture(temp.root, UnconfinedTestDispatcher(scheduler)) }
    private val page = ArrayList<HostMessage>()

    /** The Preview page as the session sees it: drawn or not, and what it was asked to print. */
    private inner class FakePage : PrintTarget {
        override var isOnScreen = false

        /** Each job, with the title of the document on screen when it was asked for. */
        val printed = ArrayList<Pair<String, String>>()
        var answer: CompletableDeferred<PrintOutcome>? = null

        override suspend fun print(jobName: String): PrintOutcome {
            printed += jobName to f.vm.state.title
            return answer?.await() ?: PrintOutcome.Opened
        }
    }

    private val fake = FakePage()

    private fun open(name: String) =
        f.vm.openDocument(f.file(name, "# $name\n\n```mermaid\ngraph LR\n  A --> B\n```\n"))

    private fun exportPdf() = f.vm.onAction(EditorAction.ChooseMenuItem(MenuItem.ExportPdf))

    private fun message() =
        f.vm.uiState.value.message
            ?.kind

    @Test
    fun `export in Code on A, then opening B, never prints - neither A on the wrong page nor B`() {
        open("a.md")
        f.vm.preview.attachPage(fake) { page += it }
        f.vm.onAction(EditorAction.SelectMode(ViewMode.Code))
        exportPdf()
        scheduler.advanceTimeBy(500)
        assertTrue("nothing is printed while the page is not drawn", fake.printed.isEmpty())

        open("b.md")
        fake.isOnScreen = true
        scheduler.advanceTimeBy(10_000)

        assertTrue("B's page is never printed for A's request", fake.printed.isEmpty())
        assertEquals("the export says it stopped", MessageKind.ExportStopped, message())
    }

    @Test
    fun `export in Code prints A once the Preview is drawn`() {
        open("a.md")
        f.vm.preview.attachPage(fake) { page += it }
        f.vm.onAction(EditorAction.SelectMode(ViewMode.Code))

        exportPdf()
        assertEquals("Code switches to Preview so the page can render", ViewMode.Preview, f.vm.state.mode)
        scheduler.advanceTimeBy(200)
        fake.isOnScreen = true
        scheduler.advanceTimeBy(200)

        assertEquals(listOf("a" to "a.md"), fake.printed)
        assertNull(message())
    }

    @Test
    fun `export in Preview or Split prints at once, for the document on screen`() {
        open("a.md")
        fake.isOnScreen = true
        f.vm.preview.attachPage(fake) { page += it }
        f.vm.onAction(EditorAction.SelectMode(ViewMode.Split))

        exportPdf()

        assertEquals(ViewMode.Split, f.vm.state.mode)
        assertEquals(listOf("a"), fake.printed.map { it.first })
    }

    @Test
    fun `no page in time says how to try again`() {
        open("a.md")
        exportPdf()
        scheduler.advanceTimeBy(5_000)

        assertEquals(MessageKind.PdfFailed, message())
    }

    @Test
    fun `a page that never finishes reports failure`() {
        open("a.md")
        fake.isOnScreen = true
        fake.answer = CompletableDeferred(PrintOutcome.TimedOut)
        f.vm.preview.attachPage(fake) { page += it }

        exportPdf()

        assertEquals(MessageKind.PdfFailed, message())
    }

    @Test
    fun `a document switch while the page prepares stops the export with a message`() {
        open("a.md")
        fake.isOnScreen = true
        val answer = CompletableDeferred<PrintOutcome>()
        fake.answer = answer
        f.vm.preview.attachPage(fake) { page += it }
        exportPdf()

        open("b.md")
        // PreviewView ends the request when it sends B's load (PreviewViewRequestsTest).
        answer.complete(PrintOutcome.DocumentChanged)

        assertEquals(MessageKind.ExportStopped, message())
    }

    @Test
    fun `a diagram export waiting on A's page gets nothing once B is open`() {
        open("a.md")
        fake.isOnScreen = true
        f.vm.preview.attachPage(fake) { page += it }
        val pending =
            f.scope.async(start = CoroutineStart.UNDISPATCHED) {
                f.vm.preview.exports
                    .request(2, png = true)
            }
        val request = page.filterIsInstance<HostMessage.ExportImage>().single()

        open("b.md")
        // B's page draws the diagram at that line and answers the old id: it must not count.
        f.vm.preview.onExportResult(request.requestId, "image/png", byteArrayOf(1))

        assertTrue(pending.isCompleted)
        assertNull(pending.getCompleted())
    }

    @Test
    fun `a diagram export waits for the page to be drawn, then asks it`() {
        open("a.md")
        f.vm.preview.attachPage(fake) { page += it }
        val pending =
            f.scope.async(start = CoroutineStart.UNDISPATCHED) {
                f.vm.preview.exports
                    .request(2, png = true)
            }
        assertTrue("nothing is asked of a page that is not drawn", page.none { it is HostMessage.ExportImage })

        fake.isOnScreen = true
        scheduler.advanceTimeBy(100)
        val request = page.filterIsInstance<HostMessage.ExportImage>().single()
        f.vm.preview.onExportResult(request.requestId, "image/png", byteArrayOf(7))

        assertArrayEquals(byteArrayOf(7), pending.getCompleted())
    }
}
