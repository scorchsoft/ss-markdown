package com.scorchsoft.ssmarkdown.theme.codec

import com.scorchsoft.ssmarkdown.theme.Theme

/**
 * One thing wrong (or ignored) in a theme file, pinned to where it is so
 * the person can find it.
 *
 * @property line 1-based line in the file; null only for whole-file
 *   problems such as "too large".
 * @property field the key at fault ("bg"), or null when the problem is
 *   not about one key (broken JSON).
 * @property message the rest of the sentence: "isn't a colour".
 */
public data class ThemeProblem(
    val line: Int?,
    val field: String?,
    val message: String,
) {
    /**
     * The sentence the theme editor shows, in the mockup's form:
     * `line 12, “bg” isn't a colour`.
     */
    public fun describe(): String {
        val where = line?.let { "line $it, " }.orEmpty()
        val what = field?.let { "“$it” " }.orEmpty()
        return where + what + message
    }

    override fun toString(): String = describe()
}

/** The outcome of reading a theme file. */
public sealed interface ThemeDecodeResult {
    /**
     * The file is a usable theme.
     *
     * @property theme the complete theme, with gaps filled from its base.
     * @property warnings keys that were ignored (from a newer version, or
     *   a typo); worth showing, never blocking.
     */
    public data class Decoded(
        val theme: Theme,
        val warnings: List<ThemeProblem>,
    ) : ThemeDecodeResult

    /**
     * The file cannot be used. Nothing is half-imported.
     *
     * @property problems every problem found, in file order; the first is
     *   the one to show inline.
     */
    public data class Invalid(
        val problems: List<ThemeProblem>,
    ) : ThemeDecodeResult {
        /**
         * The import banner: "Couldn't import midnight.json: line 12,
         * “bg” isn't a colour."
         */
        public fun headline(fileName: String): String {
            val first = problems.firstOrNull()?.describe() ?: "it isn't a theme"
            return "Couldn't import $fileName: $first."
        }
    }
}
