package com.scorchsoft.ssmarkdown.editor

import android.graphics.Bitmap
import android.graphics.Canvas
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
import kotlin.math.floor

/**
 * Dragging the fast-scroll thumb across a 20 MB file (docs/ux-spec.md,
 * "Fast scroll"; quality-bar §4, 20 MB files). Wrapped, so almost every
 * height is an estimate that drawing corrects: the drag maps by line
 * index, so the line at the top only ever moves forwards, is exactly the
 * line the finger names, and stays put while the lines around it are
 * measured.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CodeFastScrollTest {
    private val view =
        MarkdownCodeView(ApplicationProvider.getApplicationContext()).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, WIDTH, HEIGHT)
        }

    private val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)

    private fun draw() = view.draw(Canvas(bitmap))

    /** About 20 MB: short lines and long wrapping ones, so the average estimate fits none of them. */
    private fun twentyMegabytes(): TextDocument {
        val text = StringBuilder(TARGET_CHARS + LONG_LINE.length)
        var i = 0
        while (text.length < TARGET_CHARS) {
            if (i % LONG_EVERY ==
                0
            ) {
                text
                    .append("long ")
                    .append(i)
                    .append(' ')
                    .append(LONG_LINE)
            } else {
                text.append("short ").append(i)
            }
            text.append('\n')
            i++
        }
        return TextDocument(PieceTree.of(text))
    }

    @Test
    fun `a drag across 20 MB with estimated heights is monotonic, exact and steady, inside the frame budget`() {
        view.wrap = true
        view.document = twentyMegabytes()
        draw()
        view.setCaret(CARET)
        val version = view.document.version
        val lines = view.document.text.lineCount
        assertTrue("a big file ($lines lines)", lines > MIN_LINES)

        val fast = view.fastScroll
        fast.begin()
        val end = view.geometry.heights.endPosition(view.height)
        var last = -1
        val frameNanos = LongArray(STEPS + 1)
        for (step in 0..STEPS) {
            val fraction = step.toFloat() / STEPS
            val started = System.nanoTime()
            fast.dragTo(fraction)
            draw()
            frameNanos[step] = System.nanoTime() - started
            val top = view.anchorLine
            assertTrue("step $step went back: $last then $top", top >= last)
            if (fraction < 1f) {
                assertEquals("step $step is the line the finger names", floor(fraction * end).toInt(), top)
            }
            // More frames measure more lines around it: the top line must not move.
            draw()
            draw()
            assertEquals("step $step jittered as heights were measured", top, view.anchorLine)
            last = top
        }
        fast.end()

        assertEquals("the last step reaches the bottom", view.viewport.maxScrollY(), view.scrollY)
        assertEquals("the caret stays where it was", CARET, view.session.caret)
        assertEquals("no edit was made", version, view.document.version)

        val sorted = frameNanos.drop(WARM_UP).sorted()
        val p50 = sorted[sorted.size / 2] / NANOS_PER_MILLI
        val p95 = sorted[sorted.size * P95 / PERCENT] / NANOS_PER_MILLI
        println(
            "fast-scroll drag, 20 MB wrapped, $lines lines: p50 %.2f ms, p95 %.2f ms (dragTo + draw, host)".format(
                p50,
                p95,
            ),
        )
        assertTrue("median drag frame %.1f ms, fling budget %d ms".format(p50, FRAME_BUDGET_MS), p50 < FRAME_BUDGET_MS)
    }

    @Test
    fun `the thumb fraction follows scrolling and the visible fraction is one screen over the document`() {
        view.document = TextDocument(PieceTree.of((0 until SMALL_LINES).joinToString("\n") { "line $it" }))
        draw()
        assertEquals(0f, view.fastScroll.fraction, 0f)
        val expectedVisible = HEIGHT.toFloat() / (SMALL_LINES * view.rowHeight)
        assertEquals(expectedVisible, view.fastScroll.visibleFraction, 1e-4f)
        view.scrollToLine(SMALL_LINES)
        draw()
        assertEquals(1f, view.fastScroll.fraction, 0f)
        assertTrue(!view.isSelectionInUse)
    }

    private companion object {
        const val WIDTH = 1_080
        const val HEIGHT = 1_920
        const val TARGET_CHARS = 20_000_000
        const val LONG_EVERY = 7
        val LONG_LINE = "words that wrap across the pane ".repeat(12)
        const val MIN_LINES = 200_000
        const val STEPS = 200
        const val CARET = 40
        const val WARM_UP = 20
        const val NANOS_PER_MILLI = 1_000_000.0
        const val P95 = 95
        const val PERCENT = 100
        const val FRAME_BUDGET_MS = 32
        const val SMALL_LINES = 2_000
    }
}
