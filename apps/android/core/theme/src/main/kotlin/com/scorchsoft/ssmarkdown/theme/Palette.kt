package com.scorchsoft.ssmarkdown.theme

/**
 * One appearance's colours. The first five keys are the ux-spec schema;
 * [code], [muted] and [border] are optional in JSON and inherit from the
 * base theme like everything else.
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
    ;

    /** Lookup by JSON key. */
    public companion object {
        /** The key spelled [json] in theme JSON, or null. */
        public fun fromJson(json: String): PaletteKey? = entries.firstOrNull { it.json == json }
    }
}
