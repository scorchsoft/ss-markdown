package com.scorchsoft.ssmarkdown.history.versions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

/** Re-opening an unchanged file never builds or hashes its text (PERF-14). */
class RecordOpenedTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val doc = DocumentKey("content://docs/a.md")

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `an unchanged file is noted without building its text`() {
        val test = TestHistory(folder.root)
        test.record(doc, "one\ntwo\n", VersionKind.Opened)
        var built = 0

        val result = test.store.recordOpened(doc, sha("one\ntwo\n".toByteArray())) { "x".also { built++ } }

        assertTrue(result is RecordResult.Unchanged)
        assertEquals(0, built)
        assertEquals(1, test.versions(doc).size)
    }

    @Test
    fun `a changed file, or one with CRLF bytes, is recorded the ordinary way`() {
        val test = TestHistory(folder.root)
        test.record(doc, "one\n", VersionKind.Opened)

        val changed = test.store.recordOpened(doc, sha("one\ntwo\n".toByteArray())) { "one\ntwo\n" }
        val crlf = test.store.recordOpened(doc, sha("one\r\ntwo\r\n".toByteArray())) { "one\ntwo\n" }

        assertTrue(changed is RecordResult.Recorded)
        assertTrue(crlf is RecordResult.Unchanged) // Same text as the head, found by the ordinary path.
        assertEquals(2, test.versions(doc).size)
    }
}
