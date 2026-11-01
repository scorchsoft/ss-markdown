package com.scorchsoft.ssmarkdown.bench

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.View
import android.view.Window
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Suspends until the next frame has been drawn and returns `System.nanoTime()`
 * at that moment.
 *
 * How: a Choreographer frame callback runs at the start of the next frame,
 * before layout and draw; a message posted from it to the *front* of the
 * main queue runs as soon as that frame's traversal has finished. So the
 * time includes the engine's layout and draw for the frame, which is what
 * "time to the next frame drawn" means for the user. (The RenderThread's
 * GPU work is not included; FrameMetrics covers that in the jank test.)
 */
suspend fun awaitFrameDrawn(): Long =
    suspendCancellableCoroutine { continuation ->
        val main = Handler(Looper.getMainLooper())
        Choreographer.getInstance().postFrameCallback {
            main.postAtFrontOfQueue { if (continuation.isActive) continuation.resume(System.nanoTime()) }
        }
    }

/**
 * Collects every frame's total duration from the window's FrameMetrics,
 * on its own thread, between [start] and [stop].
 */
class FrameRecorder(
    private val window: Window,
) {
    private val thread = HandlerThread("frame-metrics").apply { start() }
    private val durations = ArrayList<Long>()
    private val listener =
        Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) {
                synchronized(durations) { durations += metrics.getMetric(FrameMetrics.TOTAL_DURATION) }
            }
        }

    fun start() {
        window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
    }

    /** Stops recording and returns the frame durations in nanoseconds. */
    suspend fun stop(): LongArray {
        // Metrics arrive a frame or two late; let the last ones land.
        delay(SETTLE_MILLIS)
        window.removeOnFrameMetricsAvailableListener(listener)
        thread.quitSafely()
        return synchronized(durations) { durations.toLongArray() }
    }

    private companion object {
        const val SETTLE_MILLIS = 200L
    }
}

/**
 * Flings [target] the way a thumb would, for [durationMillis]: quick
 * swipes, four one way then four the other, each followed by the engine's
 * own fling. Synthetic touch events go through the engine's real gesture
 * handling, so every engine is scrolled by its own code.
 */
suspend fun scriptedFling(
    target: View,
    durationMillis: Long,
) {
    val end = SystemClock.uptimeMillis() + durationMillis
    var swipe = 0
    while (SystemClock.uptimeMillis() < end) {
        val upwards = (swipe / SWIPES_PER_DIRECTION) % 2 == 0
        swipe(target, upwards)
        delay(FLING_GAP_MILLIS)
        swipe++
    }
}

private suspend fun swipe(
    target: View,
    upwards: Boolean,
) {
    val x = target.width / 2f
    val high = target.height * EDGE
    val low = target.height * (1 - EDGE)
    val (from, to) = if (upwards) low to high else high to low
    val down = SystemClock.uptimeMillis()
    dispatch(target, MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, from, 0))
    for (step in 1..SWIPE_STEPS) {
        delay(STEP_MILLIS)
        val y = from + (to - from) * step / SWIPE_STEPS
        val now = SystemClock.uptimeMillis()
        dispatch(target, MotionEvent.obtain(down, now, MotionEvent.ACTION_MOVE, x, y, 0))
    }
    val now = SystemClock.uptimeMillis()
    dispatch(target, MotionEvent.obtain(down, now, MotionEvent.ACTION_UP, x, to, 0))
}

/** Views copy what they keep of an event, so it is recycled once dispatched. */
private fun dispatch(
    target: View,
    event: MotionEvent,
) {
    target.dispatchTouchEvent(event)
    event.recycle()
}

private const val SWIPES_PER_DIRECTION = 4
private const val SWIPE_STEPS = 10
private const val STEP_MILLIS = 8L
private const val FLING_GAP_MILLIS = 400L
private const val EDGE = 0.2f
