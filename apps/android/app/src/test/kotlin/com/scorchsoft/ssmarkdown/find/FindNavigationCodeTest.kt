package com.scorchsoft.ssmarkdown.find

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.MarkdownCodeView
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Find next in Code through a real Code view with real text measurement
 * (edge-cases "Find next does not move"): each step selects the match and
 * scrolls the least distance that shows it, even many rows down a wrapped
 * paragraph, where putting its line at the top left it below the pane.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FindNavigationCodeTest {
    @get:Rule val temp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    private fun codeView(): MarkdownCodeView =
        MarkdownCodeView(ApplicationProvider.getApplicationContext()).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, WIDTH, HEIGHT)
        }

    private fun MarkdownCodeView.frame() {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap))
        bitmap.recycle()
    }

    private fun EditorFixture.showCode(wrap: Boolean): MarkdownCodeView {
        val view = codeView()
        val doc = vm.active ?: error("nothing open")
        view.setDocument(doc.text)
        view.wrap = wrap
        vm.attach(view)
        view.frame()
        return view
    }

    private fun MarkdownCodeView.isOnScreen(offset: Int): Boolean {
        val y = yForOffset(offset)
        return y >= 0f && y + rowHeight <= height
    }

    private fun EditorFixture.find(action: FindAction) = vm.onAction(EditorAction.Find(action))

    @Test
    fun `next selects each match in turn and scrolls it into view`() {
        val text = (0 until 300).joinToString("") { if (it % 50 == 25) "the needle $it\n" else "hay $it\n" }
        val f = EditorFixture(temp.root, dispatcher)
        f.vm.openDocument(f.file("f.md", text))
        val view = f.showCode(wrap = false)
        f.find(FindAction.Open)
        f.find(FindAction.Query("needle"))
        val starts = Regex("needle").findAll(text).map { it.range.first }.toList()
        repeat(starts.size + 1) { step ->
            val i = step % starts.size
            val find = f.vm.uiState.value.find!!
            assertEquals("counter at step $step", i, find.current)
            assertEquals("selection start at step $step", starts[i], view.session.selectionStart)
            assertEquals("selection end at step $step", starts[i] + 6, view.session.selectionEnd)
            view.frame()
            assertTrue("match $i on screen", view.isOnScreen(starts[i]))
            f.find(FindAction.Next)
        }
    }

    @Test
    fun `a match deep in a long wrapped paragraph is scrolled into view`() {
        val para = "The quick brown fox jumps over the lazy dog. ".repeat(200) + "needle " + "word ".repeat(200)
        val text = "# Title\n\n$para\n"
        val f = EditorFixture(temp.root, dispatcher)
        f.vm.openDocument(f.file("f.md", text))
        val view = f.showCode(wrap = true)
        f.find(FindAction.Open)
        f.find(FindAction.Query("needle"))
        view.frame()
        val start = text.indexOf("needle")
        assertEquals(start, view.session.selectionStart)
        assertTrue("wrapped match on screen, y=${view.yForOffset(start)}", view.isOnScreen(start))
    }

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1200
    }
}
