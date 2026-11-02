package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.history.journal.BaseFingerprint
import com.scorchsoft.ssmarkdown.history.journal.Cancellable
import com.scorchsoft.ssmarkdown.history.journal.FlushScheduler
import com.scorchsoft.ssmarkdown.history.journal.JournalStore
import com.scorchsoft.ssmarkdown.history.journal.ReplayResult
import com.scorchsoft.ssmarkdown.text.EditOp
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * R2-DS-8: after a journal failure, a flush retries too, not only the
 * next keystroke, so edits typed and then left alone become durable once
 * the disk recovers.
 */
class JournalRetryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `R2-DS-8 a flush after the retry interval resyncs a broken journal without more typing`() {
        val root = File(tmp.root, "journal").apply { writeText("not a directory yet") }
        val store = JournalStore(root, FlushScheduler { _, _ -> Cancellable { } })
        var now = 0L
        val link =
            JournalLink(store, "content://docs/a.md", JournalExecutor.INLINE, clock = { now }, retryMillis = 2_000)
        val base = BaseFingerprint.of("alpha".toByteArray(), 1)
        val text = TextDocument(PieceTree.of("alpha"))

        link.beforeEdit(EditOp(5, "", "x"), base, dirty = false) { text.snapshot() }
        text.replace(5, 0, "x")
        assertFalse(link.healthy)

        root.delete() // The disk recovers; nothing more is typed.
        now = 1_000
        link.flush { text.snapshot() }
        assertFalse("too soon: the retry interval still holds", link.healthy)
        now = 2_500
        link.flush { text.snapshot() }

        assertTrue(link.healthy)
        val replay = store.replay("content://docs/a.md", "alpha", base)
        assertEquals("alphax", (replay as ReplayResult.Ok).text)
    }
}
