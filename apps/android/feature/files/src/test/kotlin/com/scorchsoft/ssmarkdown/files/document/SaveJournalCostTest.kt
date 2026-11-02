package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.files.fakes.FakeDocumentProvider
import com.scorchsoft.ssmarkdown.files.fakes.StoreFixture
import com.scorchsoft.ssmarkdown.files.save.SaveOutcome
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/**
 * What a save costs the journal when the document has a full undo tail
 * (review r3 M3). Autosave saves after every 2 s pause in typing, so a
 * save must not rewrite the history: it appends one Rebase record to the
 * journal it already has, and never reads the undo stack on the main
 * thread. Measured on the host (2026-11-02, a loaded 4-core container),
 * 700,000 one-char keystrokes, a full 8 MB tail, eight saves each:
 *
 * | | bytes written per save | main thread | journal thread |
 * |---|---|---|---|
 * | before (a clean journal rewritten) | 8,642,915 | 65 to 245 ms | 340 to 850 ms, two fsyncs |
 * | after (a Rebase record appended) | 66 | under 0.01 ms (0.9 ms the first time) | 0.2 to 8.5 ms, one fsync |
 */
class SaveJournalCostTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a save with a full undo tail appends one small record and never reads the history`() =
        runTest {
            val provider = FakeDocumentProvider()
            // A key and a backspace, over and over: a full tail of one-char ops on a file that stays small,
            // so the fake provider's byte-at-a-time write stays cheap.
            val uri = provider.put("content://docs/long.md", "alpha\n")
            val fixture = StoreFixture(tmp.root, provider, UnconfinedTestDispatcher(testScheduler))
            val doc = (fixture.store.open(uri) as OpenOutcome.Opened).document
            repeat(KEYSTROKES / 2) {
                doc.replace(doc.document.text.length, 0, "x")
                doc.replace(doc.document.text.length - 1, 1, "")
            }
            doc.replace(doc.document.text.length, 0, "!")
            assertTrue(fixture.store.save(uri) is SaveOutcome.Saved)
            // Saves no longer restart the run, so compaction alone bounds a journal of keystrokes.
            val length = fixture.journals.fileFor(uri.value).length()
            assertTrue("the journal is $length bytes", length <= MAX_JOURNAL_BYTES)
            val tail = doc.journal.historySource
            var historyReads = 0
            doc.journal.historySource = {
                historyReads++
                tail()
            }

            repeat(SAVES) {
                doc.replace(doc.document.text.length, 0, "y")
                fixture.store.flushJournals()
                val file = fixture.journals.fileFor(uri.value)
                val before = file.length()
                val identity = file.identity()

                assertTrue(fixture.store.save(uri) is SaveOutcome.Saved)

                assertEquals("the journal is appended to, never replaced", identity, file.identity())
                val written = file.length() - before
                assertTrue("a save wrote $written bytes", written <= MAX_BYTES_PER_SAVE)
            }
            assertEquals("a save never exports the undo stack", 0, historyReads)
            assertTrue(fixture.journals.holdsHistoryOnly(uri.value))
        }

    private fun File.identity(): Any? = Files.readAttributes(toPath(), BasicFileAttributes::class.java).fileKey()

    private companion object {
        /** More than the 8 MB tail holds (about 600,000 such ops), so the tail is full. */
        const val KEYSTROKES = 700_000
        const val SAVES = 5

        /** One Rebase record: a 17-byte frame and a 49-byte payload. */
        const val MAX_BYTES_PER_SAVE = 66L

        /**
         * 16 MiB of records before compaction, plus what compaction writes: an
         * 8 MB tail and a tiny text. Without the byte trigger these 700,000 ops
         * make a 34 MB journal.
         */
        const val MAX_JOURNAL_BYTES = (16L + 8L) shl 20
    }
}
