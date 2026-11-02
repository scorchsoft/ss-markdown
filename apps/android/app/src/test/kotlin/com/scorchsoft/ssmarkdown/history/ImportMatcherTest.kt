package com.scorchsoft.ssmarkdown.history

import com.scorchsoft.ssmarkdown.history.versions.ExportedDocument
import org.junit.Assert.assertEquals
import org.junit.Test

/** History import attaches by content, then by a unique name, and otherwise asks. */
class ImportMatcherTest {
    private fun doc(
        name: String,
        sha: String?,
    ) = ExportedDocument("content://x/$name", name, 3, 1, 0, 0, sha)

    private val notes = doc("notes.md", "aa")
    private val plan = doc("plan.md", "bb")

    @Test
    fun `content wins even when the file was renamed`() {
        assertEquals(ImportMatch.Matched(plan), ImportMatcher.match(listOf(notes, plan), "renamed.md", "BB"))
    }

    @Test
    fun `a unique name matches when no content does`() {
        assertEquals(ImportMatch.Matched(notes), ImportMatcher.match(listOf(notes, plan), "Notes.md", "zz"))
    }

    @Test
    fun `no match, or two, asks instead of guessing`() {
        val twin = doc("notes.md", "cc")
        assertEquals(ImportMatch.Ask(listOf(notes, plan)), ImportMatcher.match(listOf(notes, plan), "x.md", "zz"))
        assertEquals(ImportMatch.Ask(listOf(notes, twin)), ImportMatcher.match(listOf(notes, twin), "notes.md", "zz"))
    }

    @Test
    fun `an empty export is empty`() {
        assertEquals(ImportMatch.Empty, ImportMatcher.match(emptyList(), "a.md", null))
    }

    @Test
    fun `the fingerprint is SHA-256 of the UTF-8 text`() {
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            ImportMatcher.sha256Hex("hello"),
        )
    }
}
