package com.scorchsoft.ssmarkdown.history.journal

/**
 * UTF-8 that never loses a character: WTF-8 (https://simonsapin.github.io/wtf-8/).
 *
 * The JDK's UTF-8 encoder replaces a lone surrogate with `?`. A user can
 * produce one (an edit that lands between the halves of an emoji, a
 * paste from a broken source), and a recovery journal that quietly turns
 * it into `?` has lost work. WTF-8 is byte-for-byte UTF-8 for every valid
 * string and encodes a lone surrogate as its own three-byte sequence, so
 * the round trip is exact for any `String`.
 */
internal object Wtf8 {
    private const val MAX_ONE_BYTE = 0x7F
    private const val MAX_TWO_BYTE = 0x7FF
    private const val MAX_THREE_BYTE = 0xFFFF
    private const val MAX_CODE_POINT = 0x10FFFF
    private const val CONTINUATION = 0x80
    private const val CONTINUATION_MASK = 0xC0
    private const val PAYLOAD_6 = 0x3F
    private const val LEAD_2 = 0xC0
    private const val LEAD_3 = 0xE0
    private const val LEAD_4 = 0xF0
    private const val LEAD_INVALID = 0xF8
    private const val BYTE = 0xFF
    private const val BITS_PER_CONTINUATION = 6
    private const val TWO_BYTES = 2
    private const val THREE_BYTES = 3
    private const val FOUR_BYTES = 4

    /** Encodes [text] exactly; a lone surrogate keeps its own code unit. */
    fun encode(text: String): ByteArray {
        val out = ByteArray(encodedLength(text))
        var pos = 0
        var i = 0
        while (i < text.length) {
            val cp = codePointAt(text, i)
            i += if (cp > MAX_THREE_BYTE) 2 else 1
            pos = put(out, pos, cp)
        }
        return out
    }

    /** Bytes [encode] will produce, computed without allocating. */
    fun encodedLength(text: String): Int {
        var length = 0
        var i = 0
        while (i < text.length) {
            val cp = codePointAt(text, i)
            i += if (cp > MAX_THREE_BYTE) 2 else 1
            length += sizeOf(cp)
        }
        return length
    }

    /**
     * Decodes [length] bytes from [offset], or returns null when they are
     * not well-formed (a truncated or overlong sequence). Records are
     * CRC-checked before they get here, so null means a bug or a journal
     * written by something else, and the reader reports it as corruption.
     */
    fun decode(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): String? {
        val out = StringBuilder(length)
        var pos = offset
        val end = offset + length
        while (pos < end) {
            val lead = bytes[pos].toInt() and BYTE
            val size = sequenceSize(lead)
            val cp = if (size == 0 || pos + size > end) null else readSequence(bytes, pos, size, lead)
            if (cp == null) return null
            out.appendCodePoint(cp)
            pos += size
        }
        return out.toString()
    }

    private fun codePointAt(
        text: String,
        i: Int,
    ): Int {
        val c = text[i]
        return if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
            Character.toCodePoint(c, text[i + 1])
        } else {
            c.code
        }
    }

    private fun sizeOf(cp: Int): Int =
        when {
            cp <= MAX_ONE_BYTE -> 1
            cp <= MAX_TWO_BYTE -> TWO_BYTES
            cp <= MAX_THREE_BYTE -> THREE_BYTES
            else -> FOUR_BYTES
        }

    private fun put(
        out: ByteArray,
        start: Int,
        cp: Int,
    ): Int {
        val size = sizeOf(cp)
        if (size == 1) {
            out[start] = cp.toByte()
            return start + 1
        }
        val lead =
            when (size) {
                TWO_BYTES -> LEAD_2
                THREE_BYTES -> LEAD_3
                else -> LEAD_4
            }
        var rest = cp
        for (k in size - 1 downTo 1) {
            out[start + k] = (CONTINUATION or (rest and PAYLOAD_6)).toByte()
            rest = rest shr BITS_PER_CONTINUATION
        }
        out[start] = (lead or rest).toByte()
        return start + size
    }

    private fun sequenceSize(lead: Int): Int =
        when {
            lead <= MAX_ONE_BYTE -> 1
            lead and LEAD_3 == LEAD_2 -> TWO_BYTES
            lead and LEAD_4 == LEAD_3 -> THREE_BYTES
            lead and LEAD_INVALID == LEAD_4 -> FOUR_BYTES
            else -> 0
        }

    private fun readSequence(
        bytes: ByteArray,
        pos: Int,
        size: Int,
        lead: Int,
    ): Int? {
        // A multi-byte lead carries 6 - size payload bits; ASCII is all payload.
        var cp = if (size == 1) lead else lead and (BYTE shr (size + 1))
        var continuationsOk = true
        for (k in 1 until size) {
            val b = bytes[pos + k].toInt() and BYTE
            continuationsOk = continuationsOk && b and CONTINUATION_MASK == CONTINUATION
            cp = (cp shl BITS_PER_CONTINUATION) or (b and PAYLOAD_6)
        }
        // Overlong forms and values past Unicode would decode, but never
        // come out of encode(), so they can only mean damage.
        val canonical = continuationsOk && sizeOf(cp) == size && cp <= MAX_CODE_POINT
        return if (canonical) cp else null
    }
}
