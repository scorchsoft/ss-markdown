package com.scorchsoft.ssmarkdown.markdown.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Tests write text with the selection drawn in: `‸` is a caret, `«` and
 * `»` bracket a selection. Neither char appears in real test documents.
 */
const val CARET = '‸'
const val SEL_START = '«'
const val SEL_END = '»'

data class Marked(
    val text: String,
    val selection: TextSelection,
)

fun marked(source: String): Marked {
    val caret = source.indexOf(CARET)
    if (caret >= 0) return Marked(source.removeRange(caret, caret + 1), TextSelection.caret(caret))
    val start = source.indexOf(SEL_START)
    val end = source.indexOf(SEL_END)
    require(start in 0 until end) { "test text needs ‸ or «…»: $source" }
    val text = source.removeRange(end, end + 1).removeRange(start, start + 1)
    return Marked(text, TextSelection(start, end - 1))
}

fun render(
    text: String,
    selection: TextSelection,
): String =
    if (selection.isCaret) {
        StringBuilder(text).insert(selection.start, CARET).toString()
    } else {
        StringBuilder(text).insert(selection.end, SEL_END).insert(selection.start, SEL_START).toString()
    }

/** One row of a table-driven test: [before] → [after], both with selection marks. */
data class Case(
    val before: String,
    val after: String,
    val style: DocumentStyle = DocumentStyle.DEFAULT,
)

/** Runs [command] over every case and reports every failure at once, so one run shows the whole table. */
fun assertCases(
    command: EditCommand,
    cases: List<Case>,
) {
    val failures =
        cases.mapNotNull { case ->
            val actual = run(command, case.before, case.style)
            if (actual ==
                case.after
            ) {
                null
            } else {
                "  ${show(case.before)}\n    want ${show(case.after)}\n    got  ${show(actual)}"
            }
        }
    assertTrue("$command failed ${failures.size} of ${cases.size}:\n" + failures.joinToString("\n"), failures.isEmpty())
}

/** Applies [command] to marked [before] and returns the marked result, checking the result's invariants on the way. */
fun run(
    command: EditCommand,
    before: String,
    style: DocumentStyle = DocumentStyle.DEFAULT,
): String {
    val (text, selection) = marked(before)
    val result = command.apply(text, selection, style)
    val after = result.applyTo(text)
    assertInvariants(text, result, after)
    return render(after, result.selection)
}

/** Ops are sorted high to low, never overlap, hold what the text held, and the selection fits the new text. */
fun assertInvariants(
    text: CharSequence,
    result: EditResult,
    after: String,
) {
    var limit = text.length
    for (op in result.ops) {
        val end = op.offset + op.deleted.length
        assertTrue("op $op overlaps the next one", end <= limit)
        assertEquals("op deleted text", text.subSequence(op.offset, end).toString(), op.deleted.toString())
        val minimal =
            op.deleted.isEmpty() || op.inserted.isEmpty() ||
                (op.deleted.first() != op.inserted.first() && op.deleted.last() != op.inserted.last())
        assertTrue("op is not minimal: $op", minimal)
        limit = op.offset
    }
    assertTrue("selection ${result.selection} outside ${after.length}", result.selection.end <= after.length)
}

private fun show(s: String): String = '"' + s.replace("\n", "⏎") + '"'
