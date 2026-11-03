package com.scorchsoft.ssmarkdown.markdown.code

/**
 * The languages fenced code is highlighted in, and the names a fence's
 * info string may use for each: the first word, case-insensitive
 * (```` ```Python title="x" ```` is Python). An unknown or missing name
 * stays plain, as before: no guessing (detection is slow and often wrong).
 *
 * Each language is a [LanguageSpec] for the shared [GenericLexer], or one
 * of the small structural lexers (markup, CSS, YAML, INI/TOML, diff,
 * Markdown). The word lists were written for this project from each
 * language's reference; the token classes mirror highlight.js's, which is
 * what Preview uses, so both views colour the same things the same way.
 */
public object CodeLanguages {
    internal const val GENERIC = 0
    internal const val MARKUP = 1
    internal const val CSS = 2
    internal const val SCSS = 3
    internal const val LESS = 4
    internal const val YAML = 5
    internal const val TOML = 6
    internal const val INI = 7
    internal const val DIFF = 8
    internal const val MARKDOWN = 9

    /** One language: its canonical name, the names that select it, and how it is lexed. */
    internal class Language(
        val name: String,
        val aliases: List<String>,
        val lexer: Int,
        val spec: LanguageSpec? = null,
    )

    internal val ALL: List<Language> by lazy { Specs.languages() }

    private val byAlias: WordTable by lazy {
        val map = HashMap<String, Int>()
        ALL.forEachIndexed { index, language ->
            for (alias in language.aliases + language.name) map[alias.lowercase()] = index + 1
        }
        WordTable(map, ignoreCase = true)
    }

    /** Every canonical language name, for documentation and tests. */
    public val names: List<String> get() = ALL.map { it.name }

    /**
     * The canonical name of the language a fence's [info] string selects
     * (`"ts"` gives `"typescript"`), or null when it selects none.
     */
    public fun languageOf(info: String?): String? {
        if (info == null) return null
        val id = idOf(info, 0, info.length)
        return if (id == 0) null else ALL[id - 1].name
    }

    /** The language id (1-based; 0 for none) of the first word of `text[start, end)`. */
    internal fun idOf(
        text: CharSequence,
        start: Int,
        end: Int,
    ): Int {
        var wordEnd = start
        while (wordEnd < end && text[wordEnd] !in WORD_ENDS && !text[wordEnd].isWhitespace()) wordEnd++
        val value = byAlias.valueOf(text, start, wordEnd)
        return if (value == WordTable.MISSING) 0 else value
    }

    /** The language with [id], which must not be 0. */
    internal fun byId(id: Int): Language = ALL[id - 1]

    /** Characters that end the language word: ```` ```js{1,3} ````, ```` ```python,linenos ````. */
    private const val WORD_ENDS = "{,"
}
