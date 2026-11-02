package com.scorchsoft.ssmarkdown.fonts.importing

import java.io.DataInputStream
import java.io.File
import java.io.IOException

/** What the first bytes of a file say it is. */
enum class FontKind(
    /** True when Android's `Typeface` and the WebView can both load it from a file. */
    val usable: Boolean,
) {
    /** A TrueType-outline font (`.ttf`, or `.otf` with TrueType outlines). */
    TRUETYPE(usable = true),

    /** A CFF-outline OpenType font (`.otf`). */
    OPENTYPE(usable = true),

    /** WOFF 1.0: a web wrapper. */
    WOFF(usable = false),

    /** WOFF 2.0: a web wrapper. */
    WOFF2(usable = false),

    /** A `.ttc` collection of several fonts. */
    COLLECTION(usable = false),

    /** Not a font at all. */
    UNKNOWN(usable = false),
}

/**
 * Identifies a font file by its header ("sfnt version" or signature),
 * never by its name: a renamed PDF must not reach `Typeface`, which can
 * crash on garbage on some OEM builds.
 */
object FontFileSniffer {
    /** Bytes needed: the sfnt header up to and including `numTables`. */
    const val HEADER_BYTES: Int = 6

    private const val MAX_TABLES = 64
    private const val SIGNATURE_BYTES = 4
    private const val BYTE_MASK = 0xFF
    private const val BITS_PER_BYTE = 8
    private val TRUETYPE_VERSION = listOf(0x00, 0x01, 0x00, 0x00)

    private val signatures =
        mapOf(
            TRUETYPE_VERSION to FontKind.TRUETYPE,
            "true".map { it.code } to FontKind.TRUETYPE,
            "OTTO".map { it.code } to FontKind.OPENTYPE,
            "wOFF".map { it.code } to FontKind.WOFF,
            "wOF2".map { it.code } to FontKind.WOFF2,
            "ttcf".map { it.code } to FontKind.COLLECTION,
        )

    /** Classifies [header], the file's first bytes (at least [HEADER_BYTES] to judge an sfnt). */
    fun sniff(header: ByteArray): FontKind {
        val kind =
            if (header.size <
                HEADER_BYTES
            ) {
                null
            } else {
                signatures[header.take(SIGNATURE_BYTES).map { it.toInt() and BYTE_MASK }]
            }
        val tables =
            if (header.size < HEADER_BYTES) {
                0
            } else {
                ((header[SIGNATURE_BYTES].toInt() and BYTE_MASK) shl BITS_PER_BYTE) or
                    (header[SIGNATURE_BYTES + 1].toInt() and BYTE_MASK)
            }
        return when {
            kind == null -> FontKind.UNKNOWN
            !kind.usable -> kind
            tables in 1..MAX_TABLES -> kind
            else -> FontKind.UNKNOWN
        }
    }

    /** Classifies the file at [file] by its first bytes. */
    fun sniff(file: File): FontKind {
        val header = ByteArray(HEADER_BYTES)
        val read =
            try {
                DataInputStream(file.inputStream()).use { input ->
                    input.readFully(header)
                    header.size
                }
            } catch (_: IOException) {
                0
            }
        return if (read < HEADER_BYTES) FontKind.UNKNOWN else sniff(header)
    }
}
