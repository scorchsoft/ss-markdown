package com.scorchsoft.ssmarkdown.files.save

import com.scorchsoft.ssmarkdown.files.fakes.FakeDocumentProvider
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.history.journal.BaseFingerprint
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextFileFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Every [SaveOutcome], proven against the fault-injecting provider, and
 * after every failure the file is the old version, byte for byte.
 */
class SavePipelineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val provider = FakeDocumentProvider()
    private val uri = provider.put("content://docs/note.md", OLD)
    private val baseCopies by lazy { BaseCopyStore(tmp.newFolder("base")) }
    private val pipeline by lazy { SavePipeline(provider, baseCopies) }

    private fun base(): BaseFingerprint = BaseFingerprint.of(provider.bytes(uri), provider.lastModified(uri))

    private fun request(
        text: String = NEW,
        base: BaseFingerprint = base(),
        readOnly: Boolean = false,
    ) = SaveRequest(uri, PieceTree.of(text).snapshot(), TextFileFormat.DEFAULT, base, readOnly)

    private fun assertFileIsOld() = assertEquals(OLD, provider.text(uri))

    @Test
    fun `a clean save writes, verifies and refreshes the backup`() {
        val outcome = pipeline.save(request())

        assertTrue(outcome is SaveOutcome.Saved)
        assertEquals(NEW, provider.text(uri))
        val saved = (outcome as SaveOutcome.Saved).fingerprint
        assertTrue(saved.matches(BaseFingerprint.of(NEW.toByteArray(), 0)))
        assertEquals(provider.lastModified(uri), saved.lastModified)
        assertTrue(baseCopies.matches(uri, saved))
    }

    @Test
    fun `an outside change of a different size is a conflict and nothing is written`() {
        val base = base()
        provider.externalWrite(uri, "theirs, longer than before\n".toByteArray())

        val outcome = pipeline.save(request(base = base))

        assertTrue(outcome is SaveOutcome.Conflict)
        assertEquals("theirs, longer than before\n", provider.text(uri))
        assertEquals(0, provider.writesOpened)
    }

    @Test
    fun `a same-size change in the same second is still a conflict`() {
        val base = base()
        val same = OLD.replace('a', 'b')
        assertEquals(OLD.length, same.length)
        provider.externalWrite(uri, same.toByteArray(), lastModified = base.lastModified)

        val outcome = pipeline.save(request(base = base))

        assertTrue(outcome is SaveOutcome.Conflict)
        assertEquals(same, provider.text(uri))
    }

    @Test
    fun `a provider that reports lastModified 0 is still checked by hash`() {
        provider.fixedLastModified = 0
        val base = base()
        provider.externalWrite(uri, OLD.uppercase().toByteArray())

        assertTrue(pipeline.save(request(base = base)) is SaveOutcome.Conflict)
    }

    @Test
    fun `a revoked grant is PermissionLost and the file is untouched`() {
        val base = base()
        provider.revoked += uri

        assertEquals(SaveOutcome.PermissionLost(FileIntegrity.Untouched), pipeline.save(request(base = base)))
        provider.revoked.clear()
        assertFileIsOld()
    }

    @Test
    fun `a full disk part way is StorageFull and the old file is put back`() {
        val base = base()
        provider.storageFullAfter = 5

        assertEquals(SaveOutcome.StorageFull(FileIntegrity.Restored), pipeline.save(request(base = base)))
        assertFileIsOld()
    }

    @Test
    fun `a write that fails part way is a ProviderError and the old file is put back`() {
        val base = base()
        provider.failNextWriteAfter = 7

        val outcome = pipeline.save(request(base = base))

        assertTrue(outcome is SaveOutcome.ProviderError)
        assertEquals(FileIntegrity.Restored, (outcome as SaveOutcome.ProviderError).file)
        assertFileIsOld()
    }

    @Test
    fun `an upload that fails on close is reported and the old file is put back`() {
        val base = base()
        provider.failNextClose = true

        val outcome = pipeline.save(request(base = base))

        assertTrue(outcome is SaveOutcome.ProviderError)
        assertFileIsOld()
    }

    @Test
    fun `a provider that stores the wrong bytes fails verification and is repaired`() {
        val base = base()
        provider.corruptNextWrite = true

        assertEquals(SaveOutcome.VerifyFailed(FileIntegrity.Restored), pipeline.save(request(base = base)))
        assertFileIsOld()
    }

    @Test
    fun `a provider that rejects wt is written with rwt and an explicit truncate`() {
        provider.rejectTruncateMode = true

        assertTrue(pipeline.save(request(text = "short\n")) is SaveOutcome.Saved)
        assertEquals("short\n", provider.text(uri))
    }

    @Test
    fun `a provider that ignores truncation leaves no stale tail`() {
        provider.ignoreTruncation = true

        assertTrue(pipeline.save(request(text = "short\n")) is SaveOutcome.Saved)
        assertEquals("short\n", provider.text(uri))
    }

    @Test
    fun `ignoring truncation with no way to truncate keeps the old file`() {
        provider.ignoreTruncation = true
        provider.truncateUnsupported = true

        val outcome = pipeline.save(request(text = "short\n"))

        assertTrue(outcome is SaveOutcome.ProviderError)
        assertFileIsOld()
    }

    @Test
    fun `a read-only document is never written`() {
        assertEquals(SaveOutcome.ReadOnly, pipeline.save(request(readOnly = true)))
        assertEquals(0, provider.writesOpened)
    }

    @Test
    fun `a deleted file is Missing`() {
        val base = base()
        provider.delete(uri)

        assertEquals(SaveOutcome.Missing(FileIntegrity.Untouched), pipeline.save(request(base = base)))
    }

    @Test
    fun `no backup means no write`() {
        val blocked = tmp.newFile("not-a-directory")
        val outcome = SavePipeline(provider, BaseCopyStore(blocked)).save(request())

        assertEquals(SaveOutcome.BackupFailed(storageFull = false), outcome)
        assertEquals(0, provider.writesOpened)
        assertFileIsOld()
    }

    @Test
    fun `the backup is taken before the first write`() {
        val base = base()
        provider.failNextWriteAfter = 0

        pipeline.save(request(base = base))

        assertTrue(baseCopies.matches(uri, base))
        assertArrayEquals(OLD.toByteArray(), baseCopies.fileFor(uri).readBytes())
    }

    @Test
    fun `under random one-shot faults the file is always the old version or the new one`() {
        val random = java.util.Random(SEED)
        repeat(ROUNDS) { round ->
            val before = provider.text(uri)
            val next = "round $round " + "x".repeat(random.nextInt(LONGEST)) + "\n"
            injectRandomFault(random)
            val outcome = pipeline.save(request(text = next, base = base()))
            clearFaults()
            val now = provider.text(uri)
            if (outcome is SaveOutcome.Saved) {
                assertEquals(next, now)
            } else {
                assertEquals("round $round left a partial file after $outcome", before, now)
            }
        }
    }

    private fun injectRandomFault(random: java.util.Random) {
        when (random.nextInt(FAULT_KINDS)) {
            0 -> provider.failNextWriteAfter = random.nextInt(LONGEST)
            1 -> provider.storageFullAfter = random.nextInt(LONGEST)
            2 -> provider.corruptNextWrite = true
            3 -> provider.failNextClose = true
            4 -> provider.ignoreTruncation = true
            5 -> provider.rejectTruncateMode = true
            else -> Unit
        }
    }

    private fun clearFaults() {
        provider.failNextWriteAfter = null
        provider.storageFullAfter = null
        provider.corruptNextWrite = false
        provider.failNextClose = false
        provider.ignoreTruncation = false
        provider.rejectTruncateMode = false
    }

    private companion object {
        const val OLD = "# Title\n\nalpha beta gamma\n"
        const val NEW = "# Title\n\nalpha beta gamma delta\nmore\n"
        const val SEED = 20261101L
        const val ROUNDS = 300
        const val LONGEST = 60
        const val FAULT_KINDS = 8
    }
}
