package com.scorchsoft.ssmarkdown.editor

import android.content.res.Configuration
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * edge-cases "Keyboard composing during rotation or toolbar tap": a word
 * the keyboard is still composing is committed, exactly as shown, before a
 * configuration change, a toolbar edit or losing focus, so none of them
 * can drop or duplicate it (robustness.md §5a.4).
 */
@RunWith(RobolectricTestRunner::class)
class ComposingCommitTest {
    private val view =
        MarkdownCodeView(ApplicationProvider.getApplicationContext()).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, WIDTH, HEIGHT)
            document = TextDocument(PieceTree.of("Hello \n"))
        }

    private fun composeWord() {
        view.session.setSelection(6, 6)
        view.session.setComposingText("wor", 1)
        assertTrue(view.session.isComposing)
    }

    private fun text() =
        view.session.document.text
            .toString()

    @Test
    fun `a configuration change commits the composing word`() {
        composeWord()
        view.dispatchConfigurationChanged(Configuration(view.resources.configuration))
        assertFalse(view.session.isComposing)
        assertEquals("Hello wor\n", text())
    }

    @Test
    fun `a toolbar edit commits the composing word, then applies once`() {
        composeWord()
        view.applyEdit(0, 0, "# ")
        assertFalse(view.session.isComposing)
        assertEquals("# Hello wor\n", text())
    }

    @Test
    fun `losing focus commits the composing word`() {
        composeWord()
        view.requestFocus()
        view.clearFocus()
        assertFalse(view.session.isComposing)
        assertEquals("Hello wor\n", text())
    }

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1920
    }
}
