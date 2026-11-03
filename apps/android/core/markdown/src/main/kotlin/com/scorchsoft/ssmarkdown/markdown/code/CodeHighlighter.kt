package com.scorchsoft.ssmarkdown.markdown.code

import com.scorchsoft.ssmarkdown.markdown.TokenBuilder

/**
 * One [com.scorchsoft.ssmarkdown.markdown.LineTokenizer]'s lexers, one of
 * each kind, created on first use: they keep scratch fields, so they are
 * not shared between tokenizers (or threads).
 */
internal class CodeHighlighter {
    private val generic by lazy(LazyThreadSafetyMode.NONE) { GenericLexer() }
    private val markup by lazy(LazyThreadSafetyMode.NONE) { MarkupLexer() }
    private val css by lazy(LazyThreadSafetyMode.NONE) { CssLexer(preprocessor = false, less = false) }
    private val scss by lazy(LazyThreadSafetyMode.NONE) { CssLexer(preprocessor = true, less = false) }
    private val less by lazy(LazyThreadSafetyMode.NONE) { CssLexer(preprocessor = true, less = true) }
    private val yaml by lazy(LazyThreadSafetyMode.NONE) { YamlLexer() }
    private val toml by lazy(LazyThreadSafetyMode.NONE) { IniLexer(toml = true) }
    private val ini by lazy(LazyThreadSafetyMode.NONE) { IniLexer(toml = false) }
    private val diff by lazy(LazyThreadSafetyMode.NONE) { DiffLexer() }
    private val markdown by lazy(LazyThreadSafetyMode.NONE) { MarkdownCodeLexer() }

    /**
     * Paints `text[from, to)` of a code line in language [language] (an id
     * from [CodeLanguages.idOf], not 0), starting in [state]; returns the
     * next line's state.
     */
    @Suppress("LongParameterList") // The hot per-line call: plain values, no wrapper.
    fun lex(
        language: Int,
        text: CharSequence,
        from: Int,
        to: Int,
        state: Int,
        out: TokenBuilder,
    ): Int {
        val lang = CodeLanguages.byId(language)
        val spec = lang.spec
        if (spec != null) return generic.lex(spec, text, from, to, state, out)
        val lexer =
            when (lang.lexer) {
                CodeLanguages.MARKUP -> markup
                CodeLanguages.CSS -> css
                CodeLanguages.SCSS -> scss
                CodeLanguages.LESS -> less
                CodeLanguages.YAML -> yaml
                CodeLanguages.TOML -> toml
                CodeLanguages.INI -> ini
                CodeLanguages.DIFF -> diff
                else -> markdown
            }
        return lexer.lex(text, from, to, state, out)
    }
}
