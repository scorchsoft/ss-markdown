package com.scorchsoft.ssmarkdown.preview

import com.scorchsoft.ssmarkdown.preview.bridge.HostMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * edge-cases "Export PDF from Code did nothing, then printed the next file":
 * a print request belongs to the page as it was when it was made, ends once,
 * and never opens a dialog for anything else.
 */
class PrintSessionTest {
    private val page = ArrayList<HostMessage>()
    private val dialogs = ArrayList<String>()
    private val outcomes = ArrayList<PrintOutcome>()
    private val timers = ArrayList<Pair<Long, () -> Unit>>()
    private var cancelledTimers = 0
    private var canOpen = true

    private val session =
        PrintSession(
            sendToPage = { page += it },
            openDialog = { job ->
                if (canOpen) dialogs += job
                canOpen
            },
            schedule = { delay, action ->
                timers += delay to action
                ({ cancelledTimers++ })
            },
        )

    private fun ask(job: String) = session.request(job) { outcomes += it }

    private fun lastId() = page.filterIsInstance<HostMessage.PreparePrint>().last().requestId

    @Test
    fun `a print asked for A never opens for B once B is loaded`() {
        // Code view on A: the request goes out, but the page is not on screen and never answers.
        ask("A")
        val id = lastId()
        // The user opens B; the page now holds B and finishes the stalled preparation.
        session.onDocumentLoaded()
        session.onPageReady(id)

        assertEquals("no dialog opens for a document nobody asked to print", emptyList<String>(), dialogs)
        assertEquals(listOf(PrintOutcome.DocumentChanged), outcomes)
        assertEquals("the page returns to reading", HostMessage.FinishPrint, page.last())
    }

    @Test
    fun `the page's answer opens the dialog for the job that asked, once`() {
        ask("A")
        session.onPageReady(lastId())
        session.onPageReady(lastId())

        assertEquals(listOf("A"), dialogs)
        assertEquals(listOf(PrintOutcome.Opened), outcomes)
        assertEquals("the timeout is disarmed", 1, cancelledTimers)
        assertFalse(session.busy)
    }

    @Test
    fun `an answer to an older request opens nothing`() {
        ask("A")
        val first = lastId()
        ask("A again")
        session.onPageReady(first)

        assertTrue(dialogs.isEmpty())
        assertEquals(listOf(PrintOutcome.Superseded), outcomes)
        session.onPageReady(lastId())
        assertEquals(listOf("A again"), dialogs)
    }

    @Test
    fun `no answer in time ends the request, and a late answer opens nothing`() {
        ask("A")
        val id = lastId()
        val (delay, fire) = timers.single()
        assertEquals(PrintSession.TIMEOUT_MILLIS, delay)

        fire()
        session.onPageReady(id)

        assertEquals(listOf(PrintOutcome.TimedOut), outcomes)
        assertTrue(dialogs.isEmpty())
        assertEquals(HostMessage.FinishPrint, page.last())
    }

    @Test
    fun `a destroyed page ends the request without talking to the next page`() {
        ask("A")
        page.clear()
        session.onPageGone()

        assertEquals(listOf(PrintOutcome.PageGone), outcomes)
        assertTrue("nothing is queued for the page that replaces it", page.isEmpty())
    }

    @Test
    fun `a page that cannot open the dialog reports it`() {
        canOpen = false
        ask("A")
        session.onPageReady(lastId())

        assertEquals(listOf(PrintOutcome.PageGone), outcomes)
        assertEquals(HostMessage.FinishPrint, page.last())
    }

    @Test
    fun `a caller that stops waiting cancels the request`() {
        ask("A")
        val id = lastId()
        session.cancel()
        session.onPageReady(id)

        assertEquals(listOf(PrintOutcome.Cancelled), outcomes)
        assertTrue(dialogs.isEmpty())
    }
}
