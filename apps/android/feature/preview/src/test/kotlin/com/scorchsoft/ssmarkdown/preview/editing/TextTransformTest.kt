package com.scorchsoft.ssmarkdown.preview.editing

import com.scorchsoft.ssmarkdown.preview.bridge.TextEdit
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class TextTransformTest {
    @Test
    fun `the host's insert goes first at a shared offset`() {
        val host = listOf(TextEdit(1, 1, "H"))
        val page = listOf(TextEdit(1, 1, "P"))
        val t = TextTransform.transform(host, page)
        assertEquals("aHPb", TextTransform.apply(TextTransform.apply("ab", host), t.page))
        assertEquals("aHPb", TextTransform.apply(TextTransform.apply("ab", page), t.host))
    }

    @Test
    fun `matches the page's transform on a shared vector`() {
        // The same case web/test/unit/ot.test.ts checks: typing inside text the host deleted survives.
        val host = listOf(TextEdit(2, 10, ""))
        val page = listOf(TextEdit(5, 5, "typed"))
        val t = TextTransform.transform(host, page)
        assertEquals(listOf(TextEdit(2, 2, "typed")), t.page)
        assertEquals("01typedAB", TextTransform.apply(TextTransform.apply("0123456789AB", host), t.page))
    }

    @Test
    fun `property - both orders converge and inserted text is never lost`() {
        val rnd = Random(2024)
        repeat(20_000) {
            val base = randomText(rnd, rnd.nextInt(16))
            val host = randomEdits(rnd, base.length, "XYZ")
            val page = randomEdits(rnd, base.length, "123")
            val t = TextTransform.transform(host, page)
            val viaHost = TextTransform.apply(TextTransform.apply(base, host), t.page)
            val viaPage = TextTransform.apply(TextTransform.apply(base, page), t.host)
            assertEquals("$base $host $page", viaHost, viaPage)
            val typed = (host + page).joinToString("") { it.insert }
            for (c in "XYZ123") assertEquals("$base $host $page", typed.count { it == c }, viaHost.count { it == c })
        }
    }

    @Test
    fun `maps offsets over edits`() {
        val edits = listOf(TextEdit(0, 0, "ab"), TextEdit(4, 8, "x"))
        assertEquals(4, TextTransform.mapOffset(2, edits))
        assertEquals(7, TextTransform.mapOffset(6, edits))
        assertEquals(8, TextTransform.mapOffset(9, edits))
        assertEquals(0, TextTransform.mapOffset(0, edits, after = false))
    }

    companion object {
        fun randomText(
            rnd: Random,
            length: Int,
        ): String = String(CharArray(length) { "abcdef\n"[rnd.nextInt(7)] })

        fun randomEdits(
            rnd: Random,
            length: Int,
            alphabet: String,
            deletes: Boolean = true,
        ): List<TextEdit> {
            val out = ArrayList<TextEdit>()
            var at = 0
            repeat(rnd.nextInt(4)) {
                if (at > length) return out
                val from = at + rnd.nextInt(length - at + 1)
                val to = if (!deletes || rnd.nextInt(3) == 0) from else minOf(length, from + rnd.nextInt(5))
                val insert = String(CharArray(rnd.nextInt(4)) { alphabet[rnd.nextInt(alphabet.length)] })
                if (from != to || insert.isNotEmpty()) {
                    out += TextEdit(from, to, insert)
                    at = to + 1
                }
            }
            return out
        }
    }
}
