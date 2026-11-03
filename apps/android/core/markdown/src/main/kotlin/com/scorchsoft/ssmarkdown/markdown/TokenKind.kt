package com.scorchsoft.ssmarkdown.markdown

/**
 * What a run of characters *is*, syntactically, so the editor can colour
 * it. One kind per run; the inline styles a run sits inside (bold, italic,
 * link...) travel separately as [InlineStyle] flags, because Markdown nests
 * and a flat colour alone cannot say "a link inside bold text".
 *
 * Markers (`**`, `#`, `` ` ``, `[`) have their own kinds so the editor can
 * mute them while the content they wrap keeps its emphasis. The ordinal is
 * packed into [Tokens], so new kinds go at the end and there may be at most
 * 256 of them.
 */
public enum class TokenKind {
    /** Ordinary text with no markup. Also the implicit kind past the tokenised prefix of a long line. */
    PLAIN,

    /** The backslash of a backslash escape; the escaped character itself stays [PLAIN]. */
    ESCAPE,

    /** The `#` run that opens an ATX heading, and its optional closing run. */
    HEADING_MARKER,

    /** The text of a heading, ATX or setext. */
    HEADING_TEXT,

    /** A `===` or `---` line that turns the paragraph line above it into a heading. */
    SETEXT_UNDERLINE,

    /** A `*` or `_` that opens or closes emphasis. */
    EMPHASIS_MARKER,

    /** Text inside `*emphasis*`, when no more specific kind applies. */
    EMPHASIS_TEXT,

    /** A `**` or `__` that opens or closes strong emphasis. */
    STRONG_MARKER,

    /** Text inside `**strong**`, when no more specific kind applies. */
    STRONG_TEXT,

    /** A `~~` (or `~`) that opens or closes strikethrough. */
    STRIKETHROUGH_MARKER,

    /** Text inside `~~strikethrough~~`. */
    STRIKETHROUGH_TEXT,

    /** A backtick run that opens or closes an inline code span. */
    CODE_SPAN_MARKER,

    /** The text inside an inline code span. */
    CODE_SPAN_TEXT,

    /** The backtick or tilde run that opens or closes a fenced code block. */
    CODE_FENCE,

    /** The info string after an opening fence: the language and anything after it. */
    CODE_FENCE_INFO,

    /** A line of a fenced or indented code block. */
    CODE_BLOCK_TEXT,

    /** The visible text of a link: `[this](...)`. */
    LINK_TEXT,

    /** Link brackets and parentheses: `[`, `](`, `)`, `][`, and the `:` of a reference definition. */
    LINK_PUNCTUATION,

    /** A link or image destination, or a reference definition's URL. */
    LINK_URL,

    /** A link title: `"title"`, `'title'` or `(title)`. */
    LINK_TITLE,

    /** The label in `[text][label]`, `[label]: url`. */
    LINK_LABEL,

    /** The `!` that makes a link an image; the rest of an image uses the link kinds. */
    IMAGE_MARKER,

    /** `<https://...>`, `<me@example.com>` or a bare `https://` / `www.` link. */
    AUTOLINK,

    /** A bullet (`-`, `+`, `*`) or ordinal (`1.`, `1)`) that starts a list item. */
    LIST_MARKER,

    /** An unchecked task box, `[ ]`. */
    TASK_OPEN,

    /** A checked task box, `[x]` or `[X]`. */
    TASK_DONE,

    /** A `>` that opens or continues a block quote. */
    QUOTE_MARKER,

    /** A `|` that separates table cells. */
    TABLE_PIPE,

    /** The `|---|:---:|` line under a table's header row. */
    TABLE_DELIMITER_ROW,

    /** A thematic break: `---`, `***`, `___`. */
    HORIZONTAL_RULE,

    /** An inline HTML tag or comment. */
    HTML_TAG,

    /** A line inside an HTML block. */
    HTML_BLOCK,

    /** The `---` (or `+++`) lines that open and close front matter. */
    FRONT_MATTER_DELIMITER,

    /** A line of front matter between its delimiters. */
    FRONT_MATTER_TEXT,

    /** `$`, `$$`: the delimiters of inline or display math. */
    MATH_DELIMITER,

    /** TeX between math delimiters. */
    MATH_TEXT,

    /** A footnote reference, `[^1]`. */
    FOOTNOTE_REF,

    /** The `[^1]:` that opens a footnote definition. */
    FOOTNOTE_LABEL,

    // Inside a fenced code block with a known language (code/CodeLanguages.kt). The names follow
    // highlight.js's token classes, so the Code view and Preview colour the same things the same way.

    /** A language keyword: `def`, `return`, `public`. */
    CODE_KEYWORD,

    /** A built-in type name: `int`, `string`, `Vec`. Coloured as a keyword, as highlight.js does. */
    CODE_TYPE,

    /** A literal word: `true`, `None`, `null`. Coloured as a keyword. */
    CODE_LITERAL,

    /** A built-in function or object: `print`, `console`, `echo`. */
    CODE_BUILT_IN,

    /** A string or character literal, quotes included. */
    CODE_STRING,

    /** A regular-expression literal. Coloured as a string. */
    CODE_REGEXP,

    /** A number, a CSS length or a hex colour. */
    CODE_NUMBER,

    /** A comment, line or block. */
    CODE_COMMENT,

    /** A function's name where it is defined or called, and a class's where it is declared. */
    CODE_FUNCTION,

    /** An annotation, decorator, attribute, preprocessor line or `<?php` tag. */
    CODE_META,

    /** A sigil variable: PHP and shell `$name`, Ruby `@name`, SCSS `$name`. */
    CODE_VARIABLE,

    /** A markup tag's name and brackets: `<div`, `/>`. */
    CODE_TAG,

    /** A markup attribute, a JSON or YAML key, a CSS property, an ini key. */
    CODE_ATTRIBUTE,

    /** A section header: `[section]` in ini and TOML, a heading in Markdown. */
    CODE_SECTION,

    /** A symbol: Ruby's `:name`, a Rust lifetime, a YAML or Markdown list bullet. Coloured as a number. */
    CODE_SYMBOL,

    /** A `+` line of a diff. */
    CODE_ADDITION,

    /** A `-` line of a diff. */
    CODE_DELETION,
    ;

    internal companion object {
        /** Indexed by ordinal; `values()` allocates a fresh array on every call. */
        val ALL: Array<TokenKind> = entries.toTypedArray()
    }
}

/**
 * Inline styles that wrap a run, as bit flags. A run inside `**[a *b*](u)**`
 * is `STRONG or LINK or EMPHASIS`, whatever its [TokenKind]. Flags rather
 * than an enum set because they are packed into one byte of a token.
 */
public object InlineStyle {
    /** Inside `*emphasis*` or `_emphasis_`. */
    public const val EMPHASIS: Int = 1

    /** Inside `**strong**` or `__strong__`. */
    public const val STRONG: Int = 2

    /** Inside `~~strikethrough~~`. */
    public const val STRIKETHROUGH: Int = 4

    /** Inside a link's or image's visible text. */
    public const val LINK: Int = 8
}
