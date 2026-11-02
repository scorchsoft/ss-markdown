package com.scorchsoft.ssmarkdown.theme.json

/**
 * Writes pretty JSON with two-space indentation and keys in the order
 * given, so an exported theme reads like the ux-spec example and diffs
 * cleanly. Objects whose values are all scalars stay on one line, the
 * way the spec writes palettes.
 */
internal object JsonWriter {
    private const val INDENT = "  "
    private const val CONTROL_LIMIT = 0x20

    /** A value to write: a scalar, a nested object or a list of strings. */
    sealed interface Out {
        data class Text(
            val value: String,
        ) : Out

        data class Number(
            val value: Double,
        ) : Out

        data class Object(
            val members: List<Pair<String, Out>>,
        ) : Out
    }

    fun write(root: Out.Object): String = StringBuilder().apply { writeValue(root, 0) }.append('\n').toString()

    private fun StringBuilder.writeValue(
        value: Out,
        depth: Int,
    ) {
        when (value) {
            is Out.Text -> appendQuoted(value.value)
            is Out.Number -> append(formatNumber(value.value))
            is Out.Object -> writeObject(value, depth)
        }
    }

    private fun StringBuilder.writeObject(
        value: Out.Object,
        depth: Int,
    ) {
        if (value.members.isEmpty()) {
            append("{}")
            return
        }
        val inline = value.members.none { it.second is Out.Object }
        if (inline && depth > 0) {
            append("{ ")
            value.members.forEachIndexed { i, (name, member) ->
                if (i > 0) append(", ")
                appendQuoted(name)
                append(": ")
                writeValue(member, depth + 1)
            }
            append(" }")
            return
        }
        append("{\n")
        value.members.forEachIndexed { i, (name, member) ->
            append(INDENT.repeat(depth + 1))
            appendQuoted(name)
            append(": ")
            writeValue(member, depth + 1)
            if (i < value.members.lastIndex) append(',')
            append('\n')
        }
        append(INDENT.repeat(depth)).append('}')
    }

    private fun StringBuilder.appendQuoted(text: String) {
        append('"')
        for (c in text) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c.code < CONTROL_LIMIT -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    /** Whole numbers print without ".0" (`16`, not `16.0`), as people write them. */
    private fun formatNumber(value: Double): String =
        if (value == Math.rint(value) && !value.isInfinite()) value.toLong().toString() else value.toString()
}
