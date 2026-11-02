package com.scorchsoft.ssmarkdown.files.save

import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The base copies' own guarantees, as the never-lose-work flows rely on them. */
class BaseCopyStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val uri = DocumentUri("content://docs/note.md")

    @Test
    fun `a copy staged while another waits to commit never truncates or deletes it`() {
        // Found by the random kill test: a resolved conflict's copy waits on the journal thread
        // while an outside change stages the same document again, then abandons it.
        val copies = BaseCopyStore(tmp.newFolder())
        val first = copies.stage(uri)
        first.stream.write("theirs, kept\n".toByteArray())
        val second = copies.stage(uri)
        second.stream.write("a later look\n".toByteArray())
        second.abandon()

        assertTrue(first.commit())
        assertEquals("theirs, kept\n", copies.fileFor(uri).readText())
    }

    @Test
    fun `leftover staging files go at launch, with every copy no journal needs`() {
        val copies = BaseCopyStore(tmp.newFolder())
        copies.stage(uri).apply { stream.write(1) }.commit()
        copies.stage(uri).stream.write(2) // A kill before this one committed.

        copies.retainOnly(listOf(uri))

        assertEquals(listOf(copies.fileFor(uri).name), copies.root.list()?.toList())
    }
}
