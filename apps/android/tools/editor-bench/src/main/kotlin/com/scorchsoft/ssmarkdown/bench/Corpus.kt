@file:Suppress("MagicNumber") // A corpus is literal data: sizes, word counts and odds.

package com.scorchsoft.ssmarkdown.bench

import java.io.File
import java.io.Writer
import kotlin.random.Random

/** One mebibyte of ASCII chars. File level: enum entries cannot read their own companion. */
private const val MIB = 1L shl 20

/**
 * The documents every engine opens. Sizes are in chars, which equal bytes
 * here because the corpus is ASCII. The multi-line corpora are realistic
 * Markdown with a long line (an inline base64 image, 5–20K chars) every
 * few sections; the single-line ones are the hostile case from
 * robustness.md ("a single 5 MB line").
 */
enum class Corpus(
    val label: String,
    val chars: Long,
    val singleLine: Boolean,
) {
    MB_1("1 MB", MIB, singleLine = false),
    MB_20("20 MB", 20 * MIB, singleLine = false),
    MB_100("100 MB", 100 * MIB, singleLine = false),
    LINE_1MB("1 MB one line", MIB, singleLine = true),
    LINE_5MB("5 MB one line", 5 * MIB, singleLine = true),
    ;

    /**
     * The corpus as a file in [cacheDir], generated once and reused: the same
     * bytes for every engine and every run. Deterministic, so two phones
     * benchmark identical text.
     */
    fun file(cacheDir: File): File {
        val file = File(cacheDir, "corpus-v$FORMAT_VERSION-$name.md")
        if (file.length() != chars) {
            val partial = File(cacheDir, "${file.name}.part")
            partial.bufferedWriter(Charsets.US_ASCII, BUFFER).use { out ->
                if (singleLine) {
                    CorpusWriter(
                        out,
                        Random(ordinal),
                    ).singleLine(chars)
                } else {
                    CorpusWriter(out, Random(ordinal)).markdown(chars)
                }
            }
            check(partial.renameTo(file)) { "could not move ${partial.name} into place" }
        }
        return file
    }

    private companion object {
        const val BUFFER = 1 shl 16

        /** Bump when the generator changes, so stale corpora are regenerated. */
        const val FORMAT_VERSION = 1
    }
}

/** Writes exactly the requested number of chars of synthetic Markdown. */
private class CorpusWriter(
    private val out: Writer,
    private val random: Random,
) {
    private var written = 0L
    private var limit = 0L

    fun singleLine(chars: Long) {
        limit = chars
        write("![inline](data:image/png;base64,")
        while (written < limit) write(base64(BASE64_RUN))
    }

    fun markdown(chars: Long) {
        limit = chars
        var section = 0
        while (written < limit) {
            section++
            write("# Section $section\n\n")
            paragraph()
            list()
            if (section % CODE_EVERY == 0) codeFence()
            if (section % TABLE_EVERY == 0) table()
            if (section % LONG_LINE_EVERY == 0) {
                write("![figure $section](data:image/png;base64,")
                write(base64(random.nextInt(LONG_LINE_MIN, LONG_LINE_MAX)))
                write(")\n\n")
            }
        }
    }

    private fun paragraph() {
        repeat(random.nextInt(PARAGRAPH_MIN, PARAGRAPH_MAX)) { write(sentenceLine() + "\n") }
        write("\n")
    }

    private fun list() {
        repeat(random.nextInt(LIST_MIN, LIST_MAX)) { write("- " + words(random.nextInt(WORDS_MIN, WORDS_MAX)) + "\n") }
        write("\n")
    }

    private fun codeFence() {
        write("```kotlin\n")
        repeat(CODE_LINES) { write("val value$it = compute(\"${words(2)}\", $it) // ${words(3)}\n") }
        write("```\n\n")
    }

    private fun table() {
        write("| Name | Value | Notes |\n| --- | ---: | --- |\n")
        repeat(TABLE_ROWS) { write("| ${words(1)} | ${random.nextInt(TABLE_VALUE_MAX)} | ${words(4)} |\n") }
        write("\n")
    }

    private fun sentenceLine(): String {
        val line = StringBuilder()
        while (line.length < LINE_TARGET) {
            if (line.isNotEmpty()) line.append(' ')
            val word = WORDS[random.nextInt(WORDS.size)]
            line.append(if (random.nextInt(EMPHASIS_ODDS) == 0) "**$word**" else word)
        }
        return line.toString()
    }

    private fun words(count: Int): String = (1..count).joinToString(" ") { WORDS[random.nextInt(WORDS.size)] }

    private fun base64(count: Int): String =
        buildString(count) {
            repeat(count) { append(BASE64[random.nextInt(BASE64.length)]) }
        }

    /** Writes [text], cut short at the limit so the file is exactly the requested size. */
    private fun write(text: String) {
        val room = limit - written
        if (room <= 0) return
        val part = if (text.length <= room) text else text.substring(0, room.toInt())
        out.write(part)
        written += part.length
    }

    private companion object {
        val WORDS =
            listOf(
                "editor",
                "markdown",
                "piece",
                "tree",
                "calm",
                "fast",
                "save",
                "preview",
                "line",
                "caret",
                "keyboard",
                "scroll",
                "theme",
                "folder",
                "history",
                "journal",
                "anchor",
                "table",
                "diagram",
                "note",
            )
        const val BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        const val BASE64_RUN = 4_096
        const val LINE_TARGET = 72
        const val EMPHASIS_ODDS = 12
        const val PARAGRAPH_MIN = 3
        const val PARAGRAPH_MAX = 8
        const val LIST_MIN = 3
        const val LIST_MAX = 9
        const val WORDS_MIN = 3
        const val WORDS_MAX = 10
        const val CODE_EVERY = 4
        const val CODE_LINES = 10
        const val TABLE_EVERY = 3
        const val TABLE_ROWS = 6
        const val TABLE_VALUE_MAX = 10_000
        const val LONG_LINE_EVERY = 25
        const val LONG_LINE_MIN = 5_000
        const val LONG_LINE_MAX = 20_000
    }
}
