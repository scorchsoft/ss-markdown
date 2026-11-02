package com.scorchsoft.ssmarkdown.fonts

import com.scorchsoft.ssmarkdown.theme.css.GenericFamily

/**
 * The font picker's filter chips (mockup FontPicker: All, Serif, Sans,
 * Mono) plus Google's two decorative categories.
 *
 * @property key the spelling in the catalogue file.
 * @property generic the CSS generic family a stack falls back to.
 */
enum class FontCategory(
    val key: String,
    val generic: GenericFamily,
) {
    /** Serif text faces. */
    SERIF("serif", GenericFamily.SERIF),

    /** Sans-serif text faces. */
    SANS("sans", GenericFamily.SANS_SERIF),

    /** Monospaced faces, for code. */
    MONO("mono", GenericFamily.MONOSPACE),

    /** Display faces: headings only, really. */
    DISPLAY("display", GenericFamily.SANS_SERIF),

    /** Handwriting faces. */
    HANDWRITING("handwriting", GenericFamily.SANS_SERIF),
    ;

    companion object {
        /** The category spelled [key], or null. */
        fun fromKey(key: String): FontCategory? = entries.firstOrNull { it.key == key }
    }
}

/**
 * One weight-and-slope of a family, written `400`, `700i` in the catalogue
 * and in file names.
 *
 * @property weight the CSS weight, 100–900.
 * @property italic true for the italic.
 */
data class FontStyleKey(
    val weight: Int,
    val italic: Boolean,
) {
    /** The token form: `700i`. */
    val token: String get() = if (italic) "${weight}i" else "$weight"

    companion object {
        private val TOKEN = Regex("""([1-9]00)(i?)""")

        /** Parses `400` or `700i`; null for anything else. */
        fun parse(token: String): FontStyleKey? =
            TOKEN.matchEntire(token.trim())?.let { FontStyleKey(it.groupValues[1].toInt(), it.groupValues[2] == "i") }
    }
}

/** Injected time, so tests control install timestamps. */
fun interface Clock {
    /** Milliseconds since the epoch. */
    fun nowMillis(): Long

    companion object {
        /** The wall clock. */
        val SYSTEM: Clock = Clock { System.currentTimeMillis() }
    }
}
