package com.scorchsoft.ssmarkdown.theme

/**
 * A preview theme: fonts, sizes and a light and a dark palette
 * (docs/ux-spec.md, Themes). The same shape serves the four built-ins and
 * every custom theme, so the preview never needs to know which it has.
 *
 * A theme is always complete. A custom theme's JSON may leave fields out;
 * [ThemeCodec] fills them from its [base] before a `Theme` exists.
 *
 * @property id a stable key: a built-in's id, or a slug for a custom theme.
 * @property name what the Display sheet shows ("Studio Serif").
 * @property base the built-in this theme inherits from; null for built-ins.
 * @property fonts the three font roles.
 * @property size base size, heading scale and line height.
 * @property light the palette used in light appearance (and always for print).
 * @property dark the palette used in dark appearance.
 * @property css "Advanced CSS": rules appended after the generated ones.
 */
public data class Theme(
    val id: String,
    val name: String,
    val base: String?,
    val fonts: ThemeFonts,
    val size: ThemeSize,
    val light: Palette,
    val dark: Palette,
    val css: String = "",
) {
    /** The palette for [variant]. */
    public fun palette(variant: ThemeVariant): Palette =
        when (variant) {
            ThemeVariant.LIGHT -> light
            ThemeVariant.DARK -> dark
        }

    /** Constants of the JSON format. */
    public companion object {
        /**
         * The JSON format version this build writes. Readers accept this
         * and older; a newer file is refused with a message rather than
         * half-read (docs/engineering.md: versioned formats).
         */
        public const val SCHEMA_VERSION: Int = 1
    }
}

/** Which palette of a theme is in use. */
public enum class ThemeVariant(
    /** The CSS `color-scheme` keyword, so form controls and scrollbars match. */
    public val cssColorScheme: String,
) {
    /** Light appearance; also every printout (ux-spec "PDF"). */
    LIGHT("light"),

    /** Dark appearance. */
    DARK("dark"),
}

/**
 * The three font roles. Each is a Google Fonts family name (or a bundled
 * or imported family); resolving it to files is `feature/fonts`' job.
 *
 * @property body paragraphs, lists, tables.
 * @property heading h1–h6.
 * @property code inline code and fenced blocks inside the preview. The
 *   editor's own code font is a separate setting (ux-spec "Themes").
 */
public data class ThemeFonts(
    val body: String,
    val heading: String,
    val code: String,
)

/**
 * Type sizes. [base] is what "100%" means for preview text (ux-spec,
 * "Text size: one model"): pinch and the Display stepper scale it.
 *
 * @property base body size in CSS px at 100%.
 * @property headingScale the modular scale between heading levels.
 * @property lineHeight unitless body line height.
 */
public data class ThemeSize(
    val base: Double,
    val headingScale: Double,
    val lineHeight: Double,
) {
    /** The body size in CSS px at a zoom of [percent] (100 = the theme's base). */
    public fun pxAt(percent: Int): Double = base * percent / PERCENT

    /** Bounds the JSON validator enforces, chosen so text stays readable and layout sane. */
    public companion object {
        private const val PERCENT = 100.0

        /** Allowed [base], px. */
        public val BASE_RANGE: ClosedFloatingPointRange<Double> = 10.0..32.0

        /** Allowed [headingScale]. */
        public val HEADING_SCALE_RANGE: ClosedFloatingPointRange<Double> = 1.0..2.0

        /** Allowed [lineHeight]. */
        public val LINE_HEIGHT_RANGE: ClosedFloatingPointRange<Double> = 1.0..2.5
    }
}
