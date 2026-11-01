package com.scorchsoft.ssmarkdown.theme.json

/**
 * A strict RFC 8259 reader that records line numbers. Small on purpose:
 * theme files are a few hundred bytes, so clarity beats speed. Nesting is
 * capped so a hostile file cannot exhaust the stack.
 */
@Suppress("TooManyFunctions") // Recursive descent: one small function per grammar rule.
internal class JsonReader private constructor(
    private val text: String,
) {
    private var index = 0
    private var line = 1

    /** Internal unwinding only; [parse] turns it into [JsonParse.Failed]. */
    private class SyntaxError(
        val line: Int,
        override val message: String,
    ) : RuntimeException(message)

    private fun parseDocument(): JsonValue {
        val value = readValue(depth = 0)
        skipWhitespace()
        if (index < text.length) fail("there is extra text after the end")
        return value
    }

    @Suppress("CyclomaticComplexMethod") // A dispatch on the next character, one short branch per construct.
    private fun readValue(depth: Int): JsonValue {
        if (depth > MAX_DEPTH) fail("it is nested too deeply")
        skipWhitespace()
        val startLine = line
        return when (val c = peek()) {
            '{' -> readObject(depth)
            '[' -> readArray(depth)
            '"' -> JsonValue.Str(startLine, readString())
            't' -> readLiteral("true", JsonValue.Bool(startLine, true))
            'f' -> readLiteral("false", JsonValue.Bool(startLine, false))
            'n' -> readLiteral("null", JsonValue.Null(startLine))
            null -> fail("it ends too early")
            else -> if (c == '-' || c.isDigit()) readNumber() else fail("“$c” isn't valid here")
        }
    }

    private fun readObject(depth: Int): JsonValue.Obj {
        val startLine = line
        index++
        val members = mutableListOf<Member>()
        skipWhitespace()
        if (peek() == '}') {
            index++
            return JsonValue.Obj(startLine, members)
        }
        while (true) {
            skipWhitespace()
            if (peek() != '"') fail("expected a quoted key")
            val keyLine = line
            val name = readString()
            if (members.any { it.name == name }) fail("“$name” appears twice", keyLine)
            skipWhitespace()
            expect(':')
            members += Member(name, keyLine, readValue(depth + 1))
            skipWhitespace()
            when (next()) {
                ',' -> continue
                '}' -> return JsonValue.Obj(startLine, members)
                else -> fail("expected “,” or “}”")
            }
        }
    }

    private fun readArray(depth: Int): JsonValue.Arr {
        val startLine = line
        index++
        val items = mutableListOf<JsonValue>()
        skipWhitespace()
        if (peek() == ']') {
            index++
            return JsonValue.Arr(startLine, items)
        }
        while (true) {
            items += readValue(depth + 1)
            skipWhitespace()
            when (next()) {
                ',' -> continue
                ']' -> return JsonValue.Arr(startLine, items)
                else -> fail("expected “,” or “]”")
            }
        }
    }

    private fun readString(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            val c = next() ?: fail("a string is never closed")
            when {
                c == '"' -> return out.toString()
                c == '\\' -> out.append(readEscape())
                c < ' ' -> fail("a string contains a raw control character")
                else -> out.append(c)
            }
        }
    }

    private fun readEscape(): Char =
        when (val c = next()) {
            '"', '\\', '/' -> c
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> readUnicodeEscape()
            else -> fail("“\\$c” isn't a valid escape")
        }

    private fun readUnicodeEscape(): Char {
        if (index + UNICODE_DIGITS > text.length) fail("a \\u escape is cut short")
        val digits = text.substring(index, index + UNICODE_DIGITS)
        val code = digits.toIntOrNull(HEX_RADIX) ?: fail("“\\u$digits” isn't a valid escape")
        index += UNICODE_DIGITS
        return code.toChar()
    }

    private fun readNumber(): JsonValue.Num {
        val startLine = line
        val match = NUMBER.matchAt(text, index) ?: fail("a number is malformed")
        index += match.value.length
        val value = match.value.toDoubleOrNull() ?: fail("a number is malformed")
        return JsonValue.Num(startLine, value)
    }

    private fun readLiteral(
        word: String,
        value: JsonValue,
    ): JsonValue {
        if (!text.startsWith(word, index)) fail("expected “$word”")
        index += word.length
        return value
    }

    private fun skipWhitespace() {
        while (index < text.length) {
            when (text[index]) {
                '\n' -> line++
                ' ', '\t', '\r' -> Unit
                else -> return
            }
            index++
        }
    }

    private fun expect(c: Char) {
        if (next() != c) fail("expected “$c”")
    }

    private fun peek(): Char? = text.getOrNull(index)

    private fun next(): Char? {
        val c = text.getOrNull(index) ?: return null
        index++
        if (c == '\n') line++
        return c
    }

    private fun fail(
        message: String,
        at: Int = line,
    ): Nothing = throw SyntaxError(at, message)

    companion object {
        private const val MAX_DEPTH = 32
        private const val UNICODE_DIGITS = 4
        private const val HEX_RADIX = 16
        private val NUMBER = Regex("""-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?""")

        /** Parses [text]; never throws. A leading byte-order mark is ignored. */
        fun parse(text: String): JsonParse {
            val reader = JsonReader(text.removePrefix("﻿"))
            return try {
                JsonParse.Ok(reader.parseDocument())
            } catch (error: SyntaxError) {
                JsonParse.Failed(error.line, error.message)
            }
        }
    }
}
