package com.scorchsoft.ssmarkdown.markdown.code

/**
 * The table that drives [GenericLexer] for one language: its words, how
 * its comments and strings are written, and a few switches for the
 * features only some languages have. Written for this project from each
 * language's own reference, not copied from any highlighter's grammar;
 * only the token class names follow highlight.js
 * (docs/performance-techniques.md, "Code highlighting").
 *
 * @property words every reserved word, type, literal and built-in, each
 *   with its [Word] class: one lookup per identifier.
 * @property lineComments markers that comment out the rest of the line.
 * @property blockComment the open and close markers of a block comment, or null.
 * @property nestedComments true where block comments nest (Rust, Swift, Kotlin, Dart).
 * @property quotes the characters that open a single-line string.
 * @property multiLineQuotes delimiters that open a string ending at the same
 *   delimiter, possibly on a later line (`"""`, Go's backtick).
 * @property stringPrefixes characters that may prefix a quote and belong to
 *   the string (`f"..."`, `r"..."`, C#'s `@"..."`). An `@` prefix turns escapes off.
 * @property templates true for JavaScript's backtick strings with `${}`.
 * @property regexLiterals true where `/.../` after an operator is a regular expression.
 * @property annotations true where `@name` is an annotation or decorator.
 * @property sigils characters that make the following name a variable (`$` in PHP and shell, `@` in Ruby).
 * @property preprocessor true where `#directive` at the start of a line is a preprocessor line (C family).
 * @property rustSyntax true for Rust's `#[...]` attributes, `'a` lifetimes and `r#"..."#` raw strings.
 * @property hashCommentNeedsSpace true where `#` starts a comment only at the start of a word (shell).
 * @property callsAreTitles true where `name(` is coloured as a function name, as highlight.js does for these.
 * @property symbols true for Ruby's `:symbol`.
 * @property dashedNames true where names may contain `-` and such a name is a command (PowerShell `Get-Item`).
 * @property stringKeys true where a string followed by `:` is a key (JSON).
 * @property leadingKeywords words that are instructions only as a line's first word (Dockerfile), or null.
 * @property phpTags true for `<?php`, `?>` and PHP 8's `#[...]` attributes.
 * @property prompts true where a line is a command only after a `$ ` prompt (console sessions).
 * @property dollarInNames true where `$` is an ordinary identifier character (JavaScript, Java).
 */
@Suppress("LongParameterList") // A table: every switch is named at each language's definition.
internal class LanguageSpec(
    val words: WordTable,
    val lineComments: Array<String> = emptyArray(),
    val blockComment: Array<String>? = null,
    val nestedComments: Boolean = false,
    val quotes: String = "\"'",
    val multiLineQuotes: Array<String> = emptyArray(),
    val stringPrefixes: String = "",
    val templates: Boolean = false,
    val regexLiterals: Boolean = false,
    val annotations: Boolean = false,
    val sigils: String = "",
    val preprocessor: Boolean = false,
    val rustSyntax: Boolean = false,
    val hashCommentNeedsSpace: Boolean = false,
    val callsAreTitles: Boolean = false,
    val symbols: Boolean = false,
    val dashedNames: Boolean = false,
    val stringKeys: Boolean = false,
    val leadingKeywords: WordTable? = null,
    val phpTags: Boolean = false,
    val prompts: Boolean = false,
    val dollarInNames: Boolean = false,
) {
    init {
        require(multiLineQuotes.size <= MAX_MULTI_LINE_QUOTES) { "At most $MAX_MULTI_LINE_QUOTES multi-line quotes" }
    }

    internal companion object {
        /** A multi-line quote's index is kept in the low bits of the state's extra byte. */
        const val MAX_MULTI_LINE_QUOTES = 16
    }
}

/** The classes a [LanguageSpec]'s word table assigns, and the flag for words that name what follows. */
internal object Word {
    const val KEYWORD = 1
    const val TYPE = 2
    const val LITERAL = 3
    const val BUILT_IN = 4

    /** OR-ed into a keyword that declares the next identifier: `def`, `class`, `fn`. */
    const val TITLE = 8
    const val CLASS_MASK = 7

    /**
     * One table for a language's words. A word listed twice keeps its
     * first class, in the order keywords, types, literals, built-ins.
     */
    @Suppress("LongParameterList") // Each list is named at the call site.
    fun table(
        keywords: String,
        types: String = "",
        literals: String = "",
        builtIns: String = "",
        titles: String = "",
        ignoreCase: Boolean = false,
    ): WordTable {
        val map = LinkedHashMap<String, Int>()

        fun add(
            list: String,
            value: Int,
        ) {
            for (word in WordTable.split(list)) {
                val key = if (ignoreCase) word.lowercase() else word
                if (key !in map) map[key] = value
            }
        }
        add(keywords, KEYWORD)
        add(types, TYPE)
        add(literals, LITERAL)
        add(builtIns, BUILT_IN)
        for (word in WordTable.split(titles)) {
            val key = if (ignoreCase) word.lowercase() else word
            map[key] = (map[key] ?: KEYWORD) or TITLE
        }
        return WordTable(map, ignoreCase)
    }
}
