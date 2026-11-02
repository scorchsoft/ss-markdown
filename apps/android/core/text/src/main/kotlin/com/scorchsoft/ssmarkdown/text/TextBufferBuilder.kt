package com.scorchsoft.ssmarkdown.text

/**
 * Builds a [PieceTree] from text that arrives in pieces — straight from a
 * `CharsetDecoder` — without ever holding the whole document as one String
 * (docs/robustness.md: "files are decoded straight into chunks").
 *
 * On the way in it turns CRLF and lone CR into `'\n'` and records which
 * each break was, so the file can be written back exactly. A CRLF split
 * across two calls to [append] is still one break.
 *
 * Single use: call [build] once.
 */
public class TextBufferBuilder {
    private val packer = ChunkPacker()
    private val endings = LineEndingDetector()
    private var pendingCarriageReturn = false
    private var built = false

    /** Feeds `chars[offset, offset + count)`. */
    public fun append(
        chars: CharArray,
        offset: Int = 0,
        count: Int = chars.size - offset,
    ) {
        check(!built) { "TextBufferBuilder is single use" }
        for (i in offset until offset + count) accept(chars[i])
    }

    /** Feeds [text]; convenient for tests and small documents. */
    public fun append(text: CharSequence) {
        check(!built) { "TextBufferBuilder is single use" }
        for (i in text.indices) accept(text[i])
    }

    /** Finishes the text and returns it with the line breaks it had. */
    public fun build(): BuiltText {
        check(!built) { "TextBufferBuilder is single use" }
        if (pendingCarriageReturn) breakLine(LineEnding.CR)
        built = true
        return BuiltText(PieceTree(packer.finish()), endings.finish())
    }

    private fun accept(c: Char) {
        if (pendingCarriageReturn) {
            pendingCarriageReturn = false
            if (c == '\n') {
                breakLine(LineEnding.CRLF)
                return
            }
            breakLine(LineEnding.CR)
        }
        when (c) {
            '\r' -> pendingCarriageReturn = true
            '\n' -> breakLine(LineEnding.LF)
            else -> packer.put(c)
        }
    }

    private fun breakLine(ending: LineEnding) {
        endings.record(ending)
        packer.put('\n')
    }
}

/** What [TextBufferBuilder.build] produces: the normalised text and the breaks it came with. */
public class BuiltText(
    /** The document, with `'\n'` as its only line break. */
    public val text: PieceTree,
    /** The original breaks, to re-emit on save. */
    public val lineEndings: LineEndings,
)
