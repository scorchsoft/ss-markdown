package com.scorchsoft.ssmarkdown.theme

/**
 * One appearance's colours. The first five keys are the ux-spec schema;
 * the rest are optional in JSON and inherit from the base theme like
 * everything else, so a custom theme without them gets its base's.
 *
 * @property bg the page.
 * @property text body text.
 * @property heading h1–h6.
 * @property link links.
 * @property code text inside inline code and fenced blocks.
 * @property codeBg the background of inline code and fenced blocks.
 * @property muted secondary text: blockquotes, captions, footnotes.
 * @property border rules, table lines and the quote bar (decorative, so
 *   not contrast-checked; see [com.scorchsoft.ssmarkdown.theme.contrast.ContrastReport]).
 * @property syntax the colours of highlighted code inside fenced blocks,
 *   in both the Code view and Preview.
 */
public data class Palette(
    val bg: ThemeColor,
    val text: ThemeColor,
    val heading: ThemeColor,
    val link: ThemeColor,
    val code: ThemeColor,
    val codeBg: ThemeColor,
    val muted: ThemeColor,
    val border: ThemeColor,
    val syntax: SyntaxColors,
) {
    /** Returns a copy with [key] set to [color]. */
    public fun with(
        key: PaletteKey,
        color: ThemeColor,
    ): Palette =
        when (key) {
            PaletteKey.BG -> copy(bg = color)
            PaletteKey.TEXT -> copy(text = color)
            PaletteKey.HEADING -> copy(heading = color)
            PaletteKey.LINK -> copy(link = color)
            PaletteKey.CODE -> copy(code = color)
            PaletteKey.CODE_BG -> copy(codeBg = color)
            PaletteKey.MUTED -> copy(muted = color)
            PaletteKey.BORDER -> copy(border = color)
            else -> copy(syntax = syntax.with(key, color))
        }

    /** The colour stored under [key]. */
    public operator fun get(key: PaletteKey): ThemeColor =
        when (key) {
            PaletteKey.BG -> bg
            PaletteKey.TEXT -> text
            PaletteKey.HEADING -> heading
            PaletteKey.LINK -> link
            PaletteKey.CODE -> code
            PaletteKey.CODE_BG -> codeBg
            PaletteKey.MUTED -> muted
            PaletteKey.BORDER -> border
            else -> syntax[key]
        }
}

/**
 * The colours of highlighted code, one per group of highlight.js token
 * classes (Preview's `code.css` groups them the same way, and the Code
 * view's lexers emit the same classes), so a theme colours a keyword the
 * same in both views. Every one is read on [Palette.codeBg] and is
 * contrast-checked there.
 *
 * @property comment comments (italic).
 * @property keyword keywords, built-in types and literal words.
 * @property string strings, regular expressions and diff additions.
 * @property number numbers, symbols and list bullets.
 * @property title function and class names, section headers.
 * @property attr attributes, keys, properties and sigil variables.
 * @property builtIn built-in functions and markup tags.
 * @property meta annotations, decorators, preprocessor lines.
 * @property deletion diff deletions.
 */
public data class SyntaxColors(
    val comment: ThemeColor,
    val keyword: ThemeColor,
    val string: ThemeColor,
    val number: ThemeColor,
    val title: ThemeColor,
    val attr: ThemeColor,
    val builtIn: ThemeColor,
    val meta: ThemeColor,
    val deletion: ThemeColor,
) {
    /** Returns a copy with [key], a syntax key, set to [color]. */
    public fun with(
        key: PaletteKey,
        color: ThemeColor,
    ): SyntaxColors =
        when (key) {
            PaletteKey.CODE_COMMENT -> copy(comment = color)
            PaletteKey.CODE_KEYWORD -> copy(keyword = color)
            PaletteKey.CODE_STRING -> copy(string = color)
            PaletteKey.CODE_NUMBER -> copy(number = color)
            PaletteKey.CODE_TITLE -> copy(title = color)
            PaletteKey.CODE_ATTR -> copy(attr = color)
            PaletteKey.CODE_BUILT_IN -> copy(builtIn = color)
            PaletteKey.CODE_META -> copy(meta = color)
            PaletteKey.CODE_DELETION -> copy(deletion = color)
            else -> throw IllegalArgumentException("$key is not a syntax colour")
        }

    /** The colour stored under [key], a syntax key. */
    public operator fun get(key: PaletteKey): ThemeColor =
        when (key) {
            PaletteKey.CODE_COMMENT -> comment
            PaletteKey.CODE_KEYWORD -> keyword
            PaletteKey.CODE_STRING -> string
            PaletteKey.CODE_NUMBER -> number
            PaletteKey.CODE_TITLE -> title
            PaletteKey.CODE_ATTR -> attr
            PaletteKey.CODE_BUILT_IN -> builtIn
            PaletteKey.CODE_META -> meta
            PaletteKey.CODE_DELETION -> deletion
            else -> throw IllegalArgumentException("$key is not a syntax colour")
        }

    /**
     * The defaults, which are Preview's highlight.js palette
     * (`feature/preview/web/src/styles/code.css`): a GitHub-like set for
     * every theme, and a darker (light) or lighter (dark) set at AAA for
     * Contrast. `BuiltInThemesTest` reads code.css and fails if they drift.
     */
    public companion object {
        /** Light appearance, every built-in but Contrast. */
        public val LIGHT: SyntaxColors =
            of(0x616972, 0x7C47DD, 0x0A783B, 0xA45100, 0x0550AE, 0x953800, 0x116329, 0x57606A, 0x82071E)

        /** Dark appearance, every built-in but Contrast. */
        public val DARK: SyntaxColors =
            of(0x8B949E, 0xD2A8FF, 0x7EE787, 0xFFA657, 0x79C0FF, 0xFFA198, 0x7EE787, 0x8B949E, 0xFFA198)

        /** Contrast, light: AAA on its code background. */
        public val CONTRAST_LIGHT: SyntaxColors =
            of(0x494F56, 0x5E25C8, 0x075B2D, 0x7D3E00, 0x054BA4, 0x8B3400, 0x105C26, 0x485058, 0x82071E)

        /** Contrast, dark: AAA on its code background. */
        public val CONTRAST_DARK: SyntaxColors =
            of(0xA0A7AF, 0xD2A8FF, 0x7EE787, 0xFFA657, 0x79C0FF, 0xFFA198, 0x7EE787, 0xA0A7AF, 0xFFA198)

        /** The defaults for [variant]: [LIGHT] or [DARK]. */
        public fun defaults(variant: ThemeVariant): SyntaxColors = if (variant == ThemeVariant.DARK) DARK else LIGHT

        @Suppress("LongParameterList") // One colour per group, in the order of the properties.
        private fun of(
            comment: Int,
            keyword: Int,
            string: Int,
            number: Int,
            title: Int,
            attr: Int,
            builtIn: Int,
            meta: Int,
            deletion: Int,
        ): SyntaxColors =
            SyntaxColors(
                comment = ThemeColor.of(comment),
                keyword = ThemeColor.of(keyword),
                string = ThemeColor.of(string),
                number = ThemeColor.of(number),
                title = ThemeColor.of(title),
                attr = ThemeColor.of(attr),
                builtIn = ThemeColor.of(builtIn),
                meta = ThemeColor.of(meta),
                deletion = ThemeColor.of(deletion),
            )
    }
}

/**
 * The palette keys, with their JSON spelling and CSS variable. Keeping
 * all three names in one table means the codec, the CSS generator and
 * THEME-CSS.md can never disagree.
 *
 * @property json the key in theme JSON.
 * @property cssVariable the custom property the preview reads.
 */
public enum class PaletteKey(
    public val json: String,
    public val cssVariable: String,
) {
    /** See [Palette.bg]. */
    BG("bg", "--ssm-bg"),

    /** See [Palette.text]. */
    TEXT("text", "--ssm-text"),

    /** See [Palette.heading]. */
    HEADING("heading", "--ssm-heading"),

    /** See [Palette.link]. */
    LINK("link", "--ssm-link"),

    /** See [Palette.code]. */
    CODE("code", "--ssm-code"),

    /** See [Palette.codeBg]. */
    CODE_BG("codeBg", "--ssm-code-bg"),

    /** See [Palette.muted]. */
    MUTED("muted", "--ssm-muted"),

    /** See [Palette.border]. */
    BORDER("border", "--ssm-border"),

    /** See [SyntaxColors.comment]. */
    CODE_COMMENT("codeComment", "--ssm-code-comment"),

    /** See [SyntaxColors.keyword]. */
    CODE_KEYWORD("codeKeyword", "--ssm-code-keyword"),

    /** See [SyntaxColors.string]. */
    CODE_STRING("codeString", "--ssm-code-string"),

    /** See [SyntaxColors.number]. */
    CODE_NUMBER("codeNumber", "--ssm-code-number"),

    /** See [SyntaxColors.title]. */
    CODE_TITLE("codeTitle", "--ssm-code-title"),

    /** See [SyntaxColors.attr]. */
    CODE_ATTR("codeAttr", "--ssm-code-attr"),

    /** See [SyntaxColors.builtIn]. */
    CODE_BUILT_IN("codeBuiltIn", "--ssm-code-built-in"),

    /** See [SyntaxColors.meta]. */
    CODE_META("codeMeta", "--ssm-code-meta"),

    /** See [SyntaxColors.deletion]. */
    CODE_DELETION("codeDeletion", "--ssm-code-deletion"),
    ;

    /** True for the colours of highlighted code, [SYNTAX]. */
    public val isSyntax: Boolean get() = ordinal >= CODE_COMMENT.ordinal

    /** Lookup by JSON key. */
    public companion object {
        /** The key spelled [json] in theme JSON, or null. */
        public fun fromJson(json: String): PaletteKey? = entries.firstOrNull { it.json == json }

        /** The keys of [SyntaxColors], in order. */
        public val SYNTAX: List<PaletteKey> = entries.filter { it.isSyntax }
    }
}
