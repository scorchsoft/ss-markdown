package com.scorchsoft.ssmarkdown.markdown.code

import com.scorchsoft.ssmarkdown.markdown.TokenKind

/**
 * HTML, XML and SVG: tags and their names, attributes, attribute values,
 * comments and declarations (`<!DOCTYPE>`, `<?xml ?>`). Text between tags
 * stays plain, and so do the contents of `<script>` and `<style>`: nothing
 * is nested. Carries an open tag (attributes often wrap), an open quoted
 * value inside one, an open comment and an open declaration.
 */
internal class MarkupLexer : CodeLexer() {
    private var mode = NORMAL

    override fun lex(
        from: Int,
        state: Int,
    ): Int {
        mode = CodeState.mode(state)
        var i =
            when (mode) {
                COMMENT -> {
                    comment(from, from)
                }

                DECLARATION -> {
                    declaration(from, from)
                }

                QUOTED_DOUBLE, QUOTED_SINGLE -> {
                    val close = quoted(from, from, if (mode == QUOTED_DOUBLE) '"' else '\'')
                    if (mode == TAG) insideTag(close) else close
                }

                TAG -> {
                    insideTag(from)
                }

                else -> {
                    from
                }
            }
        while (i < end) i = if (text[i] == '<') open(i) else i + 1
        return CodeState.pack(mode)
    }

    private fun open(i: Int): Int {
        val next = i + 1
        return when {
            matchesAt(i, "<!--") -> {
                mode = COMMENT
                comment(i + COMMENT_OPEN, i)
            }

            next < end && (text[next] == '!' || text[next] == '?') -> {
                mode = DECLARATION
                declaration(next, i)
            }

            next < end &&
                (isNameStart(text[next]) || (text[next] == '/' && next + 1 < end && isNameStart(text[next + 1]))) -> {
                var j = if (text[next] == '/') next + 1 else next
                while (j < end && isTagNamePart(text[j])) j++
                paint(i, j, TokenKind.CODE_TAG)
                mode = TAG
                insideTag(j)
            }

            else -> {
                next
            }
        }
    }

    private fun comment(
        from: Int,
        paintFrom: Int,
    ): Int {
        var j = from
        while (j < end && !matchesAt(j, "-->")) j++
        val close = if (j < end) j + COMMENT_CLOSE else end
        if (j < end) mode = NORMAL
        paint(paintFrom, close, TokenKind.CODE_COMMENT)
        return close
    }

    private fun declaration(
        from: Int,
        paintFrom: Int,
    ): Int {
        var j = from
        while (j < end && text[j] != '>') j++
        val close = if (j < end) j + 1 else end
        if (j < end) mode = NORMAL
        paint(paintFrom, close, TokenKind.CODE_META)
        return close
    }

    /** Attributes up to the tag's `>` or `/>`. */
    @Suppress("CyclomaticComplexMethod") // One branch per thing inside a tag.
    private fun insideTag(from: Int): Int {
        var i = from
        while (i < end) {
            val c = text[i]
            when {
                c == '>' || (c == '/' && i + 1 < end && text[i + 1] == '>') -> {
                    val close = if (c == '>') i + 1 else i + 2
                    paint(i, close, TokenKind.CODE_TAG)
                    mode = NORMAL
                    return close
                }

                c == '"' || c == '\'' -> {
                    mode = if (c == '"') QUOTED_DOUBLE else QUOTED_SINGLE
                    i = quoted(i + 1, i, c)
                    if (mode != TAG) return i
                }

                isNameStart(c) || c == '@' || c == ':' -> {
                    var j = i + 1
                    while (j < end && isTagNamePart(text[j])) j++
                    paint(i, j, TokenKind.CODE_ATTRIBUTE)
                    i = j
                }

                else -> {
                    i++
                }
            }
        }
        return end
    }

    /** A quoted attribute value; back to [TAG] when it closes. */
    private fun quoted(
        from: Int,
        paintFrom: Int,
        quote: Char,
    ): Int {
        var j = from
        while (j < end && text[j] != quote) j++
        val close = if (j < end) j + 1 else end
        paint(paintFrom, close, TokenKind.CODE_STRING)
        if (j < end) mode = TAG
        return close
    }

    private fun isTagNamePart(c: Char): Boolean = isNamePart(c) || c == '-' || c == ':' || c == '.'

    private companion object {
        const val NORMAL = 0
        const val TAG = 1
        const val COMMENT = 2
        const val DECLARATION = 3
        const val QUOTED_DOUBLE = 4
        const val QUOTED_SINGLE = 5
        const val COMMENT_OPEN = 4
        const val COMMENT_CLOSE = 3
    }
}
