package com.scorchsoft.ssmarkdown.history.journal

import com.scorchsoft.ssmarkdown.history.TextEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Salvage: the text of edits that cannot be replayed onto their file is
 * still readable (R2-DS-2, R2-DS-3). Never changes the journal.
 */
class SalvageTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val key = "content://docs/note.md"
    private val base = fingerprintOf("alpha\n")
    private val test by lazy { TestStore(folder.root) }
    private val store get() = test.store

    @Test
    fun `edits replay onto the base text when the caller has it`() {
        test.start(key, base).apply { append(TextEdit(6, "", "precious")) }.close()
        var asked: BaseFingerprint? = null

        val salvage =
            store.salvage(key) {
                asked = it
                "alpha\n"
            }

        assertEquals(Salvage.Text("alpha\nprecious", damaged = false), salvage)
        assertTrue(asked!!.matches(base))
        assertTrue("salvage only reads", store.fileFor(key).exists())
    }

    @Test
    fun `a snapshot needs no base`() {
        (store.resume(key, base, "alpha\nprecious") as OpenResult.Opened)
            .writer
            .apply {
                append(TextEdit(14, "", "!"))
            }.close()

        assertEquals(Salvage.Text("alpha\nprecious!", damaged = false), store.salvage(key) { null })
    }

    @Test
    fun `without snapshot or base only the typed text comes back, as fragments`() {
        test
            .start(key, base)
            .apply {
                append(TextEdit(6, "", "pre"))
                append(TextEdit(9, "", "cious"))
            }.close()

        assertEquals(Salvage.Fragments("precious"), store.salvage(key) { null })
    }

    @Test
    fun `an edit that does not fit the base stops the rebuild and says damaged`() {
        test
            .start(key, base)
            .apply {
                append(TextEdit(6, "", "one"))
                append(TextEdit(0, "zzz", "two"))
            }.close()

        assertEquals(Salvage.Text("alpha\none", damaged = true), store.salvage(key) { "alpha\n" })
    }

    @Test
    fun `no journal, a newer format or a damaged header is unavailable`() {
        assertTrue(store.salvage(key) { null } is Salvage.Unavailable)
        folder.root.mkdirs()
        store.fileFor(key).writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(store.salvage(key) { null } is Salvage.Unavailable)
    }

    @Test
    fun `set-aside journals are listed with their document and can be salvaged`() {
        test.start(key, base).apply { append(TextEdit(6, "", "kept")) }.close()
        val moved = store.setAside(key)!!
        folder.root.mkdirs()
        File(folder.root, Quarantine.DIRECTORY_NAME).resolve("garbage").writeBytes(byteArrayOf(7))

        val listed = store.listSetAside()

        assertEquals(2, listed.size)
        val ours = listed.single { it.file == moved }
        assertEquals(key, ours.documentKey)
        assertEquals(test.clock.now, ours.createdAtMillis)
        assertNull(listed.single { it.file != moved }.documentKey)
        assertEquals(Salvage.Fragments("kept"), store.salvageSetAside(ours.file) { null })
    }

    @Test
    fun `salvaging a file outside the quarantine is refused`() {
        test.start(key, base).apply { append(TextEdit(6, "", "x")) }.close()

        assertTrue(store.salvageSetAside(store.fileFor(key)) { null } is Salvage.Unavailable)
        assertTrue(store.salvageSetAside(folder.newFile("elsewhere")) { null } is Salvage.Unavailable)
    }
}
