package com.scorchsoft.ssmarkdown.theme.css

/**
 * One font file the preview may load, already resolved to a URL the
 * WebView can fetch (an asset or a downloaded file). `core:theme` never
 * knows where fonts live; `feature/fonts` answers through [FontFaceSource].
 *
 * @property family the CSS family name, matching the theme's font name.
 * @property url where the WebView loads the file from.
 * @property italic true for the italic face.
 * @property weights the weights the file covers: one value for a static
 *   face, a range (`100..900`) for a variable font.
 * @property format the file format, for the `format()` hint.
 */
public data class FontFace(
    val family: String,
    val url: String,
    val italic: Boolean,
    val weights: IntRange,
    val format: FontFormat,
)

/** Font file formats the preview accepts, with their CSS `format()` keyword. */
public enum class FontFormat(
    public val css: String,
) {
    /** `.ttf` */
    TRUETYPE("truetype"),

    /** `.otf` */
    OPENTYPE("opentype"),

    /** `.woff2` */
    WOFF2("woff2"),
}

/** CSS generic families, the last entry of every font stack. */
public enum class GenericFamily(
    public val css: String,
) {
    /** Serif text faces. */
    SERIF("serif"),

    /** Sans-serif text faces; also the fallback for display and handwriting faces. */
    SANS_SERIF("sans-serif"),

    /** Code. */
    MONOSPACE("monospace"),
}

/**
 * Where [ThemeCss] learns which files back a family and what kind of face
 * it is. Implemented by `feature/fonts`; tests pass a fake.
 */
public interface FontFaceSource {
    /** The faces available for [family]; empty when it is not on this phone yet. */
    public fun faces(family: String): List<FontFace>

    /** The generic family to fall back to for [family]. */
    public fun generic(family: String): GenericFamily

    /** A source with no files: every stack falls straight through to its generic family. */
    public object None : FontFaceSource {
        private val serif = setOf("Literata", "Source Serif 4", "Lora", "Merriweather")

        override fun faces(family: String): List<FontFace> = emptyList()

        override fun generic(family: String): GenericFamily =
            if (family in serif) GenericFamily.SERIF else GenericFamily.SANS_SERIF
    }
}
