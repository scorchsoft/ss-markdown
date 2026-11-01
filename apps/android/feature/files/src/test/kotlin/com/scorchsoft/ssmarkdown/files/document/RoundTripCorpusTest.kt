package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.files.fakes.FakeDocumentProvider
import com.scorchsoft.ssmarkdown.files.fakes.InterleavingDispatcher
import com.scorchsoft.ssmarkdown.files.fakes.StoreFixture
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.save.SaveOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

/**
 * The round-trip corpus of docs/robustness.md Part 3 and §4, end to end:
 * open through the store, edit, save through the safe pipeline, and
 * compare the provider's bytes.
 *
 * - Open → edit one line → save: every byte outside that line's content
 *   is identical (BOM, every line ending, the final newline or its absence).
 * - Open → save with no net edit (a character typed and deleted, so the
 *   save really writes): byte-identical.
 * - NUL bytes and invalid UTF-8 open read-only and are never written.
 */
class RoundTripCorpusTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Case(
        val name: String,
        val bytes: ByteArray,
    )

    private val corpus: List<Case> by lazy {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        fun utf8(text: String) = text.toByteArray(Charsets.UTF_8)
        listOf(
            Case("LF, final newline", utf8("# Title\n\nfirst line\nsecond line\n")),
            Case("LF, no final newline", utf8("# Title\n\nfirst line\nsecond line")),
            Case("UTF-8 BOM, LF", bom + utf8("# Title\n\nfirst line\nsecond line\n")),
            Case("CRLF, final newline", utf8("# Title\r\n\r\nfirst line\r\nsecond line\r\n")),
            Case("CRLF, no final newline", utf8("# Title\r\n\r\nfirst line\r\nsecond line")),
            Case("UTF-8 BOM, CRLF", bom + utf8("# Title\r\n\r\nfirst line\r\nsecond line\r\n")),
            Case("CR only", utf8("# Title\r\rfirst line\rsecond line\r")),
            Case("mixed endings", utf8("# Title\r\n\nfirst line\rsecond line\r\nthird\nlast")),
            Case("only line breaks", utf8("\n\r\n\n\r\n")),
            Case("trailing whitespace and tabs", utf8("keep  \n\tindented\t\nnbsp end \n")),
            Case(
                "emoji, flags, ZWJ, combining marks, CJK, RTL",
                utf8("😀 smile\n🇬🇧🇯🇵 flags\n👩‍👩‍👧 family\né combining\n漢字かな\nשלום עולם\n𝄞 clef\n"),
            ),
            Case("a 300 000-character line", utf8("x".repeat(300_000) + "\nshort line\n" + "y".repeat(70_000))),
            Case("a long line of 4-byte characters", utf8("😀".repeat(40_000) + "\r\nend\r\n")),
            Case("empty file", ByteArray(0)),
        )
    }

    private suspend fun open(
        bytes: ByteArray,
        name: String,
    ): Triple<StoreFixture, DocumentUri, OpenDocument> {
        val provider = FakeDocumentProvider()
        val uri = DocumentUri("content://docs/${name.hashCode()}.md")
        provider.put(uri, bytes)
        val fixture = StoreFixture(tmp.newFolder(), provider, InterleavingDispatcher())
        val doc = (fixture.store.open(uri) as OpenOutcome.Opened).document
        return Triple(fixture, uri, doc)
    }

    @Test
    fun `open then save with no net edit is byte-identical`() =
        runTest {
            for (case in corpus) {
                val (fixture, uri, doc) = open(case.bytes, case.name)
                doc.replace(0, 0, "x")
                doc.replace(0, 1, "")
                assertTrue(case.name, doc.isDirty) // So the save really writes.
                assertTrue(case.name, fixture.store.save(uri) is SaveOutcome.Saved)
                assertArrayEquals(case.name, case.bytes, (fixture.provider as FakeDocumentProvider).bytes(uri))
            }
        }

    @Test
    fun `editing one line changes only that line's bytes`() =
        runTest {
            for (case in corpus.filter { it.bytes.isNotEmpty() }) {
                val (fixture, uri, doc) = open(case.bytes, case.name)
                val text = doc.document.text
                // The second line if there is one (the first after a BOM is covered by the BOM cases).
                val line = if (text.lineCount > 1) 1 else 0
                doc.replace(text.lineStartOffset(line), text.lineLength(line), EDIT)
                assertTrue(case.name, fixture.store.save(uri) is SaveOutcome.Saved)

                val saved = (fixture.provider as FakeDocumentProvider).bytes(uri)
                assertArrayEquals(case.name, expectedAfterEdit(case.bytes, line), saved)
            }
        }

    @Test
    fun `NUL bytes and invalid UTF-8 open read-only and are never written`() =
        runTest {
            val hostile =
                listOf(
                    Case("NUL", "text\u0000more\n".toByteArray()),
                    Case("lone continuation byte", byteArrayOf(0x61, 0x80.toByte(), 0x0A)),
                    Case("truncated sequence", byteArrayOf(0x61, 0xC3.toByte())),
                    Case("encoded surrogate", byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte())),
                    Case("overlong slash", byteArrayOf(0xC0.toByte(), 0xAF.toByte())),
                )
            for (case in hostile) {
                val (fixture, uri, doc) = open(case.bytes, case.name)
                val provider = fixture.provider as FakeDocumentProvider
                assertTrue(case.name, doc.readOnly != null)
                doc.replace(0, 0, "typed anyway")
                assertEquals(case.name, SaveOutcome.ReadOnly, fixture.store.save(uri))
                assertEquals(case.name, 0, provider.writesOpened)
                assertArrayEquals(case.name, case.bytes, provider.bytes(uri))
            }
        }

    /** [original] with line [line]'s content bytes (not its ending) replaced by [EDIT]. */
    private fun expectedAfterEdit(
        original: ByteArray,
        line: Int,
    ): ByteArray {
        val start = if (original.startsWith(BOM)) BOM.size else 0
        var lineStart = start
        repeat(line) { lineStart = nextLineStart(original, lineStart) }
        var contentEnd = lineStart
        while (contentEnd < original.size && original[contentEnd] != CR && original[contentEnd] != LF) contentEnd++
        return ByteArrayOutputStream()
            .apply {
                write(original, 0, lineStart)
                write(EDIT.toByteArray(Charsets.UTF_8))
                write(original, contentEnd, original.size - contentEnd)
            }.toByteArray()
    }

    private fun nextLineStart(
        bytes: ByteArray,
        from: Int,
    ): Int {
        var i = from
        while (i < bytes.size && bytes[i] != CR && bytes[i] != LF) i++
        val crlf = bytes.getOrNull(i) == CR && bytes.getOrNull(i + 1) == LF
        return if (crlf) i + 2 else (i + 1).coerceAtMost(bytes.size)
    }

    private fun ByteArray.startsWith(prefix: ByteArray) =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private companion object {
        const val EDIT = "EDITED ✓ 😀"
        const val CR: Byte = 0x0D
        const val LF: Byte = 0x0A
        val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}
