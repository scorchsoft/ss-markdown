package com.scorchsoft.ssmarkdown.history.versions

import com.scorchsoft.ssmarkdown.history.journal.Wtf8
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A backward delta: the edits that turn one version's text (the base,
 * the next newer version) into another (the target, the older one).
 *
 * Wire format, little-endian like the rest of `core/history`:
 * `count u32`, then per op `start u32`, `end u32` (UTF-16 offsets into
 * the base), `length u32` and `length` bytes of WTF-8 replacement text.
 * Ops are sorted and do not overlap, which [apply] checks before it
 * trusts them: a damaged delta is reported, never applied.
 */
internal object LineDelta {
    private const val HEADER_BYTES = 4
    private const val OP_BYTES = 12

    /**
     * The delta from [base] to [target], or null when the diff budget ran
     * out; the caller then stores [target] as a keyframe instead.
     */
    fun encode(
        base: String,
        target: String,
        budget: DiffBudget = DiffBudget(),
    ): EncodedDelta? {
        val baseLines = Lines.of(base)
        val targetLines = Lines.of(target)
        val interner = LineInterner()
        val regions = MyersDiff.diff(interner.intern(baseLines), interner.intern(targetLines), budget) ?: return null
        val bytes = ByteArrayOutputStream(HEADER_BYTES + regions.size * OP_BYTES * 2)
        val out = DataOutputStream(bytes)
        out.writeIntLe(regions.size)
        for (region in regions) {
            val replacement =
                Wtf8.encode(
                    target.substring(targetLines.starts[region.bStart], targetLines.starts[region.bEnd]),
                )
            out.writeIntLe(baseLines.starts[region.aStart])
            out.writeIntLe(baseLines.starts[region.aEnd])
            out.writeIntLe(replacement.size)
            out.write(replacement)
        }
        return EncodedDelta(
            bytes = bytes.toByteArray(),
            baseOnlyLines = regions.sumOf { it.aEnd - it.aStart },
            targetOnlyLines = regions.sumOf { it.bEnd - it.bStart },
        )
    }

    /** Applies [delta] to [base], or returns null when the delta does not fit it. */
    fun apply(
        base: String,
        delta: ByteArray,
    ): String? = runCatching { applyOrThrow(base, ByteBuffer.wrap(delta).order(ByteOrder.LITTLE_ENDIAN)) }.getOrNull()

    @Suppress("ReturnCount") // Each check rejects one way a damaged delta can fail to fit.
    private fun applyOrThrow(
        base: String,
        buffer: ByteBuffer,
    ): String? {
        val count = buffer.int
        if (count < 0 || count.toLong() * OP_BYTES > buffer.remaining()) return null
        val out = StringBuilder(base.length)
        var cursor = 0
        repeat(count) {
            val start = buffer.int
            val end = buffer.int
            val length = buffer.int
            if (start !in cursor..end || end > base.length || length !in 0..buffer.remaining()) return null
            val replacement = Wtf8.decode(buffer.array(), buffer.position(), length) ?: return null
            buffer.position(buffer.position() + length)
            out.append(base, cursor, start).append(replacement)
            cursor = end
        }
        if (buffer.hasRemaining()) return null
        return out.append(base, cursor, base.length).toString()
    }
}

/**
 * A delta plus the line counts its diff found, so recording a version
 * gets its "+n −m" summary without diffing twice.
 *
 * @property bytes the encoded delta.
 * @property baseOnlyLines lines of the base that the delta removes.
 * @property targetOnlyLines lines of the target that the delta inserts.
 */
internal class EncodedDelta(
    val bytes: ByteArray,
    val baseOnlyLines: Int,
    val targetOnlyLines: Int,
)

/** Writes [value] little-endian; `DataOutputStream` alone is big-endian. */
internal fun DataOutputStream.writeIntLe(value: Int) {
    writeInt(Integer.reverseBytes(value))
}

/** Writes [value] little-endian. */
internal fun DataOutputStream.writeLongLe(value: Long) {
    writeLong(java.lang.Long.reverseBytes(value))
}
