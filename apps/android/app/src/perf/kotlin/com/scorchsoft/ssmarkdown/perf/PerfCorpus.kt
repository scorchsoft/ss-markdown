package com.scorchsoft.ssmarkdown.perf

import java.io.Writer

/**
 * Deterministic Markdown of a requested size, for the benchmarks: the
 * same bytes on every run and every device, so a change in a number is a
 * change in the app. Each block mixes what real notes hold (headings,
 * prose with inline marks and links, lists, tasks, a fenced code block, a
 * table, a quote), so the tokenizer, the outline and the Preview all have
 * real work, at about 90 characters a line.
 */
internal object PerfCorpus {
    /** Writes blocks to [out] until at least [bytes] (UTF-8, all ASCII) are written. */
    fun write(
        out: Writer,
        bytes: Long,
    ) {
        var written = 0L
        var block = 0
        while (written < bytes) {
            val text = block(block++)
            out.write(text)
            written += text.length
        }
    }

    private fun block(n: Int): String =
        buildString {
            append("## Section ").append(n).append(": notes on item ").append(n % 97).append("\n\n")
            append("This paragraph has **bold**, *italic*, `inline code` and a [link](https://example.com/")
            append(n).append(") so every inline rule runs. Line ").append(n).append(" of the corpus.\n")
            append("A second line of prose keeps the paragraph long enough to wrap on a phone screen.\n\n")
            append("- First point about ").append(n).append("\n")
            append("- Second point, with `code`\n")
            append("  - A nested point\n")
            append("- [ ] An open task\n")
            append("- [x] A done task\n\n")
            append("```kotlin\n")
            append("fun block").append(n).append("(x: Int): Int = x * ").append(n % 13).append(" + 1\n")
            append("val label = \"section ").append(n).append("\"\n")
            append("```\n\n")
            append("| Key | Value | Notes |\n|---|---|---|\n")
            append("| a").append(n).append(" | ").append(n * 7 % 101).append(" | plain cell |\n")
            append("| b").append(n).append(" | ").append(n * 3 % 89).append(" | *emphasis* |\n\n")
            append("> A quote closes the block ").append(n).append(".\n\n")
        }
}
