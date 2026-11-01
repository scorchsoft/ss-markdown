package com.scorchsoft.ssmarkdown.theme.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonReaderTest {
    private fun ok(text: String) = (JsonReader.parse(text) as JsonParse.Ok).value

    private fun failed(text: String) = JsonReader.parse(text) as JsonParse.Failed

    @Test
    fun `scalars, escapes and nesting`() {
        val value = ok("""{"a": [1, -2.5e2, true, false, null], "b": "x\"\\\/\n\u00e9"}""") as JsonValue.Obj
        val list = value.member("a")?.value as JsonValue.Arr
        assertEquals(listOf(1.0, -250.0), list.items.take(2).map { (it as JsonValue.Num).value })
        assertEquals("x\"\\/\n\u00e9", (value.member("b")?.value as JsonValue.Str).value)
    }

    @Test
    fun `members remember their lines`() {
        val value = ok("{\n\n  \"bg\":\n   \"#fff\"\n}") as JsonValue.Obj
        assertEquals(3, value.member("bg")?.line)
        assertEquals(4, value.member("bg")?.value?.line)
    }

    @Test
    fun `malformed input fails with a line, never throws`() {
        listOf("{", "[1,]", "{\"a\" 1}", "01", "\"\\x\"", "tru", "1 2", "\"a\nb\"", "{\"a\":1,}", "\"\\u12\"").forEach {
            val result = JsonReader.parse(it)
            assertTrue(it, result is JsonParse.Failed)
        }
        assertEquals(2, failed("[1,\n x]").line)
    }

    @Test
    fun `deep nesting is refused instead of overflowing the stack`() {
        assertEquals("it is nested too deeply", failed("[".repeat(10_000)).message)
    }
}
