package com.scorchsoft.ssmarkdown.history.journal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class Wtf8Test {
    @Test
    fun `valid text encodes exactly as UTF-8`() {
        val text = "plain # Title\r\nemoji 😀 family 👩‍💻 CJK 中文 accents é é max 􏿿"
        assertArrayEquals(text.toByteArray(Charsets.UTF_8), Wtf8.encode(text))
        assertEquals(text, Wtf8.decode(Wtf8.encode(text)))
    }

    @Test
    fun `lone surrogates survive the round trip`() {
        for (text in listOf("\uD83D", "\uDE00", "a\uD83Db", "\uDE00\uD83D", "😀\uD83D")) {
            assertEquals(text, Wtf8.decode(Wtf8.encode(text)))
        }
    }

    @Test
    fun `any sequence of UTF-16 code units round trips`() {
        val random = Random(SEED)
        repeat(CASES) {
            val text =
                String(CharArray(random.nextInt(MAX_LENGTH)) { random.nextInt(Char.MAX_VALUE.code + 1).toChar() })
            val bytes = Wtf8.encode(text)
            assertEquals(bytes.size, Wtf8.encodedLength(text))
            assertEquals(text, Wtf8.decode(bytes))
        }
    }

    @Test
    fun `malformed bytes decode to null rather than guessing`() {
        val malformed =
            listOf(
                byteArrayOf(0xC3.toByte()), // truncated two-byte sequence
                byteArrayOf(0xC0.toByte(), 0x80.toByte()), // overlong NUL
                byteArrayOf(0xE2.toByte(), 0x28, 0xA1.toByte()), // bad continuation
                byteArrayOf(0xF8.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte()), // no such lead byte
                byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()), // past U+10FFFF
            )
        malformed.forEach { assertNull(Wtf8.decode(it)) }
    }

    private companion object {
        const val SEED = 8
        const val CASES = 500
        const val MAX_LENGTH = 64
    }
}
