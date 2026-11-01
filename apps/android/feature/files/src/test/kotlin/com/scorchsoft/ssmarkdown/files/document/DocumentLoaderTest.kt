package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.files.fakes.FakeDocumentProvider
import com.scorchsoft.ssmarkdown.files.fakes.FakeMemorySource
import com.scorchsoft.ssmarkdown.files.fakes.MB
import com.scorchsoft.ssmarkdown.files.memory.Budget
import com.scorchsoft.ssmarkdown.files.memory.MemoryGovernor
import com.scorchsoft.ssmarkdown.files.provider.FileMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Out-of-memory protection when the provider does not say how big a file is (performance review PERF-I). */
class DocumentLoaderTest {
    // 200 MB free: a 12 MB file costs ~86 MB, past the 80 MB normal line, so it opens lean.
    private val memory = FakeMemorySource(maxHeap = 256 * MB, usedHeap = 56 * MB, memoryClassMegabytes = 256)
    private val provider = FakeDocumentProvider()
    private val loader = DocumentLoader(provider, MemoryGovernor(memory))
    private val uri = provider.put("content://docs/big.md", "x".repeat(12 * MB.toInt()))

    @Test
    fun `a file of unknown size is budgeted by the bytes that arrived`() {
        provider.reportedSize = FileMetadata.UNKNOWN_SIZE
        val loaded = loader.load(uri) as LoadResult.Loaded
        // Before: an unknown size was admitted as 0 bytes, so 12 MB opened with every cache on.
        assertEquals(Budget.Lean, loaded.budget)
    }

    @Test
    fun `a provider that under-reports is budgeted by the bytes too`() {
        provider.reportedSize = 10
        assertEquals(Budget.Lean, (loader.load(uri) as LoadResult.Loaded).budget)
    }

    @Test
    fun `a known small file keeps the budget admission gave it`() {
        val small = provider.put("content://docs/small.md", "# Hi\n")
        assertEquals(Budget.Normal, (loader.load(small) as LoadResult.Loaded).budget)
    }

    @Test
    fun `running out of memory while decoding declines the file instead of crashing`() {
        provider.failReadWith = OutOfMemoryError("Failed to allocate")
        val result = loader.load(uri)
        // Before: providerCall caught Exception only, so the error killed the I/O thread and the app.
        assertTrue("$result", result is LoadResult.Declined)
    }
}
