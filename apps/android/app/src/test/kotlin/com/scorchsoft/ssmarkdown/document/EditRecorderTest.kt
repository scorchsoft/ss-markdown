package com.scorchsoft.ssmarkdown.document

import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextDocument
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * The bridge from the editor's line reports to the journal: replaying the
 * recorded ops over the original text must reproduce the edited text
 * exactly, which is what recovery does after a crash.
 */
class EditRecorderTest {
    private fun lineChange(
        before: String,
        offset: Int,
        deleted: Int,
        inserted: String,
    ): Triple<Int, Int, Int> {
        val first = before.substring(0, offset).count { it == '\n' }
        val removed = before.substring(offset, offset + deleted).count { it == '\n' }
        return Triple(first, removed, inserted.count { it == '\n' })
    }

    @Test
    fun `replaying recorded ops reproduces random edits`() {
        val random = Random(7)
        val start = "# Title\n\nSome text\n- a\n- b\n"
        val document = TextDocument(PieceTree.of(start))
        val recorder = EditRecorder(document.snapshot())
        val replay = StringBuilder(start)
        repeat(300) {
            val before = document.text.toString()
            val offset = random.nextInt(before.length + 1)
            val deleted = random.nextInt(minOf(6, before.length - offset) + 1)
            val inserted = listOf("x", "\n", "ab\ncd", "", "##").random(random)
            val (first, removed, added) = lineChange(before, offset, deleted, inserted)
            document.replace(offset, deleted, inserted)
            val op = recorder.record(document.text, first, removed, added)
            replay.replace(op.offset, op.offset + op.deleted.length, op.inserted.toString())
            assertEquals(document.text.toString(), replay.toString())
        }
    }
}
