package com.scorchsoft.ssmarkdown.files.storage

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageAccountantTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `measures each category and keeps only the preview cache in cacheDir`() =
        runTest {
            val files = tmp.newFolder("files")
            val noBackup = tmp.newFolder("no_backup")
            val cache = tmp.newFolder("cache")
            val layout = StorageLayout(files, noBackup, cache)
            write(File(layout.history, "a/pack"), 100)
            write(File(layout.history, "b/pack"), 50)
            write(File(layout.drafts, "x.md"), 7)
            write(File(layout.journals, "j.ssmj"), 30)
            write(File(layout.baseCopies, "c.base"), 20)
            write(File(layout.previewCache, "svg"), 9)
            val accountant =
                StorageAccountant(layout, UnconfinedTestDispatcher(testScheduler), { 42L }, appBytes = { 1000 })

            val breakdown = accountant.measure()

            assertEquals(CategoryUsage(150, 2), breakdown.usage[StorageCategory.VersionHistory])
            assertEquals(CategoryUsage(7, 1), breakdown.usage[StorageCategory.Drafts])
            assertEquals(CategoryUsage(50, 2), breakdown.usage[StorageCategory.RecoveryCopies])
            assertEquals(CategoryUsage(9, 1), breakdown.usage[StorageCategory.PreviewCache])
            assertEquals(CategoryUsage(0, 0), breakdown.usage[StorageCategory.Fonts])
            assertEquals(150L + 7 + 50 + 9 + 1000, breakdown.totalBytes)
            assertEquals(breakdown, accountant.lastKnown)
            // Android's "Clear cache" only ever reaches the preview cache.
            assertTrue(layout.previewCache.startsWith(cache))
            listOf(layout.history, layout.drafts, layout.fonts, layout.journals, layout.baseCopies)
                .forEach { assertFalse(it.startsWith(cache)) }
        }

    private fun write(
        file: File,
        bytes: Int,
    ) {
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(bytes))
    }
}
