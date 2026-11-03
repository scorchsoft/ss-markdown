package com.scorchsoft.ssmarkdown.markdown.code

import com.scorchsoft.ssmarkdown.markdown.LineState
import com.scorchsoft.ssmarkdown.markdown.LineTokenizer
import com.scorchsoft.ssmarkdown.markdown.TokenKind
import com.scorchsoft.ssmarkdown.markdown.Tokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** A highlighted line: its text and its `CODE_*` runs as `KIND:text`, e.g. `KEYWORD:def`. */
internal class CodeLine(
    val text: String,
    val runs: List<String>,
    val end: LineState,
)

/**
 * Tokenises [body] as the inside of a ```` ```[language] ```` fence and
 * returns each body line's code runs (the fence lines themselves are left
 * out). Runs of non-code kinds (plain code text) are omitted.
 */
internal fun highlight(
    language: String,
    body: String,
    tokenizer: LineTokenizer = LineTokenizer(),
): List<CodeLine> {
    val lines = ("```$language\n$body").split('\n')
    var state = LineState.DOCUMENT_START
    val out = ArrayList<CodeLine>()
    for ((index, line) in lines.withIndex()) {
        val result = tokenizer.tokenize(line, state)
        if (index > 0) out += CodeLine(line, runsOf(line, result.tokens), result.endState)
        state = result.endState
    }
    return out
}

internal fun runsOf(
    line: String,
    tokens: LongArray,
): List<String> =
    tokens
        .filter { Tokens.kind(it).name.startsWith("CODE_") && Tokens.kind(it) != TokenKind.CODE_BLOCK_TEXT }
        .map { "${Tokens.kind(it).name.removePrefix("CODE_")}:${line.substring(Tokens.start(it), Tokens.end(it))}" }

/** All runs of [language] code, flattened, for "contains" checks. */
internal fun runs(
    language: String,
    body: String,
): List<String> = highlight(language, body).flatMap { it.runs }

/** Asserts that every one of [expected] is among the runs of [body]. */
internal fun assertRuns(
    language: String,
    body: String,
    vararg expected: String,
) {
    val actual = runs(language, body)
    for (run in expected) assertTrue("expected $run in $actual", run in actual)
}

/** Asserts the exact runs of each line. */
internal fun assertLines(
    language: String,
    body: String,
    vararg expected: List<String>,
) {
    assertEquals(expected.toList(), highlight(language, body).map { it.runs })
}
