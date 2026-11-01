package com.scorchsoft.ssmarkdown.editor.preview

import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.preview.bridge.HostMessage
import com.scorchsoft.ssmarkdown.preview.bridge.TextEdit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Preview typing at the edges of a binding: across a document switch
 * (DS-13) and just before the app is paused (DS-14).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreviewSessionSafetyTest {
    @get:Rule val temp = TemporaryFolder()

    private val page = ArrayList<HostMessage>()

    private fun text(f: EditorFixture) =
        f.vm.active
            ?.text
            ?.text
            .toString()

    @Test
    fun `an edit typed into the previous document never lands in the next one (DS-13)`() {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        f.vm.openDocument(f.file("a.md", "alpha\n"))
        f.vm.preview.attachPage { page += it }
        val first = page.filterIsInstance<HostMessage.Load>().last()
        val a = f.vm.active
        page.clear()

        f.vm.openDocument(f.file("b.md", "beta\n"))
        val second = page.filterIsInstance<HostMessage.Load>().last()
        assertTrue("each binding has its own epoch", first.epoch != second.epoch)
        assertTrue(
            "the page is asked to flush before the switch",
            page.indexOf(HostMessage.Flush) in 0 until page.indexOf(second),
        )

        // The page's last keystroke in a.md arrives after the switch, stamped with a.md's epoch.
        f.vm.preview.onSourceEdits(first.rev, listOf(TextEdit(0, 0, "TYPED ")), first.epoch)
        assertEquals("beta\n", text(f))
        assertEquals("TYPED alpha\n", a?.text?.text.toString())
        assertTrue("no ack for a.md reaches b.md's page", page.none { it is HostMessage.Ack })

        // Stamped with the current epoch, it applies to b.md as usual.
        f.vm.preview.onSourceEdits(second.rev, listOf(TextEdit(0, 0, "B ")), second.epoch)
        assertEquals("B beta\n", text(f))
    }

    @Test
    fun `pausing flushes the page, and typing that arrives while paused is journalled at once (DS-14)`() {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        f.vm.openDocument(f.file("a.md", "alpha\n"))
        f.vm.preview.attachPage { page += it }
        val load = page.filterIsInstance<HostMessage.Load>().last()
        page.clear()

        f.vm.onPause()
        assertTrue(HostMessage.Flush in page)

        f.vm.preview.onSourceEdits(load.rev, listOf(TextEdit(0, 0, "LASTWORDS")), load.epoch)
        f.store.awaitJournals(2_000)
        val journalled =
            File(temp.root, "journal").walk().filter { it.isFile }.any {
                "LASTWORDS" in
                    it.readText(Charsets.ISO_8859_1)
            }
        assertTrue("the paused edit is on disk in the journal", journalled)
    }
}
