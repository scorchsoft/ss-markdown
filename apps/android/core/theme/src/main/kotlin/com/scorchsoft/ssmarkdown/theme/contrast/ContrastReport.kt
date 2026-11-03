package com.scorchsoft.ssmarkdown.theme.contrast

import com.scorchsoft.ssmarkdown.theme.PaletteKey
import com.scorchsoft.ssmarkdown.theme.Theme
import com.scorchsoft.ssmarkdown.theme.ThemeColor
import com.scorchsoft.ssmarkdown.theme.ThemeVariant
import kotlin.math.floor

/**
 * A pair of palette colours that people read one on the other: research
 * R11 asks for text, link and code; headings and muted text are read too.
 * Borders are decorative and are not checked.
 *
 * @property foreground the text colour.
 * @property background what it sits on.
 * @property subject how messages start ("Links are").
 * @property large true when the text is always large (WCAG's 18 pt), so
 *   the lower ratio applies. Nothing in a theme is guaranteed large at
 *   every zoom, so all built-in pairs are false.
 */
public enum class ContrastPair(
    public val foreground: PaletteKey,
    public val background: PaletteKey,
    public val subject: String,
    public val large: Boolean = false,
) {
    /** Body text on the page. */
    TEXT(PaletteKey.TEXT, PaletteKey.BG, "Body text is"),

    /** Headings on the page. */
    HEADING(PaletteKey.HEADING, PaletteKey.BG, "Headings are"),

    /** Links on the page. */
    LINK(PaletteKey.LINK, PaletteKey.BG, "Links are"),

    /** Code on the code background. */
    CODE(PaletteKey.CODE, PaletteKey.CODE_BG, "Code is"),

    /** Quotes and captions on the page. */
    MUTED(PaletteKey.MUTED, PaletteKey.BG, "Quotes and captions are"),

    /** Highlighted code's comments on the code background. */
    CODE_COMMENT(PaletteKey.CODE_COMMENT, PaletteKey.CODE_BG, "Code comments are"),

    /** Keywords, types and literals in code. */
    CODE_KEYWORD(PaletteKey.CODE_KEYWORD, PaletteKey.CODE_BG, "Code keywords are"),

    /** Strings in code. */
    CODE_STRING(PaletteKey.CODE_STRING, PaletteKey.CODE_BG, "Code strings are"),

    /** Numbers in code. */
    CODE_NUMBER(PaletteKey.CODE_NUMBER, PaletteKey.CODE_BG, "Code numbers are"),

    /** Function and class names in code. */
    CODE_TITLE(PaletteKey.CODE_TITLE, PaletteKey.CODE_BG, "Function names in code are"),

    /** Attributes, keys and variables in code. */
    CODE_ATTR(PaletteKey.CODE_ATTR, PaletteKey.CODE_BG, "Attributes in code are"),

    /** Built-ins and tags in code. */
    CODE_BUILT_IN(PaletteKey.CODE_BUILT_IN, PaletteKey.CODE_BG, "Built-ins in code are"),

    /** Annotations and preprocessor lines in code. */
    CODE_META(PaletteKey.CODE_META, PaletteKey.CODE_BG, "Annotations in code are"),

    /** Deleted lines in a diff. */
    CODE_DELETION(PaletteKey.CODE_DELETION, PaletteKey.CODE_BG, "Deleted diff lines are"),
}

/**
 * One measured pair.
 *
 * @property variant which palette.
 * @property pair which colours.
 * @property ratio the measured contrast.
 * @property required what [level] demands for this pair.
 * @property level the level it was checked against.
 */
public data class ContrastCheck(
    val variant: ThemeVariant,
    val pair: ContrastPair,
    val ratio: Double,
    val required: Double,
    val level: WcagLevel,
) {
    /** True when [ratio] meets [required]. */
    public val passes: Boolean get() = ratio >= required

    /**
     * The theme editor's inline warning, e.g. "Links are 3.1:1 on this
     * background; 4.5:1 is needed to read easily." Ratios are floored to
     * one decimal so a failing 4.47 never displays as a passing "4.5".
     */
    public fun message(): String =
        "${pair.subject} ${format(ratio)}:1 on this background; " +
            "${format(required)}:1 is needed to read easily."

    private companion object {
        private const val TENTHS = 10.0

        fun format(value: Double): String = (floor(value * TENTHS) / TENTHS).toString().removeSuffix(".0")
    }
}

/**
 * Every [ContrastPair] of a theme, in both variants, against one level.
 *
 * @property level the level checked.
 * @property checks one entry per variant and pair.
 */
public data class ContrastReport(
    val level: WcagLevel,
    val checks: List<ContrastCheck>,
) {
    /** The pairs that fall short; empty means the theme passes [level]. */
    public val failures: List<ContrastCheck> get() = checks.filterNot { it.passes }

    /** True when every pair passes. */
    public val passes: Boolean get() = failures.isEmpty()

    /** Building reports. */
    public companion object {
        /** Measures [theme] against [level] (AA unless asked otherwise). */
        public fun of(
            theme: Theme,
            level: WcagLevel = WcagLevel.AA,
        ): ContrastReport {
            val checks =
                ThemeVariant.entries.flatMap { variant ->
                    val palette = theme.palette(variant)
                    ContrastPair.entries.map { pair ->
                        ContrastCheck(
                            variant = variant,
                            pair = pair,
                            ratio = Contrast.ratio(palette[pair.foreground], palette[pair.background]),
                            required = level.required(pair.large),
                            level = level,
                        )
                    }
                }
            return ContrastReport(level, checks)
        }
    }
}

/**
 * "Fix it": returns [theme] with every failing foreground nudged to the
 * nearest passing lightness (see [ContrastFixer]). Backgrounds are never
 * changed; they are the author's strongest choice.
 */
public fun Theme.withContrastFixed(level: WcagLevel = WcagLevel.AA): Theme {
    var fixed = this
    for (failure in ContrastReport.of(this, level).failures) {
        val palette = fixed.palette(failure.variant)
        val foreground: ThemeColor = palette[failure.pair.foreground]
        val repaired = ContrastFixer.fix(foreground, palette[failure.pair.background], failure.required).color
        val newPalette = palette.with(failure.pair.foreground, repaired)
        fixed =
            when (failure.variant) {
                ThemeVariant.LIGHT -> fixed.copy(light = newPalette)
                ThemeVariant.DARK -> fixed.copy(dark = newPalette)
            }
    }
    return fixed
}
