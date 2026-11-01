package com.scorchsoft.ssmarkdown.editor

import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.markdown.edit.EditCommand
import com.scorchsoft.ssmarkdown.markdown.edit.FenceRole
import com.scorchsoft.ssmarkdown.markdown.edit.FenceStates
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The view's fence lookup agrees with a fresh scan, and commands use it. */
@RunWith(RobolectricTestRunner::class)
class TokenizerFencesTest {
    @Test
    fun `fence roles from the view's tokenizer match a fresh scan`() {
        val source = "# Title\n```kotlin\n# not a heading\n```\n- item\n~~~\nopen to the end\n"
        val view = MarkdownCodeView(ApplicationProvider.getApplicationContext())
        view.document = TextDocument(PieceTree.of(source))
        val fromView = view.fenceStates()
        val scanned = FenceStates.scan(source)
        var start = 0
        while (start < source.length) {
            assertEquals("line at $start", scanned.roleAt(start), fromView.roleAt(start))
            start = source.indexOf('\n', start) + 1
            if (start == 0) break
        }
        assertEquals(FenceRole.INSIDE, fromView.roleAt(source.indexOf("# not")))

        val caret = source.indexOf("not")
        val result =
            EditCommand
                .SetHeading(
                    2,
                ).apply(source, TextSelection.caret(caret), fences = fromView, style = defaultStyle())
        assertTrue(result.isNoOp)
    }

    private fun defaultStyle() = com.scorchsoft.ssmarkdown.markdown.edit.DocumentStyle.DEFAULT
}
