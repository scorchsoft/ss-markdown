package com.scorchsoft.ssmarkdown.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
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

/** Layout maths through the real view, with real text measurement (native graphics). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownCodeViewTest {
    private val view =
        MarkdownCodeView(ApplicationProvider.getApplicationContext()).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, WIDTH, HEIGHT)
        }

    private fun show(text: String) {
        view.document = TextDocument(PieceTree.of(text))
    }

    private fun draw() {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        bitmap.recycle()
    }

    @Test
    fun `metrics are positive and the text starts after the gutter`() {
        show("hello")
        assertTrue(view.lineHeight > 0)
        assertTrue(view.charWidth > 0f)
        assertTrue(view.textLeft > 0)
    }

    @Test
    fun `a new code typeface re-measures the cell and keeps the caret maths exact`() {
        // UX2-12: the host swaps in the bundled JetBrains Mono after the view exists.
        show("abcd\nefgh")
        assertEquals(Typeface.MONOSPACE, view.codeTypeface)
        val face = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        view.codeTypeface = face
        assertEquals(face, view.codeTypeface)
        assertEquals(view.font.measurer.charWidth, view.charWidth, 0f)
        assertEquals(view.textLeft + 3 * view.charWidth, view.xForOffset(3), 0.5f)
        draw()
    }

    @Test
    fun `caret position and tap position are inverses`() {
        show("first line\nsecond line with more text\n\nfourth")
        for (offset in listOf(0, 5, 10, 11, 20, 37, 38, 39, 44)) {
            val x = view.xForOffset(offset)
            val y = view.yForOffset(offset) + view.lineHeight / 2f
            assertEquals("offset $offset", offset, view.offsetAt(x, y))
        }
    }

    @Test
    fun `caret x grows by one cell per ASCII char and lines stack by line height`() {
        show("abcd\nefgh")
        assertEquals(view.textLeft.toFloat(), view.xForOffset(0), 0.01f)
        assertEquals(view.textLeft + 3 * view.charWidth, view.xForOffset(3), 0.5f)
        assertEquals(view.lineHeight.toFloat(), view.yForOffset(6) - view.yForOffset(0), 0.01f)
    }

    @Test
    fun `a tap past the end of a line or below the text lands at the nearest end`() {
        show("ab\ncd")
        assertEquals(2, view.offsetAt(WIDTH - 1f, view.lineHeight / 2f))
        assertEquals(5, view.offsetAt(WIDTH - 1f, HEIGHT - 1f))
        assertEquals(3, view.offsetAt(0f, view.lineHeight * 1.5f))
    }

    @Test
    fun `a tap places the caret`() {
        show("hello\nworld")
        val time = SystemClock.uptimeMillis()
        val x = view.xForOffset(8)
        val y = view.lineHeight * 1.5f
        view.dispatchTouchEvent(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(time, time + 50, MotionEvent.ACTION_UP, x, y, 0))
        assertEquals(8, view.session.caret)
    }

    /**
     * A tap that stops a fling only stops it: it used to move the caret and
     * raise the insertion handle, which showed as a stray handle over the
     * text after a flick back to the top (edge-cases.md).
     */
    @Test
    fun `a tap that stops a fling neither moves the caret nor shows a handle`() {
        show((1..400).joinToString("\n") { "line $it" })
        view.scrollTo(0, view.lineHeight * 10)
        view.fling(VELOCITY)
        val time = SystemClock.uptimeMillis()
        val y = view.lineHeight * 1.5f
        val x = view.textLeft + 2 * view.charWidth
        view.dispatchTouchEvent(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(time, time + 50, MotionEvent.ACTION_UP, x, y, 0))
        assertEquals("the caret stays put", 0, view.session.caret)
        assertEquals(null, view.handlePoint(HandleKind.INSERTION))

        // The next tap, with nothing moving, places the caret as usual.
        val later = time + 1_000
        view.dispatchTouchEvent(MotionEvent.obtain(later, later, MotionEvent.ACTION_DOWN, x, y, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(later, later + 50, MotionEvent.ACTION_UP, x, y, 0))
        assertTrue("a plain tap moves the caret", view.session.caret > 0)
    }

    /**
     * The reading line is the scroll position over the scrollable range,
     * from the height map: exactly 1 at the very bottom, which the top
     * line it used to follow never reaches (edge-cases.md).
     */
    @Test
    fun `the scroll fraction is 0 at the top, exactly 1 at the bottom, and 0 when it all fits`() {
        val reported = ArrayList<Float>()
        view.listener =
            object : EditorListener {
                override fun onScrollFractionChanged(fraction: Float) {
                    reported += fraction
                }
            }
        view.wrap = true
        show((1..300).joinToString("\n") { "paragraph $it " + "a long wrapped sentence ".repeat(12) })
        draw()
        assertEquals(0f, reported.last())

        // Wrapped heights settle as lines are laid out; each scroll goes as far as it can.
        repeat(SETTLE_PASSES) {
            view.scrollByClamped(0, Int.MAX_VALUE / 2)
            draw()
        }
        assertEquals("exactly 1 at the bottom", 1f, reported.last())
        val half = view.scrollY / 2
        view.scrollTo(0, half)
        draw()
        assertTrue("part way", reported.last() in 0.3f..0.7f)

        view.scrollTo(0, 0)
        draw()
        assertEquals(0f, reported.last())

        show("# Short\n\nfits on one screen")
        draw()
        assertEquals("a document that fits is at 0", 0f, reported.last())
    }

    @Test
    fun `a one megabyte single line is windowed and still maps exactly`() {
        val line = "0123456789".repeat(100_000)
        show(line)
        draw()
        view.scrollTo((500_000 * view.charWidth).toInt(), 0)
        draw()
        val offset = 500_010
        val x = view.xForOffset(offset)
        assertTrue("caret should be on screen, x=$x", x in view.textLeft.toFloat()..WIDTH.toFloat())
        assertEquals(offset, view.offsetAt(x, view.lineHeight / 2f))
    }

    @Test
    fun `typing at the end of a deep line scrolls it into view`() {
        show("line\n".repeat(10_000))
        view.setCaret(view.document.text.lineStartOffset(5_000))
        val caretTop = view.yForOffset(view.session.caret)
        assertTrue(caretTop >= 0f && caretTop + view.lineHeight <= HEIGHT)
        view.session.commitText("typed", 1)
        draw()
        assertEquals(
            "typed",
            view.document.text
                .lineContent(5_000)
                .take(5),
        )
    }

    @Test
    fun `drawing a large document touches only the visible lines`() {
        show("short line\n".repeat(200_000))
        view.scrollTo(0, 100_000 * view.lineHeight)
        draw()
        // Every line drawn or prefetched is in the cache; it must be about a screen, not the file.
        val visible = HEIGHT / view.lineHeight + 1
        assertTrue(view.cachedLineCount() <= visible + 1 + PREFETCH_SLACK)
    }

    @Test
    fun `memory pressure drops cached layouts and tokens, and the next frame draws again (PERF-9)`() {
        show("# Title\n" + "short *line*\n".repeat(2_000))
        draw()
        assertTrue(view.cachedLineCount() > 0)
        assertTrue(view.highlight.tokens.cachedLines > 0)

        view.trimLayoutCaches()
        view.trimTokenCache()
        assertEquals(0, view.cachedLineCount())
        assertEquals(0, view.highlight.tokens.cachedLines)

        draw()
        assertTrue(view.cachedLineCount() > 0)
    }

    private companion object {
        const val VELOCITY = 8_000
        const val SETTLE_PASSES = 3
        const val WIDTH = 1_080
        const val HEIGHT = 1_920
        const val PREFETCH_SLACK = 8
    }
}
