package com.scorchsoft.ssmarkdown.history.journal

import com.scorchsoft.ssmarkdown.history.TextEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CompactionTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val baseText = "# Draft\r\n"
    private val base = fingerprintOf(baseText)

    @Test
    fun `compaction keeps the text, shrinks the file and appending carries on`() {
        val test = TestStore(folder.root)
        val writer = test.start("doc", base)
        var text = baseText
        repeat(EDITS) {
            val edit = TextEdit(text.length, "", "line $it 😀\r\n")
            writer.append(edit)
            text = text.applying(edit)
        }
        writer.flush()
        val file = test.store.fileFor("doc")
        val before = file.length()

        assertEquals(JournalWriteResult.Written(EDITS + 1L), writer.compact(text))
        assertTrue(file.length() < before)
        writer.append(TextEdit(0, "", "after "))
        writer.close()

        val replayed = test.store.replay("doc", baseText, base) as ReplayResult.Ok
        assertEquals("after $text", replayed.text)
        assertEquals(EDITS + 2L, replayed.lastSeq)
        assertEquals(2, replayed.recordCount)
    }

    @Test
    fun `a crash between writing the new file and the rename leaves the old journal whole`() {
        val test = TestStore(folder.root)
        val writer = test.start("doc", base)
        writer.append(TextEdit(baseText.length, "", "kept"))
        writer.close()
        val file = test.store.fileFor("doc")

        // Compaction gets as far as a half-written `.new`, then the process dies.
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        stream.write(HeaderCodec.encode(JournalHeader("doc", base, 0)))
        stream.write(byteArrayOf(1, 2, 3))
        stream.close()

        assertEquals("# Draft\r\nkept", (test.store.replay("doc", baseText, base) as ReplayResult.Ok).text)
        val scan = test.store.scan()
        assertEquals(listOf("doc"), scan.unclean.map { it.documentKey })
        assertFalse("stale .new is cleared", atomic.newFile.exists())
        assertTrue(scan.quarantined.isEmpty())
    }

    @Test
    fun `a failed compaction changes nothing and the writer carries on`() {
        val test = TestStore(folder.root)
        val writer = test.start("doc", base)
        writer.append(TextEdit(baseText.length, "", "one"))
        val file = test.store.fileFor("doc")
        // A directory where `.new` must go makes the write fail.
        AtomicFile(file).newFile.mkdirs()

        assertTrue(writer.compact("# Draft\r\none") is JournalWriteResult.Failed)
        writer.append(TextEdit(baseText.length + 3, "", " two"))
        writer.close()

        assertEquals("# Draft\r\none two", (test.store.replay("doc", baseText, base) as ReplayResult.Ok).text)
    }

    @Test
    fun `AtomicFile replaces in one step and failWrite leaves the old content`() {
        val target = folder.newFile("settings.json").apply { writeText("old") }
        val atomic = AtomicFile(target)

        val abandoned = atomic.startWrite().apply { write("half".toByteArray()) }
        atomic.failWrite(abandoned)
        assertEquals("old", target.readText())
        assertFalse(atomic.newFile.exists())

        atomic.write("new".toByteArray(), " and whole".toByteArray())
        assertEquals("new and whole", target.readText())
        assertFalse(atomic.newFile.exists())
    }

    private companion object {
        const val EDITS = 200
    }
}
