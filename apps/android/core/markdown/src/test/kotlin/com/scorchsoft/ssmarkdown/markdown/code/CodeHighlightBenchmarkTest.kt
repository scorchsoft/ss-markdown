package com.scorchsoft.ssmarkdown.markdown.code

import com.scorchsoft.ssmarkdown.markdown.IncrementalTokenizer
import com.scorchsoft.ssmarkdown.markdown.LineTokenizer
import com.scorchsoft.ssmarkdown.markdown.TestDoc
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Random

/**
 * Coarse host-JVM timings for fenced-code highlighting, against the
 * quality-bar §4 budgets (docs/performance-techniques.md, "Code
 * highlighting"):
 *
 * - a 20 MB document made of code blocks in many languages: a full
 *   tokenise with and without highlighting (time and retained memory);
 * - typing inside a 5,000-line code block: lines re-lexed and time per keystroke;
 * - typing a block-comment opener at the top of that block: the frame's work (8 ms budget),
 *   the idle pass up to the editor's 100k-char lookahead, and the whole
 *   block; then the lines far below, lexed only when they are drawn.
 *
 * Opt-in like [com.scorchsoft.ssmarkdown.markdown.TokenizerBenchmarkTest]:
 * `gradle -p apps/android :core:markdown:test -Pssmarkdown.heavyTests=true --tests '*CodeHighlightBenchmark*' -i`.
 * A phone is several times slower; the point is how cost scales.
 */
class CodeHighlightBenchmarkTest {
    @Test
    fun `highlight a 20 MB document of code blocks`() {
        assumeTrue(System.getProperty("ssmarkdown.heavyTests") == "true")
        val doc = TestDoc(corpus(TARGET_CHARS))
        println("code corpus: ${doc.lineCount} lines, ${doc.text.length / MB} MB")
        repeat(2) {
            full(doc, highlight = true)
            full(doc, highlight = false)
        } // JIT warm-up
        val plainNs = timed { full(doc, highlight = false) }
        val codeNs = timed { full(doc, highlight = true) }
        println(
            "full tokenise: ${plainNs / MS} ms plain, ${codeNs / MS} ms highlighted " +
                "(+${(codeNs - plainNs) * 100 / plainNs}%, ${doc.lineCount * 1_000L / (codeNs / MS)} lines/s)",
        )
        retained(doc)
        lexOnly(doc)
    }

    @Test
    fun `a 5,000-line code block, typing and a comment opened at its top`() {
        assumeTrue(System.getProperty("ssmarkdown.heavyTests") == "true")
        repeat(WARM_UP) { bigBlock(report = false) } // JIT warm-up
        bigBlock(report = true)
    }

    private fun full(
        doc: TestDoc,
        highlight: Boolean,
    ): IncrementalTokenizer =
        IncrementalTokenizer(doc, LineTokenizer(highlight)).also { it.continueInIdle(Long.MAX_VALUE / 4) }

    private fun retained(doc: TestDoc) {
        for (highlight in listOf(false, true)) {
            val before = usedHeap()
            val cache = full(doc, highlight)
            val after = usedHeap()
            println(
                "retained (highlight=$highlight): ${(after - before) / MB} MB measured, " +
                    "${cache.retainedBytesEstimate() / MB} MB estimated",
            )
        }
    }

    /** The per-line cost of the lexers alone: every line of the corpus through one LineTokenizer. */
    private fun lexOnly(doc: TestDoc) {
        val tokenizer = LineTokenizer()
        var state = com.scorchsoft.ssmarkdown.markdown.LineState.DOCUMENT_START
        val ns =
            timed {
                for (line in doc.lines) state = tokenizer.tokenize(line, state).endState
            }
        println("one pass, highlighted: ${ns / doc.lineCount} ns/line")
    }

    private fun bigBlock(report: Boolean) {
        val println: (String) -> Unit = if (report) ::println else { _ -> }
        val random = Random(7)
        val body = (0 until BLOCK_LINES).joinToString("\n") { C_LINES[random.nextInt(C_LINES.size)] }
        val doc = TestDoc("# Big block\n\n```c\n$body\n```\n\nAfter.\n")
        val cache = full(doc, highlight = true)
        val first = 3

        // Typing inside the block: one char per edit, re-lexed to the viewport, then idle.
        var touched = 0L
        val typingNs =
            timed {
                repeat(EDITS) {
                    val line = first + random.nextInt(BLOCK_LINES)
                    val text = doc.lines[line]
                    val col = random.nextInt(text.length + 1)
                    doc.lines[line] = text.substring(0, col) + "x" + text.substring(col)
                    cache.invalidate(line, 1, 1)
                    val before = cache.linesTokenized
                    cache.tokenizeUntil(minOf(line + VIEWPORT, doc.lineCount - 1), BUDGET_NS)
                    cache.continueInIdle(Long.MAX_VALUE / 4)
                    touched += cache.linesTokenized - before
                }
            }
        println(
            "typing in a $BLOCK_LINES-line block: ${typingNs / EDITS / 1_000} µs/keystroke, " +
                "${touched.toDouble() / EDITS} lines re-lexed per keystroke",
        )

        // `/*` at the top of the block.
        doc.lines[first] = "/* " + doc.lines[first]
        cache.invalidate(first, 1, 1)
        var before = cache.linesTokenized
        val frameNs = timed { cache.tokenizeUntil(first + VIEWPORT, BUDGET_NS) }
        val frameLines = cache.linesTokenized - before
        val lookahead = lineAfterChars(doc, first + VIEWPORT, LOOKAHEAD_CHARS)
        before = cache.linesTokenized
        var slices = 0
        val lookaheadNs = timed { while (!cache.continueInIdle(IDLE_NS, limitLine = lookahead)) slices++ }
        val lookaheadLines = cache.linesTokenized - before
        val farLine = first + BLOCK_LINES - VIEWPORT
        val shownBefore = cache.linesTokenizedForDisplay
        val farNs = timed { for (line in farLine until farLine + VIEWPORT) cache.resultAt(line) }
        println(
            "`/*` at the top: first frame ${frameNs / 1_000} µs ($frameLines lines); " +
                "idle to the 100k-char lookahead ${lookaheadNs / MS} ms in ${slices + 1} slices of 4 ms " +
                "($lookaheadLines lines); a viewport at the block's end before the pass gets there: " +
                "${farNs / 1_000} µs (${cache.linesTokenizedForDisplay - shownBefore} lines guessed)",
        )
        before = cache.linesTokenized
        val restNs = timed { while (!cache.continueInIdle(IDLE_NS)) Unit }
        println("rest of the block in idle slices: ${restNs / MS} ms (${cache.linesTokenized - before} lines)")
    }

    private fun lineAfterChars(
        doc: TestDoc,
        from: Int,
        chars: Int,
    ): Int {
        var line = from
        var count = 0
        while (line < doc.lineCount - 1 && count < chars) count += doc.lines[line++].length + 1
        return line
    }

    private fun corpus(chars: Int): String {
        val random = Random(42)
        val out = StringBuilder(chars + 4_096)
        while (out.length < chars) {
            val (language, sample) = SAMPLES[random.nextInt(SAMPLES.size)]
            out.append("Some prose before the block.\n\n```").append(language).append('\n')
            repeat(1 + random.nextInt(4)) { out.append(sample).append('\n') }
            out.append("```\n\n")
        }
        return out.toString()
    }

    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        repeat(3) {
            System.gc()
            Thread.sleep(50)
        }
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private inline fun timed(block: () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return System.nanoTime() - started
    }

    private companion object {
        const val TARGET_CHARS = 20 * 1_048_576
        const val MB = 1_048_576
        const val MS = 1_000_000L
        const val BUDGET_NS = 8_000_000L
        const val IDLE_NS = 4_000_000L
        const val VIEWPORT = 60
        const val EDITS = 2_000
        const val BLOCK_LINES = 5_000
        const val LOOKAHEAD_CHARS = 100_000
        const val WARM_UP = 3

        val C_LINES =
            listOf(
                "int main(int argc, char **argv) {",
                "    printf(\"%d items\\n\", count); // report",
                "    for (size_t i = 0; i < n; i++) total += values[i] * 2.5;",
                "#include <stdio.h>",
                "    return 0;",
                "}",
            )

        val SAMPLES =
            listOf(
                "python" to
                    "@cached\ndef area(r: float) -> float:\n    \"\"\"Area of a circle.\"\"\"\n" +
                    "    return 3.14159 * r ** 2  # pi r squared\n",
                "ts" to
                    "export interface User { name: string; age?: number }\n" +
                    "const greet = (u: User) => `Hi \${u.name}, you are \${u.age ?? 0}`; // ok\n",
                "java" to
                    "@Override\npublic Map<String, List<Integer>> load(int n) throws IOException {\n" +
                    "    return Collections.emptyMap(); /* none */\n}\n",
                "c" to
                    "/* sum */\nint sum(const int *v, size_t n) {\n" +
                    "    int t = 0; for (size_t i = 0; i < n; i++) t += v[i];\n}\n",
                "php" to "<?php\nfunction total(\$items) { return array_sum(\$items) * 1.2; } # vat\n",
                "rust" to "#[derive(Debug)]\nfn main() { let v: Vec<u32> = vec![1, 2]; println!(\"{:?}\", v); }\n",
                "go" to "func main() {\n\tfmt.Println(`raw`, 42) // done\n}\n",
                "json" to "{ \"name\": \"ss\", \"version\": 2, \"ok\": true, \"tags\": [\"a\", null] }\n",
                "yaml" to "name: ci\non: [push]\njobs:\n  build:\n    runs-on: ubuntu-latest # runner\n",
                "html" to "<div class=\"card\" id='x'>\n  <a href=\"/p\">Link</a> <!-- note -->\n</div>\n",
                "css" to ".card > p:hover { color: #333; margin: 0 1.5em !important; }\n",
                "bash" to "if [ -n \"\$HOME\" ]; then echo \"\${PATH}\" | grep bin; fi # path\n",
                "sql" to "SELECT id, COUNT(*) FROM orders WHERE total > 10.5 GROUP BY id; -- report\n",
                "kotlin" to "data class Point(val x: Int, val y: Int) { fun len() = sqrt(x * x + y * y.0) }\n",
            )
    }
}
