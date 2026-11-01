package com.scorchsoft.ssmarkdown.history.versions

/**
 * The little JSON the export manifest and the clean-up log need, so a
 * pure module does not take a dependency for it.
 *
 * Values map to Kotlin as: object → `Map<String, Any?>`, array →
 * `List<Any?>`, string → `String`, integer → `Long`, other number →
 * `Double`, `true`/`false` → `Boolean`, `null` → `null`.
 */
internal object Json {
    private const val HEX_RADIX = 16
    private const val UNICODE_ESCAPE_DIGITS = 4
    private const val FIRST_PRINTABLE = 0x20

    /**
     * Deepest nesting [parse] accepts. Ours never nests past four levels;
     * the limit only stops a hostile file from overflowing the stack
     * (SEC-4), which an Android I/O thread does far sooner than a desktop JVM.
     */
    const val MAX_DEPTH: Int = 64

    /** Serialises [value] (built from the types above). */
    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    /**
     * Parses [text], or throws [IllegalArgumentException] when it is not
     * one JSON value or nests deeper than [MAX_DEPTH].
     */
    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.value()
        parser.skipSpace()
        require(parser.atEnd()) { "trailing characters at ${parser.position}" }
        return value
    }

    private fun append(
        out: StringBuilder,
        value: Any?,
    ) {
        when (value) {
            null -> {
                out.append("null")
            }

            is String -> {
                appendString(out, value)
            }

            is Boolean, is Int, is Long -> {
                out.append(value.toString())
            }

            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { i, (key, item) ->
                    if (i > 0) out.append(',')
                    appendString(out, key.toString())
                    out.append(':')
                    append(out, item)
                }
                out.append('}')
            }

            is List<*> -> {
                out.append('[')
                value.forEachIndexed { i, item ->
                    if (i > 0) out.append(',')
                    append(out, item)
                }
                out.append(']')
            }

            else -> {
                throw IllegalArgumentException("not a JSON value: ${value::class}")
            }
        }
    }

    private fun appendString(
        out: StringBuilder,
        value: String,
    ) {
        out.append('"')
        for (c in value) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c.code < FIRST_PRINTABLE || c.isSurrogate() -> out.append("\\u").append("%04x".format(c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    @Suppress("TooManyFunctions") // One small function per JSON production.
    private class Parser(
        private val text: String,
    ) {
        var position = 0
        private var depth = 0

        fun atEnd() = position >= text.length

        fun skipSpace() {
            while (position < text.length && text[position] in " \t\r\n") position++
        }

        fun value(): Any? {
            skipSpace()
            require(!atEnd()) { "unexpected end" }
            return when (text[position]) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '"' -> stringValue()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        private fun literal(
            word: String,
            result: Any?,
        ): Any? {
            require(text.startsWith(word, position)) { "bad literal at $position" }
            position += word.length
            return result
        }

        private fun objectValue(): Map<String, Any?> = nested { objectBody() }

        private fun arrayValue(): List<Any?> = nested { arrayBody() }

        private inline fun <T> nested(body: () -> T): T {
            require(++depth <= MAX_DEPTH) { "nested deeper than $MAX_DEPTH at $position" }
            return body().also { depth-- }
        }

        private fun objectBody(): Map<String, Any?> {
            position++
            val out = LinkedHashMap<String, Any?>()
            skipSpace()
            if (text.getOrNull(position) == '}') return out.also { position++ }
            while (true) {
                skipSpace()
                val key = stringValue()
                skipSpace()
                expect(':')
                out[key] = value()
                skipSpace()
                if (text.getOrNull(position) == '}') return out.also { position++ }
                expect(',')
            }
        }

        private fun arrayBody(): List<Any?> {
            position++
            val out = ArrayList<Any?>()
            skipSpace()
            if (text.getOrNull(position) == ']') return out.also { position++ }
            while (true) {
                out += value()
                skipSpace()
                if (text.getOrNull(position) == ']') return out.also { position++ }
                expect(',')
            }
        }

        private fun expect(c: Char) {
            require(text.getOrNull(position) == c) { "expected '$c' at $position" }
            position++
        }

        fun stringValue(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                require(!atEnd()) { "unterminated string" }
                val c = text[position++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> out.append(escape())
                    else -> out.append(c)
                }
            }
        }

        private fun escape(): Char {
            require(!atEnd()) { "unterminated escape" }
            return when (val c = text[position++]) {
                'n' -> {
                    '\n'
                }

                't' -> {
                    '\t'
                }

                'r' -> {
                    '\r'
                }

                'b' -> {
                    '\b'
                }

                'f' -> {
                    '\u000C'
                }

                'u' -> {
                    require(position + UNICODE_ESCAPE_DIGITS <= text.length) { "short \\u escape" }
                    val code = text.substring(position, position + UNICODE_ESCAPE_DIGITS).toInt(HEX_RADIX)
                    position += UNICODE_ESCAPE_DIGITS
                    code.toChar()
                }

                '"', '\\', '/' -> {
                    c
                }

                else -> {
                    throw IllegalArgumentException("bad escape \\$c")
                }
            }
        }

        private fun number(): Any {
            val start = position
            while (position < text.length && text[position] in "+-0123456789.eE") position++
            val token = text.substring(start, position)
            return token.toLongOrNull() ?: requireNotNull(token.toDoubleOrNull()) { "bad number '$token' at $start" }
        }
    }
}

/** Typed reads from a parsed JSON object; each throws [IllegalArgumentException] on a wrong shape. */
internal fun Map<*, *>.string(key: String): String = requireNotNull(this[key] as? String) { "missing string '$key'" }

internal fun Map<*, *>.stringOrNull(key: String): String? = this[key] as? String

internal fun Map<*, *>.long(key: String): Long = requireNotNull(this[key] as? Long) { "missing number '$key'" }

internal fun Map<*, *>.longOrNull(key: String): Long? = this[key] as? Long

internal fun Map<*, *>.int(key: String): Int = long(key).toInt()

internal fun Map<*, *>.boolean(key: String): Boolean = this[key] as? Boolean ?: false

internal fun Map<*, *>.list(key: String): List<*> = requireNotNull(this[key] as? List<*>) { "missing list '$key'" }
