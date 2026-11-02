package com.scorchsoft.ssmarkdown.preview.editing

import com.scorchsoft.ssmarkdown.preview.bridge.HostMessage
import com.scorchsoft.ssmarkdown.preview.bridge.LineChange
import com.scorchsoft.ssmarkdown.preview.bridge.TextEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PreviewEditSyncTest {
    /** The page's half (web/src/core/editSync.ts), as a reference model. */
    private class Page(
        var text: String,
        var seen: Int,
    ) {
        val outstanding = ArrayList<List<TextEdit>>()

        fun type(edits: List<TextEdit>): Int {
            text = TextTransform.apply(text, edits)
            outstanding += edits
            return seen
        }

        fun ack(rev: Int) {
            assertEquals(seen + 1, rev)
            seen = rev
            outstanding.removeAt(0)
        }

        fun host(edit: HostMessage.Edit) {
            assertEquals(seen + 1, edit.rev)
            var incoming = edit.ops ?: error("no ops")
            for (i in outstanding.indices) {
                val t = TextTransform.transform(incoming, outstanding[i])
                incoming = t.host
                outstanding[i] = t.page
            }
            seen = edit.rev
            text = TextTransform.apply(text, incoming)
        }
    }

    @Test
    fun `a page edit made before a host change is rebased, not rejected`() {
        val sync = PreviewEditSync()
        var host = "hello world"
        val load = sync.load(host)
        val page = Page(host, load.rev)
        val base = page.type(listOf(TextEdit(5, 5, "!")))
        val edit = sync.onHostEdits(PreviewText.of(host), listOf(TextEdit(0, 0, "> "))) ?: error("no edit")
        host = TextTransform.apply(host, listOf(TextEdit(0, 0, "> ")))
        val outcome = sync.onPageEdits(base, listOf(TextEdit(5, 5, "!")), host.length) as PageEditOutcome.Apply
        assertEquals(listOf(TextEdit(7, 7, "!")), outcome.edits)
        host = TextTransform.apply(host, outcome.edits)
        page.host(edit)
        page.ack(outcome.ack.rev)
        assertEquals("> hello! world", host)
        assertEquals(host, page.text)
    }

    @Test
    fun `asks for a reload only when rebasing is impossible`() {
        val sync = PreviewEditSync(maxUnseen = 2)
        sync.load("abc")
        assertTrue(sync.onPageEdits(99, listOf(TextEdit(0, 0, "x")), 3) is PageEditOutcome.Resync)
        assertTrue(sync.onPageEdits(1, listOf(TextEdit(2, 1, "x")), 3) is PageEditOutcome.Resync)
        assertTrue(sync.onPageEdits(1, listOf(TextEdit(0, 9, "")), 3) is PageEditOutcome.Resync)
        var text = "abc"
        repeat(3) {
            sync.onHostEdits(PreviewText.of(text), listOf(TextEdit(0, 0, "h")))
            text = "h$text"
        }
        assertTrue(sync.onPageEdits(1, listOf(TextEdit(0, 0, "x")), text.length) is PageEditOutcome.Resync)
    }

    @Test
    fun `line changes group edits on one line and run last to first`() {
        val before = PreviewText.of("one\ntwo three\nfour\nfive")
        val changes =
            PreviewEditSync.lineChanges(
                before,
                listOf(TextEdit(4, 4, "**"), TextEdit(7, 7, "**"), TextEdit(19, 23, "5\nsix")),
            )
        assertEquals(
            listOf(LineChange(3, 4, listOf("5", "six")), LineChange(1, 2, listOf("**two** three"))),
            changes,
        )
    }

    /** Kotlin and the page with messages queued both ways, delivered when the dice say. */
    private class Simulation(
        start: String,
    ) {
        val sync = PreviewEditSync()
        var host = start
        val page = Page(start, sync.load(start).rev)
        val toHost = ArrayDeque<Pair<Int, List<TextEdit>>>()
        val toPage = ArrayDeque<HostMessage>()
        val typed = StringBuilder()

        fun typeInPreview(e: TextEdit) {
            typed.append(e.insert)
            toHost.addLast(page.type(listOf(e)) to listOf(e))
        }

        fun typeInCode(e: TextEdit) {
            typed.append(e.insert)
            toPage.addLast(sync.onHostEdits(PreviewText.of(host), listOf(e)) ?: error("no edit"))
            host = TextTransform.apply(host, listOf(e))
        }

        fun deliverUp() {
            val (base, edits) = toHost.removeFirstOrNull() ?: return
            val outcome = sync.onPageEdits(base, edits, host.length) as PageEditOutcome.Apply
            host = TextTransform.apply(host, outcome.edits)
            toPage.addLast(outcome.ack)
        }

        fun deliverDown() {
            when (val m = toPage.removeFirstOrNull()) {
                is HostMessage.Ack -> page.ack(m.rev)
                is HostMessage.Edit -> page.host(m)
                else -> Unit
            }
        }

        fun drain() {
            while (toHost.isNotEmpty() || toPage.isNotEmpty()) {
                deliverUp()
                deliverDown()
            }
        }
    }

    @Test
    fun `property - concurrent typing in Code and Preview converges and keeps every keystroke`() {
        for (deletes in listOf(false, true)) {
            val rnd = Random(if (deletes) 8 else 7)
            repeat(300) { round ->
                val sim = Simulation("The quick brown fox\njumps over")
                repeat(40) {
                    when (rnd.nextInt(5)) {
                        0 -> sim.typeInPreview(oneEdit(rnd, sim.page.text.length, "PQR", deletes))
                        1 -> sim.typeInCode(oneEdit(rnd, sim.host.length, "XYZ", deletes))
                        2 -> sim.deliverUp()
                        else -> sim.deliverDown()
                    }
                }
                sim.drain()
                assertEquals("round $round", sim.host, sim.page.text)
                if (!deletes) {
                    for (c in "PQRXYZ") {
                        assertEquals("round $round", sim.typed.count { it == c }, sim.host.count { it == c })
                    }
                }
            }
        }
    }

    private fun oneEdit(
        rnd: Random,
        length: Int,
        alphabet: String,
        deletes: Boolean,
    ): TextEdit {
        val from = rnd.nextInt(length + 1)
        val to = if (deletes) minOf(length, from + rnd.nextInt(3)) else from
        return TextEdit(from, to, String(CharArray(1 + rnd.nextInt(2)) { alphabet[rnd.nextInt(alphabet.length)] }))
    }
}
