package com.scorchsoft.ssmarkdown.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextDocument
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import kotlin.math.abs

/**
 * "Upward fling judders" (docs/edge-cases.md). Flinging towards the start
 * of a wrapped document measures lines above the top whose estimated
 * height was wrong, and the scroll anchoring moves scrollY by the
 * difference. The fling then set scrollY from its own position, which
 * knew nothing of it, so every such frame lurched. Here a fling up through
 * lines of very different lengths must move the text one way only, and
 * never faster than the frame before.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FlingUpTest {
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

    @Test
    fun `a fling up through estimated heights moves the text one way, decelerating`() {
        view.wrap = true
        // Short and very long lines mixed: the average estimate is wrong for every one of them.
        view.document =
            TextDocument(
                PieceTree.of(
                    (0 until LINES).joinToString("\n") { i ->
                        if (i % LONG_EVERY == 0) "long $i " + "words that wrap ".repeat(LONG_WORDS) else "short $i"
                    },
                ),
            )
        draw()
        view.scrollToLine(START_LINE)
        draw()

        view.fling(-VELOCITY)
        val steps = ArrayList<Int>()
        repeat(FRAMES) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(FRAME_MILLIS))
            val before = view.scrollY
            view.computeScroll()
            steps += view.scrollY - before
            draw()
        }
        val moving = steps.takeWhile { it != 0 }
        assertTrue("the fling moved for a while (${moving.size} frames)", moving.size > MIN_FRAMES)
        assertTrue("never backwards: $steps", moving.all { it < 0 })
        moving.zipWithNext().forEachIndexed { frame, (previous, next) ->
            val allowed = abs(previous) + maxOf(SLACK_PX, abs(previous) * SLACK_FRACTION)
            assertTrue("frame ${frame + 1} jumped: $previous then $next in $steps", abs(next) <= allowed)
        }
        assertTrue("lines above were corrected mid-fling", view.viewport.flingRestarts > 0)
    }

    private companion object {
        const val WIDTH = 1_080
        const val HEIGHT = 1_920
        const val LINES = 2_000
        const val LONG_EVERY = 5
        const val LONG_WORDS = 40
        const val START_LINE = 1_500
        const val VELOCITY = 12_000
        const val FRAMES = 90
        const val FRAME_MILLIS = 16L
        const val MIN_FRAMES = 10
        const val SLACK_PX = 4f
        const val SLACK_FRACTION = 0.1f
    }
}
