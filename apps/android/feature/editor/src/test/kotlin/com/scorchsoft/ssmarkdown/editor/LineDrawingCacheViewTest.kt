package com.scorchsoft.ssmarkdown.editor

import android.graphics.RenderNode
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** On a hardware canvas, each line's text is recorded once and replayed until that line changes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LineDrawingCacheViewTest {
    private val view =
        MarkdownCodeView(ApplicationProvider.getApplicationContext()).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, WIDTH, HEIGHT)
        }

    /** Draws the view into a display list, which is a hardware-accelerated canvas. */
    private fun drawHardware() {
        val node = RenderNode("test")
        node.setPosition(0, 0, WIDTH, HEIGHT)
        val canvas = node.beginRecording()
        try {
            assertTrue(canvas.isHardwareAccelerated)
            view.draw(canvas)
        } finally {
            node.endRecording()
        }
    }

    @Test
    fun `unchanged lines are replayed and an edit re-records only its line`() {
        view.document = TextDocument(PieceTree.of((1..10).joinToString("\n") { "line $it with **bold** text" }))
        drawHardware()
        val first = view.recordedLineCount()
        assertEquals(10, first)

        drawHardware()
        assertEquals("a second frame replays", first, view.recordedLineCount())

        view.applyEdit(0, 0, "edited ")
        drawHardware()
        assertEquals("only the edited line is recorded again", first + 1, view.recordedLineCount())

        view.zoomPercent = 150
        drawHardware()
        assertTrue("a font change re-records what is visible", view.recordedLineCount() > first + 1)
    }

    /**
     * Highlighted code is cached like any line: typing inside a code line
     * re-records that line, and opening a block comment re-records the lines
     * whose colours it changed (their tokens and entry state changed), and
     * closing it re-records them again.
     */
    @Test
    fun `code lines are re-recorded when their text or entry state changes`() {
        view.document =
            TextDocument(
                PieceTree.of("```c\n" + (1..10).joinToString("\n") { "int x$it = $it;" } + "\n```"),
            )
        drawHardware()
        val first = view.recordedLineCount()
        assertEquals(12, first)

        view.applyEdit(view.document.text.lineStartOffset(5), view.document.text.lineStartOffset(5), "  ")
        drawHardware()
        assertEquals("an edit that keeps the state re-records its own line", first + 1, view.recordedLineCount())

        val open = view.document.text.lineStartOffset(3)
        view.applyEdit(open, open, "/* ")
        drawHardware()
        // Lines 3..10 are now comment; the closing fence keeps its tokens and is replayed.
        assertEquals("the comment's lines re-record", first + 1 + 8, view.recordedLineCount())

        view.applyEdit(open, open + 3, "")
        drawHardware()
        assertEquals("removing it restores them", first + 1 + 16, view.recordedLineCount())
    }

    /**
     * The platform frees a line's display list once nothing draws it (the
     * line scrolled off screen for a frame). Scrolling back must record it
     * again: replaying the emptied node drew the gutter and no text
     * (edge-cases.md, "Text vanishes after scrolling back").
     */
    @Test
    fun `lines the platform emptied are recorded again before they are drawn`() {
        view.wrap = true
        view.document = TextDocument(PieceTree.of((1..200).joinToString("\n") { "line $it " + "word ".repeat(30) }))
        drawHardware()
        val first = view.recordedLineCount()
        assertTrue(first > 0)

        view.scrollTo(0, HEIGHT * 4)
        drawHardware()
        // What HWUI does to the nodes of lines no frame draws any more.
        view.renderer.discardDisplayListsForTest()
        view.scrollTo(0, 0)
        val before = view.recordedLineCount()
        drawHardware()

        assertTrue("the top lines are recorded again", view.recordedLineCount() >= before + first)
        assertTrue(view.renderer.revivedLines >= first)
        assertEquals("every line on screen has content again", first, view.renderer.liveLinesForTest())
    }

    private companion object {
        const val WIDTH = 900
        const val HEIGHT = 800
    }
}
