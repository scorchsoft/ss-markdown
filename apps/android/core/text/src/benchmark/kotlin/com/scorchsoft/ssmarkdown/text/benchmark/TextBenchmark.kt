package com.scorchsoft.ssmarkdown.text.benchmark

import com.scorchsoft.ssmarkdown.text.DecodeResult
import com.scorchsoft.ssmarkdown.text.TextDocument
import com.scorchsoft.ssmarkdown.text.Utf8Decoder
import com.scorchsoft.ssmarkdown.text.Utf8Encoder
import java.io.OutputStream
import java.util.Random
import kotlin.system.measureNanoTime

/**
 * Coarse timings for the text engine at 1, 20 and 100 MB of synthetic
 * Markdown: load (decode + build), 10k random edits, 100k line lookups,
 * a snapshot, a save, and compaction. Run with
 * `gradle -p apps/android :core:text:benchmark`.
 *
 * Host JVM numbers, not phone numbers: they show how cost scales with file
 * size (the claim is that edits and lookups do not), not what a Pixel does.
 */
public fun main() {
    println(
        "SS Markdown :core:text benchmark - JVM ${System.getProperty(
            "java.version",
        )}, max heap ${mb(Runtime.getRuntime().maxMemory())} MB",
    )
    // Warm the JIT on a small file so the first row is not mostly compilation.
    repeat(WARMUP_ROUNDS) { measure(megabytes = 1, report = false) }
    println("size    load      edits(10k)  lookups(100k)  snapshot  save      compact   pieces  heap(doc)")
    for (size in SIZES_MB) measure(size, report = true)
}

private val SIZES_MB = listOf(1, 20, 100)
private const val WARMUP_ROUNDS = 3
private const val EDITS = 10_000
private const val LOOKUPS = 100_000
private const val BYTES_PER_MB = 1_048_576
private const val NANOS_PER_MILLI = 1_000_000.0

private fun measure(
    megabytes: Int,
    report: Boolean,
) {
    val (decoded, load) = load(megabytes)
    val document = TextDocument.from(decoded)
    val heap = if (report) liveHeapMegabytes() else 0L
    val text = document.text
    val random = Random(megabytes.toLong())
    val edits = measureNanoTime { repeat(EDITS) { randomEdit(document, random) } }
    var sink = 0
    val lookups =
        measureNanoTime {
            repeat(LOOKUPS) {
                val line = random.nextInt(text.lineCount)
                sink += text.lineStartOffset(line) + text.positionAt(random.nextInt(text.length + 1)).line
                if (it % 10 == 0) sink += text.lineContent(line).length
            }
        }
    val pieces = text.pieceCount
    val snapshot = measureNanoTime { sink += text.snapshot().lineCount }
    val save = measureNanoTime { Utf8Encoder.write(text.snapshot(), document.format, NullOutput) }
    val compact = measureNanoTime { text.compact() }
    if (report) {
        println(
            "%3d MB  %6.0f ms  %7.1f ms  %9.1f ms  %6.2f ms  %6.0f ms  %6.0f ms  %6d  %4d MB".format(
                megabytes,
                ms(load),
                ms(edits),
                ms(lookups),
                ms(snapshot),
                ms(save),
                ms(compact),
                pieces,
                heap,
            ),
        )
    }
    if (sink == Int.MIN_VALUE) println(sink) // keeps the JIT from discarding the lookups
}

/** A mix of what an editor sees: typing runs, backspaces, pastes and cuts. */
private fun randomEdit(
    document: TextDocument,
    random: Random,
) {
    val length = document.text.length
    val offset = random.nextInt(length + 1)
    when (random.nextInt(10)) {
        in 0..5 -> document.replace(offset, 0, "x")
        in 6..7 -> document.replace(offset, minOf(1, length - offset), "")
        8 -> document.replace(offset, 0, "pasted line\n".repeat(1 + random.nextInt(20)))
        else -> document.replace(offset, minOf(random.nextInt(500), length - offset), "")
    }
}

/** Decodes a synthetic file and returns it with the decode time; the source bytes die here. */
private fun load(megabytes: Int): Pair<DecodeResult.Decoded, Long> {
    val bytes = SyntheticMarkdown.generate(megabytes * BYTES_PER_MB, seed = megabytes.toLong())
    lateinit var decoded: DecodeResult.Decoded
    val nanos = measureNanoTime { decoded = Utf8Decoder.decode(bytes) as DecodeResult.Decoded }
    return decoded to nanos
}

/** Heap in use after a GC: with only the document alive, roughly what it costs. */
private fun liveHeapMegabytes(): Long {
    val runtime = Runtime.getRuntime()
    System.gc()
    return mb(runtime.totalMemory() - runtime.freeMemory())
}

private fun ms(nanos: Long): Double = nanos / NANOS_PER_MILLI

private fun mb(bytes: Long): Long = bytes / BYTES_PER_MB

private object NullOutput : OutputStream() {
    override fun write(b: Int) = Unit

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) = Unit
}

/** Plausible Markdown: headings, paragraphs, lists, fenced code, some CRLF-free UTF-8. */
private object SyntheticMarkdown {
    private val blocks =
        listOf(
            "# Heading for a section\n\n",
            "A paragraph of ordinary prose, with *emphasis*, `code` and a [link](https://example.com).\n",
            "- a list item\n- another item with **bold**\n",
            "```kotlin\nval answer = 42\n```\n\n",
            "> A quote — with an em dash and naïve café text, plus an emoji 😀.\n\n",
            "| a | b |\n|---|---|\n| 1 | 2 |\n\n",
        )

    fun generate(
        targetBytes: Int,
        seed: Long,
    ): ByteArray {
        val random = Random(seed)
        val out = StringBuilder(targetBytes)
        while (out.length < targetBytes) out.append(blocks[random.nextInt(blocks.size)])
        return out.toString().toByteArray()
    }
}
