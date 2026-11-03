package com.scorchsoft.ssmarkdown.editor

import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.text.EditOp
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextDocument
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * edge-cases "Replace all with many matches": a compound edit through
 * [MarkdownCodeView.applyEdits] is one undo step, and the host hears of it
 * once, not once per op. Replace all of 10,000 matches used to report
 * 10,000 edits and selections, each shifting every find highlight and the
 * tokenizer's marks: eight seconds on the main thread on the host JVM.
 */
@RunWith(RobolectricTestRunner::class)
class ApplyEditsTest {
    private val edits = mutableListOf<LineChange>()
    private val selections = mutableListOf<Pair<Int, Int>>()

    private fun view(text: String) =
        MarkdownCodeView(ApplicationProvider.getApplicationContext()).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, WIDTH, HEIGHT)
            document = TextDocument(PieceTree.of(text))
            listener =
                object : EditorListener {
                    override fun onTextEdited(
                        change: LineChange,
                        version: Long,
                    ) {
                        edits += change
                    }

                    override fun onSelectionChanged(
                        start: Int,
                        end: Int,
                    ) {
                        selections += start to end
                    }
                }
        }

    /** Every "x" in [text] becomes "yy", highest offset first, as replace all builds them. */
    private fun replaceAll(text: String): List<EditOp> =
        text.indices
            .filter { text[it] == 'x' }
            .asReversed()
            .map { EditOp(it, "x", "yy") }

    private fun check(count: Int) {
        val text = "word x\n".repeat(count)
        val v = view(text)
        edits.clear()
        selections.clear()
        v.applyEdits(replaceAll(text), 0, 0)
        val expected = "word yy\n".repeat(count)
        assertEquals(
            expected,
            v.session.document.text
                .toString(),
        )
        assertEquals("edits reported", 1, edits.size)
        assertEquals("selections reported", listOf(0 to 0), selections)
        v.session.undo()
        assertEquals(
            text,
            v.session.document.text
                .toString(),
        )
    }

    @Test
    fun `a few ops are one step and one report`() = check(count = 10)

    @Test
    fun `many ops are one step and one report`() = check(count = 2_000)

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1920
    }
}
