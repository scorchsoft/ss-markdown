package com.scorchsoft.ssmarkdown.editor

import android.view.View
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** requestCursorUpdates: where the caret and the composing word are drawn, once or on every change. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CursorAnchorReporterTest {
    private val view =
        MarkdownCodeView(ApplicationProvider.getApplicationContext()).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, WIDTH, HEIGHT)
            document = TextDocument(PieceTree.of("first line\nsecond"))
        }
    private val reports = mutableListOf<CursorAnchorInfo>()
    private val reporter = CursorAnchorReporter(view) { reports += it }

    @Test
    fun `an immediate request reports the caret where it is drawn`() {
        view.setCaret(14)
        assertTrue(reporter.request(InputConnection.CURSOR_UPDATE_IMMEDIATE))
        val info = reports.single()
        assertEquals(14, info.selectionStart)
        assertEquals(14, info.selectionEnd)
        assertEquals(view.xForOffset(14), info.insertionMarkerHorizontal, 0.01f)
        assertEquals(view.yForOffset(14), info.insertionMarkerTop, 0.01f)
        assertEquals(view.yForOffset(14) + view.lineHeight, info.insertionMarkerBottom, 0.01f)
        assertEquals(CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION, info.insertionMarkerFlags)
        // Not monitoring: later moves are not reported.
        reporter.onCursorMoved()
        assertEquals(1, reports.size)
    }

    @Test
    fun `monitoring reports every change until the keyboard stops it`() {
        reporter.request(InputConnection.CURSOR_UPDATE_MONITOR)
        assertTrue(reports.isEmpty())
        reporter.onCursorMoved()
        reporter.onCursorMoved()
        assertEquals(2, reports.size)
        reporter.request(0)
        reporter.onCursorMoved()
        assertEquals(2, reports.size)
    }

    @Test
    fun `the composing word and its character bounds are included`() {
        view.setCaret(17)
        view.session.setComposingText("ab", 1)
        reporter.request(InputConnection.CURSOR_UPDATE_IMMEDIATE)
        val info = reports.single()
        assertEquals(17, info.composingTextStart)
        assertEquals("ab", info.composingText.toString())
        val bounds = info.getCharacterBounds(18)
        assertEquals(view.xForOffset(18), bounds?.left ?: Float.NaN, 0.01f)
        assertEquals(view.xForOffset(19), bounds?.right ?: Float.NaN, 0.01f)
    }

    @Test
    fun `the view's input connection supports cursor updates`() {
        val ic = view.onCreateInputConnection(EditorInfo())
        assertTrue(
            ic.requestCursorUpdates(InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR),
        )
        assertTrue(view.cursorAnchors.monitoring)
        ic.closeConnection()
        assertTrue(!view.cursorAnchors.monitoring)
    }

    private companion object {
        const val WIDTH = 600
        const val HEIGHT = 400
    }
}
