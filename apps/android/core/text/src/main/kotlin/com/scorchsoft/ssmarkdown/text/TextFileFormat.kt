package com.scorchsoft.ssmarkdown.text

/**
 * Everything about a file's bytes that is not its text: its encoding, how
 * its lines end and whether it starts with a byte order mark. Carried from
 * load to save so an unedited file is written back byte for byte.
 */
public data class TextFileFormat(
    /** The original line breaks, per line when they were mixed. */
    public val lineEndings: LineEndings = LineEndings.DEFAULT,
    /** True when the file began with its encoding's byte order mark; it is written back if so. */
    public val hasByteOrderMark: Boolean = false,
    /** The encoding the file was read in, and so is written in. */
    public val encoding: TextEncoding = TextEncoding.UTF_8,
) {
    /** Defaults. */
    public companion object {
        /** A new file: UTF-8, LF, no BOM. */
        public val DEFAULT: TextFileFormat = TextFileFormat()
    }
}
