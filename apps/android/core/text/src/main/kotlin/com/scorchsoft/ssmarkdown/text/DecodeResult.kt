package com.scorchsoft.ssmarkdown.text

import java.io.IOException

/**
 * The outcome of reading a file as text. Errors are values here
 * (docs/engineering.md): a file that is not valid UTF-8 is a normal thing
 * to meet, and the UI must explain it rather than open a silently
 * damaged copy that would then be saved over the original.
 */
public sealed interface DecodeResult {
    /** The file decoded cleanly. */
    public class Decoded(
        /** The document text, line breaks normalised to `'\n'`. */
        public val text: PieceTree,
        /** What to write back on save. */
        public val format: TextFileFormat,
    ) : DecodeResult

    /** The bytes are not valid UTF-8; [byteOffset] is where the first bad sequence starts. */
    public data class Malformed(
        /** Offset in the file, counting any BOM. */
        public val byteOffset: Long,
    ) : DecodeResult

    /** The file declares another encoding through its BOM (UTF-16 or UTF-32). */
    public data class UnsupportedEncoding(
        /** The encoding's canonical name, e.g. `UTF-16LE`. */
        public val encoding: String,
    ) : DecodeResult

    /** Reading the stream failed part way. */
    public class ReadFailed(
        /** What the stream threw. */
        public val cause: IOException,
    ) : DecodeResult
}
