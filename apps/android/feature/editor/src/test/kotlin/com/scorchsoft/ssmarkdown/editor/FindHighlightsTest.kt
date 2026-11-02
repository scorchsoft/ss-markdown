package com.scorchsoft.ssmarkdown.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Find-match ranges: lookup, edits, and the fills the view draws for them. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FindHighlightsTest {
    @Test
    fun `matches are sorted, current follows its match, and lookup is by binary search`() {
        val finds = FindHighlights()
        finds.set(intArrayOf(20, 0, 10), intArrayOf(23, 3, 13), newCurrent = 0)
        assertEquals(listOf(0, 10, 20), (0 until finds.count).map(finds::startOf))
        assertEquals(2, finds.current)
        assertEquals(0, finds.firstEndingAfter(0))
        assertEquals(1, finds.firstEndingAfter(3))
        assertEquals(1, finds.firstEndingAfter(12))
        assertEquals(3, finds.firstEndingAfter(23))
    }

    @Test
    fun `an edit shifts matches after it, keeps those before, and drops those it touches`() {
        val finds = FindHighlights()
        finds.set(intArrayOf(0, 10, 20), intArrayOf(3, 13, 23), newCurrent = 2)
        finds.onEdit(start = 11, removed = 1, inserted = 4)
        assertEquals(listOf(0 to 3, 23 to 26), (0 until finds.count).map { finds.startOf(it) to finds.endOf(it) })
        assertEquals(1, finds.current)
        // An insertion right before a match moves it; one right after leaves it.
        finds.onEdit(start = 23, removed = 0, inserted = 2)
        finds.onEdit(start = 3, removed = 0, inserted = 1)
        assertEquals(listOf(0 to 3, 26 to 29), (0 until finds.count).map { finds.startOf(it) to finds.endOf(it) })
    }

    @Test
    fun `the view fills matches with the highlight token and follows edits`() {
        val view = sizedView()
        view.theme = EditorTheme.LIGHT
        view.document = TextDocument(PieceTree.of("alpha beta alpha\nsecond line"))
        view.setFindMatches(intArrayOf(0, 11), intArrayOf(5, 16), current = 1)
        assertEquals(2, view.findMatchCount)

        val plain = render(view)
        val y = (view.yForOffset(0) + view.lineHeight / 2).toInt()
        // Sample the band above the glyphs, below the current match's outline.
        val top = (view.yForOffset(0) + OUTLINE_CLEARANCE).toInt()
        assertEquals(EditorTheme.LIGHT.findMatch, plain.getPixel((view.xForOffset(0) + 1).toInt(), top))
        assertEquals(EditorTheme.LIGHT.findCurrent, plain.getPixel((view.xForOffset(11) + view.charWidth).toInt(), top))
        assertNotEquals(EditorTheme.LIGHT.findMatch, plain.getPixel((view.xForOffset(7)).toInt(), top))
        assertEquals(true, y > top)

        // Typing at the start moves both matches; undo clears them until the host searches again.
        view.applyEdit(0, 0, ">> ")
        assertEquals(2, view.findMatchCount)
        val moved = render(view)
        assertEquals(EditorTheme.LIGHT.findMatch, moved.getPixel((view.xForOffset(3) + 1).toInt(), top))
        view.session.undo()
        assertEquals(0, view.findMatchCount)
        view.clearFindMatches()
    }

    @Test
    fun `text stays readable on both highlight fills in both themes`() {
        for (theme in listOf(EditorTheme.LIGHT, EditorTheme.DARK)) {
            for (fill in listOf(theme.findMatch, theme.findCurrent)) {
                val ratio = contrast(theme.text, fill)
                assertEquals("ink on ${Integer.toHexString(fill)}: $ratio", true, ratio >= AA)
            }
        }
    }

    private fun contrast(
        a: Int,
        b: Int,
    ): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + FLARE) / (minOf(la, lb) + FLARE)
    }

    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((color shr shift) and BYTE) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun sizedView(): MarkdownCodeView =
        MarkdownCodeView(ApplicationProvider.getApplicationContext()).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, WIDTH, HEIGHT)
        }

    private fun render(view: MarkdownCodeView): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private companion object {
        const val WIDTH = 600
        const val HEIGHT = 400
        const val OUTLINE_CLEARANCE = 3
        const val AA = 4.5
        const val FLARE = 0.05
        const val BYTE = 0xFF
    }
}
