package com.scorchsoft.ssmarkdown.files.saf

import com.scorchsoft.ssmarkdown.files.fakes.FakeDocumentProvider
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.provider.ProviderResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafHelpersTest {
    @Test
    fun `suggested names come from the first heading, made safe`() {
        assertEquals("Trip plan.md", SuggestedFileName.forText("# Trip plan\n\ntext"))
        assertEquals("Q3 review.md", SuggestedFileName.forText("Intro\n\nQ3 review\n=========\n"))
        assertEquals("a b.md", SuggestedFileName.forText("## a/b ##\n"))
        assertEquals("Bold.md", SuggestedFileName.forText("# **Bold**"))
        assertEquals("Untitled.md", SuggestedFileName.forText("```\n# not a heading\n```\nplain"))
        assertEquals("Untitled.md", SuggestedFileName.forText(""))
        assertEquals(63, SuggestedFileName.forText("# " + "x".repeat(200)).length)
    }

    @Test
    fun `extensions match without regard to case`() {
        assertTrue(MarkdownFiles.hasMarkdownExtension("README.MD"))
        assertTrue(MarkdownFiles.hasMarkdownExtension("notes.Markdown"))
        assertTrue(MarkdownFiles.hasMarkdownExtension("a.mdx"))
        assertTrue(MarkdownFiles.hasMarkdownExtension("todo.txt"))
        assertFalse(MarkdownFiles.hasMarkdownExtension("photo.jpg"))
        assertFalse(MarkdownFiles.hasMarkdownExtension(".md"))
        assertTrue(MarkdownFiles.isTextMime("Text/Plain"))
    }

    @Test
    fun `VIEW and EDIT open in place, with write only when granted`() {
        val view = IncomingRequests.parse(IntentFields(IncomingRequests.ACTION_VIEW, "content://a/1", "text/markdown"))
        assertEquals(IncomingRequest.OpenInPlace(DocumentUri("content://a/1"), writable = false), view)
        val granted =
            IncomingRequests.parse(
                IntentFields(IncomingRequests.ACTION_VIEW, "content://a/1", null, grantsWrite = true),
            )
        assertEquals(IncomingRequest.OpenInPlace(DocumentUri("content://a/1"), writable = true), granted)
        val edit = IncomingRequests.parse(IntentFields(IncomingRequests.ACTION_EDIT, "content://a/1", null))
        assertEquals(IncomingRequest.OpenInPlace(DocumentUri("content://a/1"), writable = true), edit)
        assertEquals(
            IncomingRequest.Unsupported,
            IncomingRequests.parse(IntentFields(IncomingRequests.ACTION_VIEW, "https://x", null)),
        )
    }

    @Test
    fun `SEND prefers streams and falls back to text`() {
        val streams =
            IncomingRequests.parse(
                IntentFields(
                    IncomingRequests.ACTION_SEND,
                    null,
                    "text/plain",
                    streams = listOf("content://s/1"),
                    text = "t",
                ),
            )
        assertEquals(IncomingRequest.SharedStreams(listOf(DocumentUri("content://s/1"))), streams)
        val text =
            IncomingRequests.parse(
                IntentFields(IncomingRequests.ACTION_SEND, null, "text/plain", text = "hello", subject = "Hi"),
            )
        assertEquals(IncomingRequest.SharedText("hello", "Hi"), text)
        assertEquals(
            IncomingRequest.Unsupported,
            IncomingRequests.parse(IntentFields(IncomingRequests.ACTION_SEND, null, null)),
        )
    }

    @Test
    fun `shared streams are size-checked before buffering`() {
        val provider = FakeDocumentProvider()
        val small = provider.put("content://s/small", "hello\n")
        val big = provider.put("content://s/big", "x".repeat(100))
        val bad = DocumentUri("content://s/bad").also { provider.put(it, byteArrayOf(0xFF.toByte())) }
        val reader = SharedStreamReader(provider, limitBytes = 50)

        assertEquals(SharedContent.Text("hello\n", "small"), reader.read(small))
        assertEquals(SharedContent.TooLarge(100), reader.read(big))
        assertEquals(SharedContent.NotText, reader.read(bad))
        provider.revoked += small
        assertEquals(SharedContent.Failed(ProviderResult.PermissionDenied), reader.read(small))
    }

    @Test
    fun `the folder drawer lists folders first, Markdown only, with markers`() {
        val tree = DocumentUri("content://t/tree/root")
        val source =
            TreeSource { _, parent ->
                ProviderResult.Ok(
                    when (parent) {
                        "root" -> {
                            listOf(
                                entry("b.md"),
                                entry("photo.jpg"),
                                entry("A.MD"),
                                entry(".hidden.md"),
                                entry("sub", dir = true),
                            )
                        }

                        "sub" -> {
                            listOf(entry("inner.markdown"))
                        }

                        else -> {
                            emptyList()
                        }
                    },
                )
            }

        val rows = (FolderLister(source).list(tree, "root", store = null) as ProviderResult.Ok).value

        assertEquals(listOf("sub", "inner.markdown", "A.MD", "b.md"), rows.map { it.entry.name })
        assertEquals(listOf(0, 1, 0, 0), rows.map { it.depth })
        val all =
            (
                FolderLister(
                    source,
                ).list(tree, "root", store = null, showAllFiles = true) as ProviderResult.Ok
            ).value
        assertTrue(all.any { it.entry.name == "photo.jpg" })
    }

    @Test
    fun `collapsed folders count the unsaved files beneath them`() {
        val rows =
            listOf(
                FolderRow(entry("top", dir = true), 0, dirty = false, changedOutside = false),
                FolderRow(entry("mid", dir = true), 1, dirty = false, changedOutside = false),
                FolderRow(entry("a.md"), 2, dirty = true, changedOutside = false),
                FolderRow(entry("b.md"), 1, dirty = true, changedOutside = false),
                FolderRow(entry("c.md"), 0, dirty = true, changedOutside = false),
            )

        val counts = rows.unsavedCountsByFolder()

        assertEquals(2, counts[DocumentUri("content://t/top")])
        assertEquals(1, counts[DocumentUri("content://t/mid")])
    }

    private fun entry(
        name: String,
        dir: Boolean = false,
    ) = TreeEntry(
        DocumentUri("content://t/$name"),
        name,
        name,
        dir,
        10,
        1_000,
        if (dir) "vnd.android.document/directory" else null,
    )
}
