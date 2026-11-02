package com.scorchsoft.ssmarkdown.files.save

import com.scorchsoft.ssmarkdown.files.fakes.FakeDocumentProvider
import com.scorchsoft.ssmarkdown.files.provider.ProviderResult
import com.scorchsoft.ssmarkdown.history.journal.BaseFingerprint
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextFileFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A slow provider ends as [SaveOutcome.TimedOut], and the file is never
 * left part-written: either nothing was written, or the backup was put
 * back and verified (docs/edge-cases.md, "Slow cloud provider").
 */
class SaveTimeoutTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val provider = FakeDocumentProvider()
    private val uri = provider.put("content://drive/note.md", OLD)
    private val baseCopies by lazy { BaseCopyStore(tmp.newFolder("base")) }
    private var now = 0L
    private val timeout = SaveTimeout(millis = BUDGET, clock = { now })

    private fun request() =
        SaveRequest(
            uri,
            PieceTree.of(NEW).snapshot(),
            TextFileFormat.DEFAULT,
            BaseFingerprint.of(provider.bytes(uri), provider.lastModified(uri)),
            readOnly = false,
        )

    @Test
    fun `a save inside its budget is saved`() {
        provider.onWriteByte = { now += 1 }
        val outcome = SavePipeline(provider, baseCopies, SaveTimeout(millis = 10_000, clock = { now })).save(request())
        assertTrue(outcome is SaveOutcome.Saved)
        assertFalse(outcome.isTimedOut)
        assertEquals(NEW, provider.text(uri))
    }

    @Test
    fun `an upload that runs out of time stops, and the backup is written back`() {
        // Every byte costs a millisecond, the restore's too: a restore is never cut short.
        provider.onWriteByte = { now += 1 }
        val outcome = SavePipeline(provider, baseCopies, timeout).save(request())

        assertEquals(SaveOutcome.TimedOut(FileIntegrity.Restored), outcome)
        assertTrue(outcome.isTimedOut)
        assertEquals((outcome as SaveOutcome.ProviderError).failure, ProviderResult.TimedOut)
        assertEquals(OLD, provider.text(uri))
    }

    @Test
    fun `a slow precondition read times out before anything is written`() {
        provider.onOpenInput = { now += BUDGET * 2 }
        val outcome = SavePipeline(provider, baseCopies, timeout).save(request())

        assertEquals(SaveOutcome.TimedOut(FileIntegrity.Untouched), outcome)
        assertEquals(0, provider.writesOpened)
        assertEquals(OLD, provider.text(uri))
    }

    @Test
    fun `a timed-out save can be retried and then succeeds`() {
        provider.onWriteByte = { now += 1 }
        val pipeline = SavePipeline(provider, baseCopies, timeout)
        assertTrue(pipeline.save(request()).isTimedOut)
        assertEquals(OLD, provider.text(uri))

        provider.onWriteByte = null
        assertTrue(pipeline.save(request()) is SaveOutcome.Saved)
        assertEquals(NEW, provider.text(uri))
    }

    @Test
    fun `no timeout never gives up`() {
        provider.onWriteByte = { now += 1_000_000 }
        assertTrue(SavePipeline(provider, baseCopies, SaveTimeout.NONE).save(request()) is SaveOutcome.Saved)
    }

    private companion object {
        const val BUDGET = 10L
        const val OLD = "the old version, as last saved\n"
        val NEW = "the new version, much longer than the budget allows to upload in time\n".repeat(4)
    }
}
