package com.scorchsoft.ssmarkdown.preview

import android.content.Context
import android.view.MotionEvent
import android.view.ScaleGestureDetector

/**
 * Pinch in Preview changes this file's text size, reflowing text rather than
 * scaling a bitmap (docs/ux-spec.md, "Gestures in Preview", rule 8). The
 * WebView's own zoom is off; this reports a clamped factor to the host.
 */
internal class PinchZoom(
    context: Context,
    private val onZoom: (Float) -> Unit,
) {
    var zoom: Float = 1f
        private set

    private val detector =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val next = (zoom * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    if (next != zoom) {
                        zoom = next
                        onZoom(next)
                    }
                    return true
                }
            },
        )

    fun set(value: Float) {
        zoom = value.coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    /** Feeds a touch event; returns true while a pinch is in progress. */
    fun onTouchEvent(event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        return detector.isInProgress
    }

    companion object {
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 4f
    }
}
