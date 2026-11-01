package com.scorchsoft.ssmarkdown.history.journal

import com.scorchsoft.ssmarkdown.history.TextEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A journal is deleted only by the run of edits that wrote it (DS-3):
 * the store refuses to delete a sound journal on another base, sets one
 * aside on request instead of reading it, and replaces the caller's own
 * old-base journal in one atomic step after a save (DS-2).
 */
class JournalOwnershipTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val key = "content://docs/plan.md"
    private val oldBase = fingerprintOf("plan")
    private val newBase = fingerprintOf("plan!")
    private val test by lazy { TestStore(folder.root) }
    private val store get() = test.store
    private val quarantine get() = File(folder.root, Quarantine.DIRECTORY_NAME)

    @Test
    fun `markClean on another base quarantines the journal instead of deleting it`() {
        test.start(key, oldBase).apply { append(TextEdit(4, "", "!")) }.close()

        val result = store.markClean(key, expectedBase = newBase)

        assertTrue(result is CleanResult.Quarantined)
        assertFalse(store.fileFor(key).exists())
        val kept = (result as CleanResult.Quarantined).file
        assertEquals(quarantine, kept.parentFile)
        assertTrue(kept.length() > 0)
    }

    @Test
    fun `markClean on the journal's own base deletes it`() {
        test.start(key, oldBase).apply { append(TextEdit(4, "", "!")) }.close()

        assertEquals(CleanResult.Deleted, store.markClean(key, expectedBase = oldBase))
        assertFalse(store.fileFor(key).exists())
        assertFalse(quarantine.exists() && quarantine.list().orEmpty().isNotEmpty())
    }

    @Test
    fun `setAside moves a journal into quarantine untouched and reports none when there is none`() {
        assertNull(store.setAside(key))
        test.start(key, oldBase).apply { append(TextEdit(0, "", "x")) }.close()
        val bytes = store.fileFor(key).readBytes()

        val moved = store.setAside(key)

        assertFalse(store.fileFor(key).exists())
        assertEquals(bytes.toList(), moved?.readBytes()?.toList())
    }

    @Test
    fun `resume replacing the caller's own old-base journal swaps it in one step without quarantine`() {
        test.start(key, oldBase).apply { append(TextEdit(4, "", "!")) }.close()

        val result = store.resume(key, newBase, "plan! more", replacing = oldBase)

        assertTrue(result is OpenResult.Opened)
        (result as OpenResult.Opened).writer.close()
        assertEquals("plan! more", (store.replay(key, "plan!", newBase) as ReplayResult.Ok).text)
        assertFalse(quarantine.exists() && quarantine.list().orEmpty().isNotEmpty())
    }

    @Test
    fun `resume over a journal on an unrelated base still quarantines it`() {
        test.start(key, fingerprintOf("other")).close()

        val result = store.resume(key, newBase, "plan!", replacing = oldBase)

        assertTrue(result is OpenResult.Opened)
        (result as OpenResult.Opened).writer.close()
        assertEquals(1, quarantine.list()?.size)
    }
}
