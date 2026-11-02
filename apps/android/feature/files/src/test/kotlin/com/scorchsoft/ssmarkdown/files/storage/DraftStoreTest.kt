package com.scorchsoft.ssmarkdown.files.storage

import com.scorchsoft.ssmarkdown.files.document.OpenOutcome
import com.scorchsoft.ssmarkdown.files.drafts.DraftStore
import com.scorchsoft.ssmarkdown.files.fakes.FakeDocumentProvider
import com.scorchsoft.ssmarkdown.files.fakes.StoreFixture
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.provider.FileDocumentProvider
import com.scorchsoft.ssmarkdown.files.provider.RoutingDocumentProvider
import com.scorchsoft.ssmarkdown.files.save.SaveOutcome
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DraftStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a draft goes through the same pipeline and lists with its first line and age`() =
        runTest {
            var now = 10_000L
            var id = 0
            val drafts = DraftStore(tmp.newFolder("drafts"), nowMillis = { now }, newId = { "draft-${id++}" })
            val provider = RoutingDocumentProvider(FileDocumentProvider(drafts::resolve), FakeDocumentProvider())
            val store = StoreFixture(tmp.newFolder("app"), provider, UnconfinedTestDispatcher(testScheduler)).store
            val uri = drafts.create() ?: error("no draft")

            val doc = (store.open(uri) as OpenOutcome.Opened).document
            doc.replace(0, 0, "\n## Shopping list\n- milk\n")
            assertTrue(store.save(uri) is SaveOutcome.Saved)

            now = drafts.resolve(uri)?.lastModified()?.plus(5_000) ?: error("missing")
            val listed = drafts.list().single()
            assertEquals(uri, listed.uri)
            assertEquals("Shopping list", listed.title)
            assertEquals(5_000L, listed.ageMillis)
        }

    @Test
    fun `draft addresses cannot escape the drafts folder`() {
        val drafts = DraftStore(tmp.newFolder("drafts"), nowMillis = { 0 })
        assertNull(drafts.resolve(DocumentUri("ssmd-draft:../../etc/passwd")))
        assertNull(drafts.resolve(DocumentUri("content://x/y")))
    }

    @Test
    fun `a trashed draft leaves the list and comes back on restore, once`() {
        var id = 0
        val drafts = DraftStore(tmp.newFolder("trash-drafts"), nowMillis = { 0L }, newId = { "d-${id++}" })
        val first = drafts.create() ?: error("no draft")
        val second = drafts.create() ?: error("no draft")
        drafts.resolve(first)?.writeText("# Keep me")

        assertTrue(drafts.trash(first))
        assertEquals(listOf(second), drafts.list().map { it.uri })
        assertTrue(drafts.restore(first))
        assertEquals("# Keep me", drafts.resolve(first)?.readText())
        assertEquals(setOf(first, second), drafts.list().map { it.uri }.toSet())

        // Only the newest deletion is undoable; trashing another empties the older one.
        assertTrue(drafts.trash(first))
        assertTrue(drafts.trash(second))
        assertTrue(!drafts.restore(first))
        assertTrue(drafts.restore(second))
    }
}
