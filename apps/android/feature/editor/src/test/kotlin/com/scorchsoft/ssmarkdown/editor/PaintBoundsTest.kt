package com.scorchsoft.ssmarkdown.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextDocument
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The Code view paints only its own rectangle. Its page used to be
 * `drawColor`, which fills the canvas's whole clip: drawn by a host that
 * does not clip it first (a software capture of the app's Compose screen),
 * it painted over the header above it and erased the title and Save.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaintBoundsTest {
    @Test
    fun `the view never paints outside its bounds`() {
        val view =
            MarkdownCodeView(ApplicationProvider.getApplicationContext()).apply {
                measure(
                    View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
                )
                layout(0, 0, WIDTH, HEIGHT)
                document = TextDocument(PieceTree.of("# Title\n\nbody"))
            }
        // A canvas three views tall, with the view drawn in the middle band and no clip.
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT * 3, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(0f, HEIGHT.toFloat())
        view.draw(canvas)

        assertEquals("above the view", Color.TRANSPARENT, bitmap.getPixel(WIDTH / 2, HEIGHT / 2))
        assertEquals("below the view", Color.TRANSPARENT, bitmap.getPixel(WIDTH / 2, HEIGHT * 5 / 2))
        assertEquals("the page itself", view.theme.background, bitmap.getPixel(WIDTH - 1, HEIGHT * 3 / 2))
    }

    @Test
    fun `a gutter number whose row is half off the top is clipped to the view`() {
        // Found on a phone: scrolled to about line 235, the "234" of the row half above the pane
        // was drawn over the top bar. The text column was clipped; the gutter was not.
        val view =
            MarkdownCodeView(ApplicationProvider.getApplicationContext()).apply {
                measure(
                    View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
                )
                layout(0, 0, WIDTH, HEIGHT)
                document = TextDocument(PieceTree.of((1..400).joinToString("\n") { "line $it" }))
            }
        view.scrollTo(0, view.lineHeight * 233 + view.lineHeight / 2)
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT * 3, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // As a parent draws a scrolled child: translated by its scroll, with no clip.
        canvas.translate(0f, (HEIGHT - view.scrollY).toFloat())
        view.draw(canvas)

        assertEquals("the row half off the top", 233, view.anchorLine)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                assertEquals("ink above the view at ($x, $y)", Color.TRANSPARENT, bitmap.getPixel(x, y))
                assertEquals(
                    "ink below the view at ($x, ${y + 2 * HEIGHT})",
                    Color.TRANSPARENT,
                    bitmap.getPixel(x, y + 2 * HEIGHT),
                )
            }
        }
        assertEquals("the gutter itself", view.theme.gutterBackground, bitmap.getPixel(1, HEIGHT + 1))
    }

    private companion object {
        const val WIDTH = 400
        const val HEIGHT = 300
    }
}
