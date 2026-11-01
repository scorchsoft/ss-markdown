package com.scorchsoft.ssmarkdown.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

/**
 * The cache's one promise: after any sequence of edits, interleaved with
 * any pattern of budgeted and heuristic reads, finishing the pass gives
 * exactly what tokenising the final text from scratch gives, line by line
 * (tokens, end state, kind, level, role, label), and the same outline and
 * references.
 *
 * Documents are built from Markdown's state-changing fragments (fences,
 * front matter, `$$`, HTML blocks, setext underlines, tables, quotes,
 * lists) and edited at random offsets, so edits routinely open and close
 * blocks mid-document. Seeded, so a failure replays. Heavy mode
 * (`-Pssmarkdown.heavyTests=true`) runs ten times the seeds.
 */
class IncrementalEquivalencePropertyTest {
    private val heavy = System.getProperty("ssmarkdown.heavyTests") == "true"
    private val seeds = if (heavy) 5_000 else 500

    private val fragments =
        listOf(
            "# Heading",
            "## Sub *em*",
            "Para text with **bold**",
            "more words",
            "",
            "",
            "```",
            "```kotlin",
            "~~~",
            "---",
            "===",
            "$$",
            "<div>",
            "<!--",
            "-->",
            "</div>",
            "> quote",
            "> > deep",
            "- item",
            "  - nested",
            "1. one",
            "2) two",
            "- [ ] task",
            "| a | b |",
            "|---|---|",
            "1 | 2",
            "[ref]: /url \"t\"",
            "[^n]: note",
            "    indented",
            "text `code` [l](u) <b>x</b>",
            "+++",
            "***",
            "Setext",
            "\$x\$ and \$\$y\$\$",
            "\\# escaped",
        )
    private val alphabet = "#*_`~>-=|\$[]()!<: x\n1."

    @Test
    fun `incremental tokens equal a full re-tokenise after random edits`() {
        for (seed in 0 until seeds) runSeed(seed.toLong(), IncrementalTokenizer.DEFAULT_TOKEN_CACHE_LINES)
    }

    @Test
    fun `a token cache smaller than the document changes no token (PERF-9)`() {
        // Four lines collide and evict constantly; none keeps every token re-derived from stored states.
        for (seed in 0 until seeds) {
            runSeed(seed.toLong(), cacheLines = 4)
            runSeed(seed.toLong(), cacheLines = 0)
        }
    }

    private fun runSeed(
        seed: Long,
        cacheLines: Int,
    ) {
        val random = Random(seed)
        val doc = TestDoc(List(1 + random.nextInt(40)) { fragments[random.nextInt(fragments.size)] }.joinToString("\n"))
        val cache = IncrementalTokenizer(doc, tokenCacheLines = cacheLines)
        repeat(1 + random.nextInt(25)) {
            randomEdit(random, doc, cache)
            when (random.nextInt(4)) {
                0 -> {
                    cache.tokenizeUntil(
                        random.nextInt(doc.lineCount),
                        budgetNanos = random.nextInt(5).toLong(),
                        clock = SteppingClock(1),
                    )
                }

                1 -> {
                    cache.continueInIdle(budgetNanos = random.nextInt(20).toLong(), clock = SteppingClock(1))
                }

                2 -> {
                    cache.resultAt(random.nextInt(doc.lineCount))
                }

                else -> {
                    Unit
                }
            }
        }
        cache.continueInIdle(Long.MAX_VALUE / 4)
        assertTrue(cache.isComplete)
        assertMatchesScratch(seed, doc, cache)
    }

    private fun randomEdit(
        random: Random,
        doc: TestDoc,
        cache: IncrementalTokenizer,
    ) {
        val length = doc.text.length
        val start = random.nextInt(length + 1)
        val end = minOf(length, start + if (random.nextInt(3) == 0) random.nextInt(30) else 0)
        val replacement =
            when (random.nextInt(3)) {
                0 -> "\n" + fragments[random.nextInt(fragments.size)]
                1 -> fragments[random.nextInt(fragments.size)] + "\n"
                else -> String(CharArray(random.nextInt(4)) { alphabet[random.nextInt(alphabet.length)] })
            }
        doc.edit(cache, start, end, replacement)
    }

    private fun assertMatchesScratch(
        seed: Long,
        doc: TestDoc,
        cache: IncrementalTokenizer,
    ) {
        val expected = tokenizeFromScratch(doc.lines)
        for (line in expected.indices) {
            val actual = cache.resultAt(line)
            if (!actual.sameAs(expected[line])) {
                fail(
                    "seed $seed line $line \"${doc.lines[line]}\"\n" +
                        "expected ${expected[line]}\nactual   $actual\n---\n${doc.text}",
                )
            }
        }
        val headings =
            expected
                .withIndex()
                .filter { it.value.kind == LineKind.HEADING }
                .map {
                    Heading(
                        it.index,
                        it.value.headingLevel,
                        Outline.headingText(doc.lines[it.index], it.value.tokens),
                    )
                }
        assertEquals("outline, seed $seed", headings, cache.outline.headings())
        val labels = expected.mapNotNull { it.definedLabel }.distinct()
        assertEquals("references, seed $seed", labels, cache.references.labels())
    }
}
