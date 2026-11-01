package com.scorchsoft.ssmarkdown.theme

/**
 * An opaque sRGB colour from a theme palette.
 *
 * Themes deliberately have no alpha: a translucent text colour has no
 * single contrast ratio, so the contrast promise (docs/quality-bar.md)
 * could not be kept. Stored as `0xRRGGBB`.
 *
 * @property rgb the colour packed as `0xRRGGBB`; the top byte is always 0.
 */
@JvmInline
public value class ThemeColor private constructor(
    public val rgb: Int,
) {
    /** Red channel, 0–255. */
    public val red: Int get() = (rgb shr RED_SHIFT) and CHANNEL_MASK

    /** Green channel, 0–255. */
    public val green: Int get() = (rgb shr GREEN_SHIFT) and CHANNEL_MASK

    /** Blue channel, 0–255. */
    public val blue: Int get() = rgb and CHANNEL_MASK

    /** The canonical spelling used in JSON and CSS: `#RRGGBB`, upper case. */
    public val hex: String get() = "#%06X".format(rgb)

    override fun toString(): String = hex

    /** Construction and parsing. */
    public companion object {
        private const val RED_SHIFT = 16
        private const val GREEN_SHIFT = 8
        private const val CHANNEL_MASK = 0xFF
        private const val RGB_MASK = 0xFFFFFF
        private const val SHORT_HEX_LENGTH = 3
        private const val LONG_HEX_LENGTH = 6
        private const val HEX_RADIX = 16

        /** Black, the darkest text a theme can have. */
        public val BLACK: ThemeColor = ThemeColor(0x000000)

        /** White, the lightest. */
        public val WHITE: ThemeColor = ThemeColor(0xFFFFFF)

        /** Packs [rgb], ignoring any alpha byte. */
        public fun of(rgb: Int): ThemeColor = ThemeColor(rgb and RGB_MASK)

        /** Packs three channels, each clamped to 0–255. */
        public fun of(
            red: Int,
            green: Int,
            blue: Int,
        ): ThemeColor {
            val r = red.coerceIn(0, CHANNEL_MASK)
            val g = green.coerceIn(0, CHANNEL_MASK)
            val b = blue.coerceIn(0, CHANNEL_MASK)
            return ThemeColor((r shl RED_SHIFT) or (g shl GREEN_SHIFT) or b)
        }

        /**
         * Parses `#RGB` or `#RRGGBB` (any case), the two spellings people
         * paste from design tools. Anything else, including `#RRGGBBAA`
         * and named colours, is null so the caller can say exactly which
         * field is wrong.
         */
        public fun parse(text: String): ThemeColor? {
            val digits = text.trim().removePrefix("#")
            if (!text.trim().startsWith("#") || digits.any { it.digitToIntOrNull(HEX_RADIX) == null }) return null
            return when (digits.length) {
                SHORT_HEX_LENGTH -> ThemeColor(digits.map { "$it$it" }.joinToString("").toInt(HEX_RADIX))
                LONG_HEX_LENGTH -> ThemeColor(digits.toInt(HEX_RADIX))
                else -> null
            }
        }
    }
}
