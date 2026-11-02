package com.scorchsoft.ssmarkdown.text

/**
 * The encodings a file is read in and written back in. Every one is
 * lossless for any text the editor can hold, so a file is always saved in
 * the encoding it was opened in, never quietly converted (robustness.md,
 * "Encodings").
 */
public enum class TextEncoding(
    /** The `java.nio` charset name. */
    public val charsetName: String,
    byteOrderMark: ByteArray,
) {
    /** UTF-8, with or without `EF BB BF`. Every new file. */
    UTF_8("UTF-8", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())),

    /** UTF-16, low byte first (Windows "Unicode"), with or without `FF FE`. */
    UTF_16LE("UTF-16LE", byteArrayOf(0xFF.toByte(), 0xFE.toByte())),

    /** UTF-16, high byte first, with or without `FE FF`. */
    UTF_16BE("UTF-16BE", byteArrayOf(0xFE.toByte(), 0xFF.toByte())),
    ;

    private val bom = byteOrderMark

    /** This encoding's byte order mark: what a file that had one starts with again. */
    public val byteOrderMark: ByteArray get() = bom.copyOf()

    internal val bomSize: Int get() = bom.size

    internal fun bomByte(index: Int): Byte = bom[index]
}
