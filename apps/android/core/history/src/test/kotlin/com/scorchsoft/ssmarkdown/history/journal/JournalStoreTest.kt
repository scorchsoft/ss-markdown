package com.scorchsoft.ssmarkdown.history.journal

import com.scorchsoft.ssmarkdown.history.TextEdit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class JournalStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val key = "content://com.android.externalstorage.documents/document/primary%3ANotes%2Fplan.md"
    private val base = fingerprintOf("plan")

    private val test by lazy { TestStore(folder.root) }
    private val store get() = test.store
    private val quarantine get() = File(folder.root, Quarantine.DIRECTORY_NAME)

    @Test
    fun `a second start never overwrites unsaved edits`() {
        test.start(key, base).apply { append(TextEdit(4, "", "!")) }.close()

        assertEquals(OpenResult.AlreadyExists(key), store.start(key, base))
        assertEquals("plan!", (store.replay(key, "plan", base) as ReplayResult.Ok).text)
    }

    @Test
    fun `the startup scan lists unclean journals by key and base`() {
        test.start(key, base).close()
        test.start("second", fingerprintOf("x")).close()

        val scan = store.scan()
        assertEquals(setOf(key, "second"), scan.unclean.map { it.documentKey }.toSet())
        assertTrue(
            scan.unclean
                .first { it.documentKey == key }
                .base
                .matches(base),
        )
        assertTrue(scan.quarantined.isEmpty() && scan.unsupported.isEmpty() && scan.failures.isEmpty())
    }

    @Test
    fun `garbage is quarantined, never deleted, and other files are left alone`() {
        folder.root.mkdirs()
        val garbage = File(folder.root, "0123.ssmj").apply { writeBytes(byteArrayOf(9, 9, 9, 9, 9)) }
        val foreign = File(folder.root, "notes.txt").apply { writeText("not ours") }

        val scan = store.scan()
        assertEquals(1, scan.quarantined.size)
        assertFalse(garbage.exists())
        assertArrayEquals(byteArrayOf(9, 9, 9, 9, 9), scan.quarantined.single().readBytes())
        assertEquals(quarantine, scan.quarantined.single().parentFile)
        assertTrue(foreign.exists())
        assertTrue(scan.unclean.isEmpty())
    }

    @Test
    fun `a journal from a newer format is read-only and never deleted`() {
        val file = store.fileFor(key)
        file.parentFile.mkdirs()
        val future = futureJournal()
        file.writeBytes(future)

        assertEquals(listOf(UnsupportedJournal(file, FUTURE_VERSION)), store.scan().unsupported)
        assertEquals(ReplayResult.Unsupported(FUTURE_VERSION), store.replay(key, "plan", base))
        assertEquals(CleanResult.KeptUnsupported(FUTURE_VERSION), store.markClean(key))
        assertEquals(OpenResult.Unsupported(FUTURE_VERSION), store.start(key, base))
        assertEquals(OpenResult.Unsupported(FUTURE_VERSION), store.resume(key, base, "plan"))
        assertArrayEquals(future, file.readBytes())
    }

    @Test
    fun `markClean deletes a sound journal`() {
        test.start(key, base).apply { append(TextEdit(0, "", "saved ")) }.close()

        assertEquals(CleanResult.Deleted, store.markClean(key))
        assertFalse(store.fileFor(key).exists())
        assertEquals(CleanResult.NothingToClean, store.markClean(key))
        assertEquals(ReplayResult.Missing, store.replay(key, "plan", base))
        assertTrue(store.scan().unclean.isEmpty())
    }

    @Test
    fun `markClean quarantines a journal with a damaged record`() {
        test.start(key, base).apply { append(TextEdit(0, "", "edit")) }.close()
        val file = store.fileFor(key)
        val bytes = file.readBytes().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        file.writeBytes(bytes)

        val result = store.markClean(key) as CleanResult.Quarantined
        assertArrayEquals(bytes, result.file.readBytes())
        assertFalse(file.exists())
    }

    @Test
    fun `resume replaces a sound journal with a snapshot and carries on`() {
        test.start(key, base).apply { append(TextEdit(4, "", " A")) }.close()
        val recovered = (store.replay(key, "plan", base) as ReplayResult.Ok).text

        val writer = (store.resume(key, base, recovered) as OpenResult.Opened).writer
        writer.append(TextEdit(recovered.length, "", " B"))
        writer.close()

        assertEquals("plan A B", (store.replay(key, "plan", base) as ReplayResult.Ok).text)
        assertFalse(quarantine.exists())
    }

    @Test
    fun `resume moves a damaged or foreign-base journal aside instead of overwriting it`() {
        test.start(key, fingerprintOf("other base")).close()
        val old = store.fileFor(key).readBytes()

        val writer = (store.resume(key, base, "plan, recovered") as OpenResult.Opened).writer
        writer.close()

        assertEquals("plan, recovered", (store.replay(key, "plan", base) as ReplayResult.Ok).text)
        assertArrayEquals(
            old,
            quarantine
                .listFiles()
                .orEmpty()
                .single()
                .readBytes(),
        )
    }

    @Test
    fun `a leftover from an interrupted start is kept aside, not deleted`() {
        val atomic = AtomicFile(store.fileFor(key))
        atomic.startWrite().use { it.write(byteArrayOf(1, 2, 3)) }

        val scan = store.scan()
        assertEquals(1, scan.quarantined.size)
        assertFalse(atomic.newFile.exists())
        assertTrue(store.start(key, base) is OpenResult.Opened)
    }

    private fun futureJournal(): ByteArray =
        ByteBuffer
            .allocate(FUTURE_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(JournalFormat.MAGIC)
            .putShort(FUTURE_VERSION.toShort())
            .put(ByteArray(FUTURE_SIZE - JournalFormat.MAGIC.size - 2) { 7 })
            .array()

    private companion object {
        const val FUTURE_VERSION = 99
        const val FUTURE_SIZE = 64
    }
}
