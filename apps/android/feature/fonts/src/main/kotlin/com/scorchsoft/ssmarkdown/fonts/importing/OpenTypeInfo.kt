package com.scorchsoft.ssmarkdown.fonts.importing

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * What an imported font says about itself: its family name (so the
 * picker shows "Comic Neue", not "cn-reg-v2.ttf") and its weight and
 * slope (from the `OS/2` table).
 *
 * @property family the typographic family (name ID 16), else the legacy family (ID 1).
 * @property weight `usWeightClass`, clamped to 100–900.
 * @property italic `fsSelection` bit 0.
 */
data class OpenTypeInfo(
    val family: String?,
    val weight: Int,
    val italic: Boolean,
) {
    companion object {
        // Offsets from the OpenType spec (https://learn.microsoft.com/typography/opentype/spec/otff).
        private const val NUM_TABLES = 4L
        private const val TABLE_DIRECTORY = 12
        private const val TABLE_RECORD = 16
        private const val TAG_LENGTH = 4
        private const val RECORD_OFFSET = 8
        private const val NAME_COUNT = 2
        private const val NAME_STRINGS = 4
        private const val NAME_RECORDS = 6
        private const val NAME_RECORD = 12
        private const val RECORD_NAME_ID = 6
        private const val RECORD_LENGTH = 8
        private const val RECORD_STRING = 10
        private const val MIN_WEIGHT = 100
        private const val MAX_WEIGHT = 900
        private const val U32_MASK = 0xFFFFFFFFL
        private const val TYPOGRAPHIC_FAMILY = 16
        private const val LEGACY_FAMILY = 1
        private const val PLATFORM_WINDOWS = 3
        private const val PLATFORM_UNICODE = 0
        private const val WEIGHT_OFFSET = 4L
        private const val SELECTION_OFFSET = 62L
        private const val DEFAULT_WEIGHT = 400
        private const val MAX_NAME_RECORDS = 1024

        /**
         * Reads [file], which must already have passed [FontFileSniffer].
         * Null when its table directory or name table is damaged.
         */
        fun read(file: File): OpenTypeInfo? =
            try {
                RandomAccessFile(file, "r").use { raf ->
                    val tables = tables(raf)
                    val os2 = tables["OS/2"]
                    val weight = os2?.let { raf.u16(it + WEIGHT_OFFSET) } ?: DEFAULT_WEIGHT
                    val italic = os2?.let { raf.u16(it + SELECTION_OFFSET) and 1 == 1 } ?: false
                    OpenTypeInfo(
                        tables["name"]?.let { familyName(raf, it) },
                        weight.coerceIn(MIN_WEIGHT, MAX_WEIGHT),
                        italic,
                    )
                }
            } catch (_: IOException) {
                null
            }

        private fun tables(raf: RandomAccessFile): Map<String, Long> {
            val count = raf.u16(NUM_TABLES)
            return (0 until count).associate { i ->
                val record = TABLE_DIRECTORY + i * TABLE_RECORD.toLong()
                val tag =
                    ByteArray(TAG_LENGTH)
                        .also {
                            raf.seek(record)
                            raf.readFully(it)
                        }.toString(Charsets.ISO_8859_1)
                val offset = raf.u32(record + RECORD_OFFSET)
                if (offset >= raf.length()) throw IOException("table $tag points outside the file")
                tag to offset
            }
        }

        private fun familyName(
            raf: RandomAccessFile,
            table: Long,
        ): String? {
            val count = raf.u16(table + NAME_COUNT).coerceAtMost(MAX_NAME_RECORDS)
            val strings = table + raf.u16(table + NAME_STRINGS)
            val names =
                (0 until count)
                    .mapNotNull { i ->
                        val record = table + NAME_RECORDS + i * NAME_RECORD.toLong()
                        val platform = raf.u16(record)
                        val nameId = raf.u16(record + RECORD_NAME_ID)
                        val unicode = platform == PLATFORM_WINDOWS || platform == PLATFORM_UNICODE
                        if (!unicode ||
                            (nameId != TYPOGRAPHIC_FAMILY && nameId != LEGACY_FAMILY)
                        ) {
                            return@mapNotNull null
                        }
                        val length = raf.u16(record + RECORD_LENGTH)
                        val offset = raf.u16(record + RECORD_STRING)
                        nameId to utf16(raf, strings + offset, length)
                    }.toMap()
            return (names[TYPOGRAPHIC_FAMILY] ?: names[LEGACY_FAMILY])?.trim()?.takeIf { it.isNotEmpty() }
        }

        private fun utf16(
            raf: RandomAccessFile,
            at: Long,
            length: Int,
        ): String {
            if (at + length > raf.length()) throw IOException("a name points outside the file")
            val bytes = ByteArray(length)
            raf.seek(at)
            raf.readFully(bytes)
            return bytes.toString(Charsets.UTF_16BE)
        }

        private fun RandomAccessFile.u16(at: Long): Int {
            seek(at)
            return readUnsignedShort()
        }

        private fun RandomAccessFile.u32(at: Long): Long {
            seek(at)
            return readInt().toLong() and U32_MASK
        }
    }
}
