package com.scorchsoft.ssmarkdown.theme

/**
 * The four themes that ship (docs/ux-spec.md, Themes), each with a light
 * and a dark palette. Every pair here passes WCAG AA and Contrast passes
 * AAA; `BuiltInThemesTest` holds them to it, so a tweak that greys text
 * out fails the build instead of reaching a reader (research R11).
 *
 * Fonts are all bundled (`feature/fonts`), so a built-in never waits for
 * a download.
 */
public object BuiltInThemes {
    private const val INTER = "Inter"
    private const val JETBRAINS_MONO = "JetBrains Mono"

    /** The default: a GitHub-like sans at 15 px and 1.6 line height. */
    public val CLEAN: Theme =
        Theme(
            id = "clean",
            name = "Clean",
            base = null,
            fonts = ThemeFonts(body = INTER, heading = INTER, code = JETBRAINS_MONO),
            size = ThemeSize(base = 15.0, headingScale = 1.25, lineHeight = 1.6),
            light =
                palette(
                    bg = 0xFFFFFF,
                    text = 0x1C1C1E,
                    heading = 0x1C1C1E,
                    link = 0x1D5DBF,
                    code = 0x1C1C1E,
                    codeBg = 0xF4F4F5,
                    muted = 0x5E5E64,
                    border = 0xD9D9DE,
                ),
            dark =
                palette(
                    bg = 0x161618,
                    text = 0xE6E6E9,
                    heading = 0xF4F4F6,
                    link = 0x82B4FF,
                    code = 0xE6E6E9,
                    codeBg = 0x242428,
                    muted = 0xA1A1A8,
                    border = 0x3A3A3F,
                ),
        )

    /**
     * Serif and sepia, for long reading. 15 px, not 17: Literata's wide
     * set held only 41 characters a line on a 390 px phone, under the
     * comfortable 45 (rubric #7; preview web `themes.test.ts`).
     */
    public val READER: Theme =
        Theme(
            id = "reader",
            name = "Reader",
            base = null,
            fonts = ThemeFonts(body = "Literata", heading = "Literata", code = JETBRAINS_MONO),
            size = ThemeSize(base = 15.0, headingScale = 1.25, lineHeight = 1.7),
            light =
                palette(
                    bg = 0xFBF7EF,
                    text = 0x2B2620,
                    heading = 0x7A2E1B,
                    link = 0x9B3B22,
                    code = 0x2B2620,
                    codeBg = 0xF1EADC,
                    muted = 0x6B5F50,
                    border = 0xE2D8C6,
                ),
            dark =
                palette(
                    bg = 0x1E1B17,
                    text = 0xE9E1D3,
                    heading = 0xF0A58C,
                    link = 0xF2B49E,
                    code = 0xE9E1D3,
                    codeBg = 0x2A2620,
                    muted = 0xB3A795,
                    border = 0x3D372F,
                ),
        )

    /** Dense, for technical documents: smaller base, tighter lines and scale. */
    public val COMPACT: Theme =
        Theme(
            id = "compact",
            name = "Compact",
            base = null,
            fonts = ThemeFonts(body = "IBM Plex Sans", heading = "IBM Plex Sans", code = JETBRAINS_MONO),
            size = ThemeSize(base = 14.0, headingScale = 1.2, lineHeight = 1.45),
            light =
                palette(
                    bg = 0xFFFFFF,
                    text = 0x1C1C1E,
                    heading = 0x111113,
                    link = 0x1D5DBF,
                    code = 0x24292F,
                    codeBg = 0xF2F3F5,
                    muted = 0x57606A,
                    border = 0xD0D7DE,
                ),
            dark =
                palette(
                    bg = 0x0F1115,
                    text = 0xE3E6EA,
                    heading = 0xF5F6F8,
                    link = 0x7CB0FF,
                    code = 0xE3E6EA,
                    codeBg = 0x1C1F25,
                    muted = 0x9AA3AD,
                    border = 0x30363D,
                ),
        )

    /** AAA contrast and Atkinson Hyperlegible, for accessibility. */
    public val CONTRAST: Theme =
        Theme(
            id = "contrast",
            name = "Contrast",
            base = null,
            fonts =
                ThemeFonts(
                    body = "Atkinson Hyperlegible Next",
                    heading = "Atkinson Hyperlegible Next",
                    code = JETBRAINS_MONO,
                ),
            size = ThemeSize(base = 16.0, headingScale = 1.25, lineHeight = 1.6),
            light =
                palette(
                    bg = 0xFFFFFF,
                    text = 0x000000,
                    heading = 0x000000,
                    link = 0x0A3A8C,
                    code = 0x000000,
                    codeBg = 0xF0F0F0,
                    muted = 0x2E2E2E,
                    border = 0x000000,
                ),
            dark =
                palette(
                    bg = 0x000000,
                    text = 0xFFFFFF,
                    heading = 0xFFFFFF,
                    link = 0xA8CCFF,
                    code = 0xFFFFFF,
                    codeBg = 0x1A1A1A,
                    muted = 0xD9D9D9,
                    border = 0xFFFFFF,
                ),
        )

    /** The theme a new install uses. */
    public val DEFAULT: Theme = CLEAN

    /** All four, in Display-sheet order. */
    public val ALL: List<Theme> = listOf(CLEAN, READER, COMPACT, CONTRAST)

    /** The built-in with [id], or null. */
    public fun byId(id: String): Theme? = ALL.firstOrNull { it.id == id }

    @Suppress("LongParameterList") // One argument per palette key, named at every call site.
    private fun palette(
        bg: Int,
        text: Int,
        heading: Int,
        link: Int,
        code: Int,
        codeBg: Int,
        muted: Int,
        border: Int,
    ): Palette =
        Palette(
            bg = ThemeColor.of(bg),
            text = ThemeColor.of(text),
            heading = ThemeColor.of(heading),
            link = ThemeColor.of(link),
            code = ThemeColor.of(code),
            codeBg = ThemeColor.of(codeBg),
            muted = ThemeColor.of(muted),
            border = ThemeColor.of(border),
        )
}
