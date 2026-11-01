package com.scorchsoft.ssmarkdown.preview.bridge

import com.scorchsoft.ssmarkdown.preview.PreviewListener
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PageMessageDeliveryTest {
    private class Recorder : PreviewListener {
        val edits = mutableListOf<Pair<Int, List<TextEdit>>>()
        val links = mutableListOf<LinkTarget>()
        var exported: ByteArray? = null

        override fun onSourceEdits(
            baseRev: Int,
            edits: List<TextEdit>,
        ) {
            this.edits += baseRev to edits
        }

        override fun onLinkTapped(
            target: LinkTarget,
            line: Int,
        ) {
            links += target
        }

        override fun onExportResult(
            requestId: Int,
            mime: String,
            bytes: ByteArray,
        ) {
            exported = bytes
        }
    }

    @Test
    fun `a task toggle is a one-character source edit`() {
        val r = Recorder()
        PageMessage.TaskToggle(baseRev = 5, offset = 3, checked = true).deliverTo(r)
        PageMessage.TaskToggle(baseRev = 6, offset = 3, checked = false).deliverTo(r)
        assertEquals(listOf(5 to listOf(TextEdit(3, 4, "x")), 6 to listOf(TextEdit(3, 4, " "))), r.edits)
    }

    @Test
    fun `links are classified on the Kotlin side too`() {
        val r = Recorder()
        listOf("https://example.com", "javascript:alert(1)", "content://x/y", "notes/other.md")
            .forEach { PageMessage.LinkTapped(it, 0).deliverTo(r) }
        assertEquals(
            listOf(
                LinkTarget.External("https://example.com"),
                LinkTarget.Blocked,
                LinkTarget.Blocked,
                LinkTarget.Relative("notes/other.md"),
            ),
            r.links,
        )
    }

    @Test
    fun `png exports arrive as bytes and svg as utf-8 text`() {
        val r = Recorder()
        PageMessage.ExportResult(1, "image/png", "iVBORw==").deliverTo(r)
        assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), r.exported)
        PageMessage.ExportResult(2, "image/svg+xml", "<svg/>").deliverTo(r)
        assertArrayEquals("<svg/>".toByteArray(), r.exported)
    }
}
