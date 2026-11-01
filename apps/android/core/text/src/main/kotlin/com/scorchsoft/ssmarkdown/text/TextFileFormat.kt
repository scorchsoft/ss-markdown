package com.scorchsoft.ssmarkdown.text

/**
 * Everything about a file's bytes that is not its text: how its lines end
 * and whether it starts with a UTF-8 byte order mark. Carried from load to
 * save so an unedited file is written back byte for byte.
 */
public data class TextFileFormat(
    /** The original line breaks, per line when they were mixed. */
    public val lineEndings: LineEndings = LineEndings.DEFAULT,
    /** True when the file began with EF BB BF; it is written back if so. */
    public val hasByteOrderMark: Boolean = false,
) {
    /** Defaults. */
    public companion object {
        /** A new file: LF, no BOM. */
        public val DEFAULT: TextFileFormat = TextFileFormat()
    }
}
