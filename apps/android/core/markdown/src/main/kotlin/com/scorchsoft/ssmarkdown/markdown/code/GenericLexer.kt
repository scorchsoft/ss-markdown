package com.scorchsoft.ssmarkdown.markdown.code

import com.scorchsoft.ssmarkdown.markdown.TokenBuilder
import com.scorchsoft.ssmarkdown.markdown.TokenKind

/**
 * The table-driven lexer behind every programming language here: one
 * left-to-right pass that recognises comments, strings, numbers,
 * annotations, sigils and words, configured by a [LanguageSpec].
 *
 * What it carries between lines ([CodeState]):
 * - [BLOCK_COMMENT], with the nesting depth where comments nest;
 * - [MULTI_STRING], with which of the spec's multi-line quotes is open (or
 *   the `#` count of a Rust raw string);
 * - [TEMPLATE] and [TEMPLATE_EXPR] for JavaScript template literals, the
 *   latter with the `${` brace depth.
 *
 * Simplifications, all deliberate and listed in docs/edge-cases.md: a
 * template literal nested inside `${}` must close on its own line;
 * heredocs are not tracked (their body is lexed as code); a regex literal
 * must close on its line, otherwise its `/` is an operator.
 */
@Suppress("TooManyFunctions") // One small function per kind of token; together they are the lexer.
internal class GenericLexer : CodeLexer() {
    private lateinit var spec: LanguageSpec
    private var mode = NORMAL
    private var depth = 0
    private var extra = 0
    private var lineStart = true
    private var valueBefore = false
    private var titleNext = false

    @Suppress("LongParameterList") // The hot per-line call: plain values, no wrapper.
    fun lex(
        spec: LanguageSpec,
        line: CharSequence,
        from: Int,
        to: Int,
        state: Int,
        target: TokenBuilder,
    ): Int {
        this.spec = spec
        return lex(line, from, to, state, target)
    }

    override fun lex(
        from: Int,
        state: Int,
    ): Int {
        mode = CodeState.mode(state)
        depth = CodeState.depth(state)
        extra = CodeState.extra(state)
        lineStart = true
        valueBefore = false
        titleNext = false
        var i = resume(from)
        if (spec.prompts && mode == NORMAL && i == from) {
            i = prompt(from)
            if (i < 0) return state
        }
        while (i < end) i = step(i)
        return if (mode == NORMAL) 0 else CodeState.pack(mode, depth, extra)
    }

    /** Finishes what the previous line left open. */
    private fun resume(from: Int): Int =
        when (mode) {
            BLOCK_COMMENT -> blockComment(from, from)
            MULTI_STRING -> multiString(from, from)
            TEMPLATE -> template(from, from)
            else -> from
        }

    /** A console line: a `$ ` (or `#`, `%`) prompt, then a command; anything else is output, left plain. */
    private fun prompt(from: Int): Int {
        val p = skipSpaces(from)
        val isPrompt = p + 1 < end && text[p] in PROMPTS && text[p + 1] == ' '
        if (!isPrompt) return -1
        paint(p, p + 1, TokenKind.CODE_META)
        return p + 2
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount") // A precedence list: the first rule that matches wins.
    private fun step(i: Int): Int {
        val c = text[i]
        if (isSpace(c)) return i + 1
        if (mode == TEMPLATE_EXPR && (c == '{' || c == '}')) return templateBrace(i, c)
        val special = commentOrMeta(i, c)
        if (special >= 0) return significant(special, value = false)
        val string = stringAt(i, c, i)
        if (string >= 0) return significant(string, value = true)
        return when {
            c == '/' && spec.regexLiterals && !valueBefore -> regex(i)
            c.isDigitAscii() || (c == '.' && i + 1 < end && text[i + 1].isDigitAscii()) -> number(i)
            c == '@' && spec.annotations && i + 1 < end && isNameStart(text[i + 1]) -> annotation(i)
            c in spec.sigils && i + 1 < end -> sigil(i)
            c == ':' && spec.symbols && i + 1 < end && isNameStart(text[i + 1]) && !after(i, ':') -> symbol(i)
            isNameStart(c) || (c == '$' && spec.dollarInNames) -> word(i)
            else -> punctuation(i, c)
        }
    }

    /** Comments, preprocessor lines, attributes and PHP tags; -1 if none starts at [i]. */
    private fun commentOrMeta(
        i: Int,
        c: Char,
    ): Int {
        val meta = metaAt(i, c)
        val block = spec.blockComment
        return when {
            meta >= 0 -> {
                meta
            }

            spec.lineComments.any { matchesAt(i, it) && commentMayStart(i, it) } -> {
                paint(i, end, TokenKind.CODE_COMMENT)
                end
            }

            block != null && matchesAt(i, block[0]) -> {
                mode = BLOCK_COMMENT
                depth = 1
                blockComment(i + block[0].length, i)
            }

            else -> {
                -1
            }
        }
    }

    /** A `.` joining the parts of a dotted annotation: `@app.route`. */
    private fun isDottedPart(j: Int): Boolean = text[j] == '.' && j + 1 < end && isNameStart(text[j + 1])

    /** A character inside a name: `$` too where names may hold it (JavaScript, Java). */
    private fun isWordPart(c: Char): Boolean = isNamePart(c) || (c == '$' && spec.dollarInNames)

    /** Shell's `#` starts a comment only at the start of a word: `a#b` and `$#` are not comments. */
    private fun commentMayStart(
        i: Int,
        marker: String,
    ): Boolean = marker != "#" || !spec.hashCommentNeedsSpace || lineStart || i == 0 || isSpace(text[i - 1])

    /** Attributes, preprocessor lines and PHP tags; -1 if none starts at [i]. */
    private fun metaAt(
        i: Int,
        c: Char,
    ): Int =
        when {
            c == '#' && (spec.rustSyntax || spec.phpTags) && bracketAttribute(i) -> attribute(i)
            c == '#' && spec.preprocessor && lineStart -> preprocessor(i)
            !spec.phpTags -> -1
            matchesAt(i, PHP_OPEN) -> phpTag(i, PHP_OPEN.length)
            matchesAt(i, "<?") || matchesAt(i, "?>") -> phpTag(i, 2)
            else -> -1
        }

    /** A string of any kind starting at [i], painted from [paintFrom] (a prefix's start); -1 if none. */
    private fun stringAt(
        i: Int,
        c: Char,
        paintFrom: Int,
    ): Int {
        val quote = spec.multiLineQuotes.indexOfFirst { matchesAt(i, it) }
        return when {
            quote >= 0 -> {
                mode = MULTI_STRING
                extra = quote
                multiString(i + spec.multiLineQuotes[quote].length, paintFrom)
            }

            c == '`' && spec.templates -> {
                templateOpen(i, paintFrom)
            }

            isSymbolPrefix(i, c) -> {
                stringAt(i + 1, text[i + 1], paintFrom)
            }

            c !in spec.quotes -> {
                -1
            }

            else -> {
                quoted(i, c, paintFrom)
            }
        }
    }

    /** C#'s `@"..."` and `$"..."`: a prefix that is not a letter. */
    private fun isSymbolPrefix(
        i: Int,
        c: Char,
    ): Boolean = c in spec.stringPrefixes && !isNameStart(c) && i + 1 < end && text[i + 1] in spec.quotes

    /** A single-line string (or a Rust char) opened by [c] at [i]. */
    private fun quoted(
        i: Int,
        c: Char,
        paintFrom: Int,
    ): Int {
        if (c == '\'' && spec.rustSyntax) return rustQuote(i)
        // C#'s verbatim `@"C:\path"` has no escapes.
        val close = stringEnd(i, c, escapes = !(paintFrom < i && text[paintFrom] == '@'))
        paint(paintFrom, close, TokenKind.CODE_STRING)
        if (spec.stringKeys && c == '"') {
            val next = skipSpaces(close)
            if (next < end && text[next] == ':') paint(paintFrom, close, TokenKind.CODE_ATTRIBUTE)
        }
        return close
    }

    private fun significant(
        next: Int,
        value: Boolean,
    ): Int {
        lineStart = false
        valueBefore = value
        titleNext = false
        return next
    }

    private fun blockComment(
        from: Int,
        paintFrom: Int,
    ): Int {
        val block = checkNotNull(spec.blockComment)
        var j = from
        while (j < end) {
            when {
                spec.nestedComments && matchesAt(j, block[0]) -> {
                    depth = minOf(depth + 1, CodeState.MAX_DEPTH)
                    j += block[0].length
                }

                matchesAt(j, block[1]) -> {
                    j += block[1].length
                    depth--
                    if (depth <= 0) {
                        mode = NORMAL
                        depth = 0
                        paint(paintFrom, j, TokenKind.CODE_COMMENT)
                        return j
                    }
                }

                else -> {
                    j++
                }
            }
        }
        paint(paintFrom, end, TokenKind.CODE_COMMENT)
        return end
    }

    /** The rest of a multi-line string whose delimiter is in [extra]. */
    private fun multiString(
        from: Int,
        paintFrom: Int,
    ): Int {
        val raw = extra and RAW_FLAG != 0
        val close = if (raw) null else spec.multiLineQuotes[extra]
        val escapes = close != null && close != "`"
        var j = from
        while (j < end) {
            val c = text[j]
            val closes = if (close != null) matchesAt(j, close) else c == '"' && hashesAt(j + 1) >= extra - RAW_FLAG
            when {
                c == '\\' && escapes -> {
                    j += 2
                }

                closes -> {
                    j += close?.length ?: (1 + extra - RAW_FLAG)
                    mode = NORMAL
                    extra = 0
                    paint(paintFrom, j, TokenKind.CODE_STRING)
                    return j
                }

                else -> {
                    j++
                }
            }
        }
        paint(paintFrom, end, TokenKind.CODE_STRING)
        return end
    }

    private fun hashesAt(from: Int): Int {
        var j = from
        while (j < end && text[j] == '#') j++
        return j - from
    }

    private fun templateOpen(
        i: Int,
        paintFrom: Int,
    ): Int {
        if (mode == TEMPLATE_EXPR) {
            // A template inside `${}`: it must close on this line (a documented simplification).
            var j = i + 1
            while (j < end && text[j] != '`') j += if (text[j] == '\\') 2 else 1
            val close = minOf(j + 1, end)
            paint(paintFrom, close, TokenKind.CODE_STRING)
            return close
        }
        mode = TEMPLATE
        return template(i + 1, paintFrom)
    }

    /** The rest of a template literal: to its closing backtick, or to the next `${`. */
    private fun template(
        from: Int,
        paintFrom: Int,
    ): Int {
        var j = from
        while (j < end) {
            val c = text[j]
            when {
                c == '\\' -> {
                    j += 2
                }

                c == '`' -> {
                    mode = NORMAL
                    paint(paintFrom, j + 1, TokenKind.CODE_STRING)
                    valueBefore = true
                    return j + 1
                }

                c == '$' && j + 1 < end && text[j + 1] == '{' -> {
                    paint(paintFrom, j, TokenKind.CODE_STRING)
                    mode = TEMPLATE_EXPR
                    depth = 1
                    valueBefore = false
                    return j + 2
                }

                else -> {
                    j++
                }
            }
        }
        paint(paintFrom, end, TokenKind.CODE_STRING)
        return end
    }

    private fun templateBrace(
        i: Int,
        c: Char,
    ): Int {
        if (c == '{') {
            depth = minOf(depth + 1, CodeState.MAX_DEPTH)
            return significant(i + 1, value = false)
        }
        depth--
        if (depth > 0) return significant(i + 1, value = true)
        mode = TEMPLATE
        depth = 0
        return template(i + 1, i + 1)
    }

    /** Rust: `'a'` and `'\n'` are chars, `'a` and `'static` are lifetimes. */
    private fun rustQuote(i: Int): Int {
        val next = i + 1
        if (next >= end) return i + 1
        val charEnd = if (Character.isHighSurrogate(text[next])) next + 2 else next + 1
        return when {
            text[next] == '\\' || (charEnd < end && text[charEnd] == '\'') -> {
                val close = stringEnd(i, '\'')
                paint(i, close, TokenKind.CODE_STRING)
                close
            }

            isNameStart(text[next]) -> {
                var j = next + 1
                while (j < end && isNamePart(text[j])) j++
                paint(i, j, TokenKind.CODE_SYMBOL)
                j
            }

            else -> {
                i + 1
            }
        }
    }

    private fun regex(i: Int): Int {
        var j = i + 1
        var inClass = false
        while (j < end) {
            val c = text[j]
            when {
                c == '\\' -> j++
                c == '[' -> inClass = true
                c == ']' -> inClass = false
                c == '/' && !inClass -> break
            }
            j++
        }
        if (j >= end) return punctuation(i, '/')
        j++
        while (j < end && text[j] in 'a'..'z') j++
        paint(i, j, TokenKind.CODE_REGEXP)
        return significant(j, value = true)
    }

    private fun number(i: Int): Int {
        val j = numberEnd(i)
        paint(i, j, TokenKind.CODE_NUMBER)
        return significant(j, value = true)
    }

    /** `@Override`, `@app.route`: the name and its dotted parts. */
    private fun annotation(i: Int): Int {
        var j = i + 1
        while (j < end && (isNamePart(text[j]) || isDottedPart(j))) j++
        paint(i, j, TokenKind.CODE_META)
        return significant(j, value = false)
    }

    /** `$name`, `${NAME}`, `$1`, `@ivar`, `@@cvar`. */
    private fun sigil(i: Int): Int {
        val j = variableEnd(i, if (text[i] == '@' && text[i + 1] == '@') i + 2 else i + 1)
        if (j < 0) return punctuation(i, text[i])
        paint(i, j, TokenKind.CODE_VARIABLE)
        return significant(j, value = true)
    }

    /** Where the variable whose name starts at [name] ends; -1 if the sigil at [i] starts none. */
    private fun variableEnd(
        i: Int,
        name: Int,
    ): Int {
        if (name >= end) return -1
        val dollar = text[i] == '$'
        val c = text[name]
        return when {
            isNameStart(c) -> {
                var j = name
                while (j < end && isNamePart(text[j])) j++
                j
            }

            dollar && c == '{' -> {
                var j = name
                while (j < end && text[j] != '}') j++
                minOf(j + 1, end)
            }

            dollar && (c.isDigitAscii() || c in SHELL_SPECIALS) -> {
                name + 1
            }

            else -> {
                -1
            }
        }
    }

    private fun symbol(i: Int): Int {
        var j = i + 1
        while (j < end && isNamePart(text[j])) j++
        if (j < end && (text[j] == '?' || text[j] == '!')) j++
        paint(i, j, TokenKind.CODE_SYMBOL)
        return significant(j, value = true)
    }

    @Suppress("CyclomaticComplexMethod") // One branch per word class.
    private fun word(i: Int): Int {
        var j = i + 1
        while (j < end && isWordPart(text[j])) j++
        var dashed = false
        if (spec.dashedNames) {
            while (j + 1 < end && text[j] == '-' && isNameStart(text[j + 1])) {
                dashed = true
                j += 2
                while (j < end && isNamePart(text[j])) j++
            }
        }
        prefixedString(i, j)?.let { return significant(it, value = true) }
        if (spec.rustSyntax) {
            rawString(i, j)?.let { return significant(it, value = true) }
            if (isMacroCall(j)) {
                paint(i, j + 1, TokenKind.CODE_BUILT_IN)
                return significant(j + 1, value = false)
            }
        }
        val wasTitle = titleNext
        val value = spec.words.valueOf(text, i, j)
        val kind =
            when {
                wasTitle -> TokenKind.CODE_FUNCTION
                lineStart && spec.leadingKeywords?.contains(text, i, j) == true -> TokenKind.CODE_KEYWORD
                value != WordTable.MISSING -> wordKind(value)
                dashed -> TokenKind.CODE_BUILT_IN
                spec.callsAreTitles && j < end && text[j] == '(' -> TokenKind.CODE_FUNCTION
                else -> null
            }
        if (kind != null) paint(i, j, kind)
        significant(j, value = kind != TokenKind.CODE_KEYWORD)
        titleNext = !wasTitle && value != WordTable.MISSING && value and Word.TITLE != 0
        return j
    }

    /** Rust's `println!`, `vec!`: a name then `!` (not `!=`). */
    private fun isMacroCall(nameEnd: Int): Boolean {
        val bang = nameEnd < end && text[nameEnd] == '!'
        return bang && (nameEnd + 1 >= end || text[nameEnd + 1] != '=')
    }

    private fun wordKind(value: Int): TokenKind =
        when (value and Word.CLASS_MASK) {
            Word.TYPE -> TokenKind.CODE_TYPE
            Word.LITERAL -> TokenKind.CODE_LITERAL
            Word.BUILT_IN -> TokenKind.CODE_BUILT_IN
            else -> TokenKind.CODE_KEYWORD
        }

    /** `f"..."`, `rb'...'`, `u8"..."`: a short all-prefix word right before a quote is part of the string. */
    private fun prefixedString(
        start: Int,
        wordEnd: Int,
    ): Int? {
        if (wordEnd - start > MAX_PREFIX || wordEnd >= end || spec.stringPrefixes.isEmpty()) return null
        for (k in start until wordEnd) if (text[k] !in spec.stringPrefixes) return null
        val c = text[wordEnd]
        val opens = c in spec.quotes || spec.multiLineQuotes.any { matchesAt(wordEnd, it) }
        return if (opens) stringAt(wordEnd, c, start).takeIf { it >= 0 } else null
    }

    /** Rust's `r"..."` and `r#"..."#` (also `br`), which may span lines and have no escapes. */
    private fun rawString(
        start: Int,
        wordEnd: Int,
    ): Int? {
        val prefix = wordEnd - start
        val isRaw = (prefix == 1 && text[start] == 'r') || (prefix == 2 && text[start] == 'b' && text[start + 1] == 'r')
        if (!isRaw) return null
        val hashes = hashesAt(wordEnd)
        val quote = wordEnd + hashes
        if (quote >= end || text[quote] != '"' || hashes > CodeState.MAX_EXTRA - RAW_FLAG) return null
        mode = MULTI_STRING
        extra = RAW_FLAG + hashes
        return multiString(quote + 1, start)
    }

    /** `#[derive(Debug)]`, `#![allow(x)]`, PHP's `#[Attr]`. */
    private fun bracketAttribute(i: Int): Boolean =
        (i + 1 < end && text[i + 1] == '[') || (spec.rustSyntax && matchesAt(i + 1, "!["))

    private fun attribute(i: Int): Int {
        var j = i + 1
        var brackets = 0
        while (j < end) {
            when (text[j]) {
                '[' -> brackets++
                ']' -> if (--brackets == 0) break
            }
            j++
        }
        val close = minOf(j + 1, end)
        paint(i, close, TokenKind.CODE_META)
        return close
    }

    /** `#include <stdio.h>`: the directive is meta, an angle-bracket path a string; the rest lexes as code. */
    private fun preprocessor(i: Int): Int {
        var j = skipSpaces(i + 1)
        val word = j
        while (j < end && isNamePart(text[j])) j++
        paint(i, j, TokenKind.CODE_META)
        val includes = matchesAt(word, "include") || matchesAt(word, "import")
        val path = skipSpaces(j)
        if (includes && path < end && text[path] == '<') {
            var close = path + 1
            while (close < end && text[close] != '>') close++
            close = minOf(close + 1, end)
            paint(path, close, TokenKind.CODE_STRING)
            return close
        }
        return j
    }

    private fun phpTag(
        i: Int,
        length: Int,
    ): Int {
        paint(i, i + length, TokenKind.CODE_META)
        return i + length
    }

    private fun punctuation(
        i: Int,
        c: Char,
    ): Int {
        lineStart = false
        titleNext = false
        valueBefore = c == ')' || c == ']' || c == '}'
        return i + 1
    }

    private fun after(
        i: Int,
        c: Char,
    ): Boolean = i > 0 && (text[i - 1] == c || isNamePart(text[i - 1]))

    private companion object {
        const val NORMAL = 0
        const val BLOCK_COMMENT = 1
        const val MULTI_STRING = 2
        const val TEMPLATE = 3
        const val TEMPLATE_EXPR = 4

        /** In [extra] while a Rust raw string is open; the rest of the byte counts its `#`s. */
        const val RAW_FLAG = 0x80
        const val MAX_PREFIX = 2
        const val PHP_OPEN = "<?php"
        const val PROMPTS = "$#%"
        const val SHELL_SPECIALS = "@#?$!*-"
    }
}
