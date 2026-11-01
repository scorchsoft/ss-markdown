package com.scorchsoft.ssmarkdown.fonts

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Builds tiny but structurally valid sfnt files: just a `name` and an `OS/2` table. */
object TestFonts {
    private const val OS2_LENGTH = 96

    fun ttf(
        family: String?,
        weight: Int = 400,
        italic: Boolean = false,
        signature: Int = 0x00010000,
    ): ByteArray {
        val name = nameTable(family)
        val os2 = os2Table(weight, italic)
        val headerLength = 12 + 2 * 16
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            writeInt(signature)
            writeShort(2)
            writeShort(32)
            writeShort(1)
            writeShort(0)
            record("OS/2", headerLength, os2.size)
            record("name", headerLength + os2.size, name.size)
            write(os2)
            write(name)
        }
        return bytes.toByteArray()
    }

    private fun DataOutputStream.record(
        tag: String,
        offset: Int,
        length: Int,
    ) {
        writeBytes(tag)
        writeInt(0)
        writeInt(offset)
        writeInt(length)
    }

    private fun os2Table(
        weight: Int,
        italic: Boolean,
    ): ByteArray {
        val table = ByteArray(OS2_LENGTH)
        table[4] = (weight shr 8).toByte()
        table[5] = weight.toByte()
        table[63] = if (italic) 1 else 0
        return table
    }

    private fun nameTable(family: String?): ByteArray {
        val strings = family?.toByteArray(Charsets.UTF_16BE) ?: ByteArray(0)
        val count = if (family == null) 0 else 1
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            writeShort(0)
            writeShort(count)
            writeShort(6 + 12 * count)
            if (family != null) {
                writeShort(3)
                writeShort(1)
                writeShort(0x409)
                writeShort(1)
                writeShort(strings.size)
                writeShort(0)
            }
            write(strings)
        }
        return bytes.toByteArray()
    }
}
