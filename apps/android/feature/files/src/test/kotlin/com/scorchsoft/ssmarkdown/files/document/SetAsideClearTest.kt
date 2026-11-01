package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.files.fakes.FakeDocumentProvider
import com.scorchsoft.ssmarkdown.files.fakes.InterleavingDispatcher
import com.scorchsoft.ssmarkdown.files.fakes.StoreFixture
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/**
 * The quarantine's only way out (edge-cases "Journal disk-space
 * preallocation, and quarantine retention"): an export of every
 * set-aside journal, verified, then a clear of exactly the ones the user
 * confirmed. The app never deletes them on its own.
 */
class SetAsideClearTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val provider = FakeDocumentProvider()
    private val uri: DocumentUri = provider.put("content://docs/note.md", "alpha\n")
    private val root: File by lazy { tmp.newFolder() }

    /** A run of edits typed, flushed, then set aside after a restart: one quarantined journal. */
    private suspend fun setAside(typed: String) {
        val before = StoreFixture(root, provider, InterleavingDispatcher())
        val doc = (before.store.open(uri) as OpenOutcome.Opened).document
        doc.replace(doc.document.text.length, 0, typed)
        before.store.flushJournals()
        StoreFixture(root, provider, InterleavingDispatcher()).journals.setAside(uri.value)
    }

    @Test
    fun `export holds the rebuilt text and the raw journals, then the clear removes only what was confirmed`() =
        runTest {
            setAside("first run")
            val store = StoreFixture(root, provider, InterleavingDispatcher()).store
            val confirmed = store.setAsideEdits()
            assertEquals(1, confirmed.size)
            setAside("set aside after the user looked") // Not in the confirmation: must survive.

            val zip = File(tmp.newFolder(), "set-aside.zip")
            assertTrue(store.exportSetAsideEdits(confirmed, zip))
            ZipFile(zip).use { written ->
                val names = written.entries().toList().map { it.name }
                val text = names.single { it.endsWith(".md") }
                assertEquals(
                    "alpha\nfirst run",
                    written.getInputStream(written.getEntry(text)).use { String(it.readBytes()) },
                )
                assertTrue(names.any { it.startsWith("journals/") })
            }

            assertEquals(1, store.clearSetAsideEdits(confirmed))

            val left = store.setAsideEdits()
            assertEquals(1, left.size)
            assertFalse(confirmed.single().file.exists())
        }

    @Test
    fun `nothing is set aside or cleared by the app on its own, however large`() =
        runTest {
            setAside("x".repeat(64 * 1024))
            val store = StoreFixture(root, provider, InterleavingDispatcher()).store
            store.scanForRecovery()
            assertEquals(1, store.setAsideEdits().size)
        }
}
