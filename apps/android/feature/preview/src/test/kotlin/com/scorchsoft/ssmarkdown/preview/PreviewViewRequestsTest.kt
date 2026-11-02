package com.scorchsoft.ssmarkdown.preview

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.preview.bridge.HostMessage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * edge-cases "Export PDF from Code did nothing, then printed the next file",
 * at the view: whatever was asked of the page ends, with nothing for the
 * document asked about, as soon as the page is given a document or destroyed.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PreviewViewRequestsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun load(text: String) = HostMessage.Load(rev = 1, text = text, anchorLine = 0, epoch = 2)

    @Test
    fun `a print waiting on the page ends when the page is given another document`() {
        val view = PreviewView(context)
        view.send(load("# A"))
        val outcome = GlobalScope.async(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) { view.print("A") }
        assertFalse(outcome.isCompleted)

        view.send(load("# B"))

        assertTrue(outcome.isCompleted)
        assertEquals(PrintOutcome.DocumentChanged, outcome.getCompleted())
        view.release()
    }

    @Test
    fun `a print waiting on the page ends when the page is destroyed`() {
        val view = PreviewView(context)
        val outcome = GlobalScope.async(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) { view.print("A") }

        view.release()

        assertEquals(PrintOutcome.PageGone, outcome.getCompleted())
    }

    @Test
    fun `a rich-text copy waiting on the page gets nothing from the next document`() {
        val view = PreviewView(context)
        view.send(load("# A"))
        val html = GlobalScope.async(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) { view.exportHtml(0, 1) }

        view.send(load("# B"))

        assertTrue("the copy falls back to A's plain text at once", html.isCompleted)
        assertNull(html.getCompleted())
        view.release()
    }

    @Test
    fun `a view that is not in a window is not on screen`() {
        assertFalse(PreviewView(context).isOnScreen)
    }
}
