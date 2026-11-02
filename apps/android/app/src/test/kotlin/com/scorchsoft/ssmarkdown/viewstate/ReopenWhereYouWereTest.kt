package com.scorchsoft.ssmarkdown.viewstate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.MarkdownCodeView
import com.scorchsoft.ssmarkdown.editor.Pane
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Rubric #3, "reopening a file returns you to where you were", end to end
 * on the host: a real Code view is attached, the reader moves the cursor,
 * selects, scrolls, zooms and switches mode, the process goes away, and a
 * new editor over the same storage reopens the file. The new Code view gets
 * the same selection and the same top line, and the screen the same mode
 * and zoom.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReopenWhereYouWereTest {
    @get:Rule val temp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    private fun store() = JsonViewStateStore(File(temp.root, "view-state.json"), CoroutineScope(dispatcher))

    private fun codeView(): MarkdownCodeView =
        MarkdownCodeView(ApplicationProvider.getApplicationContext()).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, WIDTH, HEIGHT)
        }

    /** One frame, as the screen would draw it: the view lays out its rows and settles its top line. */
    private fun MarkdownCodeView.frame() {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap))
        bitmap.recycle()
    }

    /** What `MarkdownEditor` does when the Code pane appears: the view gets the document and zoom, then attaches. */
    private fun EditorFixture.showCode(): MarkdownCodeView {
        val view = codeView()
        val doc = vm.active ?: error("nothing open")
        view.setDocument(doc.text)
        view.zoomPercent = vm.uiState.value.codeZoom
        vm.attach(view)
        view.frame()
        return view
    }

    @Test
    fun `a reopened file restores its view mode, zoom, cursor, selection and top line`() {
        val text = (0 until LINES).joinToString("") { "Line $it of the handbook.\n" }
        val first = EditorFixture(temp.root, dispatcher, viewStates = store())
        val uri = first.file("handbook.md", text)
        first.vm.openDocument(uri)
        val before = first.showCode()
        first.vm.onAction(EditorAction.SelectMode(ViewMode.Split))
        first.vm.onAction(EditorAction.StepZoom(Pane.Code, 2))
        val selStart = text.indexOf("Line 120")
        val selEnd = selStart + "Line 120".length
        before.session.setSelection(selStart, selEnd)
        before.scrollToLine(TOP_LINE)
        before.frame()
        // The Code pane reports its top line as the reader scrolls (CodePaneHost wires this in the app).
        first.vm.onPaneTopLine(Pane.Code, before.anchorLine)
        assertEquals("scrolled before leaving", TOP_LINE, before.anchorLine)
        first.vm.onPause()

        // A new process: nothing survives but storage.
        val second = EditorFixture(temp.root, dispatcher, viewStates = store())
        second.vm.openDocument(uri)
        val after = second.showCode()

        val state = second.vm.uiState.value
        assertEquals(ViewMode.Split, state.mode)
        assertEquals(ZOOMED, state.codeZoom)
        assertEquals("cursor", selStart, after.session.selectionStart)
        assertEquals("selection end", selEnd, after.session.selectionEnd)
        assertEquals("the Code view's top line", TOP_LINE, after.anchorLine)
        assertEquals("the shared anchor", TOP_LINE, second.vm.anchorLine.value)
    }

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1800
        const val LINES = 400
        const val TOP_LINE = 117
        const val ZOOMED = 120
    }
}
