package com.scorchsoft.ssmarkdown.editor

import kotlin.math.roundToInt

/**
 * The Code pane's text size as a zoom percentage (ux-spec, "Text size: one
 * model"): 100 % is [BASE_SP] (12.5 sp, quality-bar typography), and a
 * pinch changes the font size, never a bitmap scale.
 *
 * A pinch multiplies an exact float scale, so many small steps do not
 * accumulate rounding; [percent] is what is shown ("Pinching · 112%") and
 * stored per file. Pure, so the arithmetic is tested on the host.
 */
class ZoomModel(
    percent: Int = DEFAULT_PERCENT,
) {
    private var exact: Float = percent.coerceIn(MIN_PERCENT, MAX_PERCENT).toFloat()

    /** Whole percent, clamped to [MIN_PERCENT]..[MAX_PERCENT]. */
    val percent: Int get() = exact.roundToInt()

    /** The text size this zoom gives, in sp. */
    val textSizeSp: Float get() = BASE_SP * percent / DEFAULT_PERCENT

    /** Sets an exact percentage (the Display sheet stepper, a restored file). Returns true if it changed. */
    fun set(newPercent: Int): Boolean {
        val before = percent
        exact = newPercent.coerceIn(MIN_PERCENT, MAX_PERCENT).toFloat()
        return percent != before
    }

    /**
     * Applies one pinch step of [factor] (ScaleGestureDetector's scale
     * factor). Returns true when [percent] changed.
     */
    fun scaleBy(factor: Float): Boolean {
        if (!factor.isFinite() || factor <= 0f) return false
        val before = percent
        exact = (exact * factor).coerceIn(MIN_PERCENT.toFloat(), MAX_PERCENT.toFloat())
        return percent != before
    }

    /** Limits. */
    companion object {
        /** Code's 100 %, in sp. */
        const val BASE_SP: Float = 12.5f

        /** 100 %. */
        const val DEFAULT_PERCENT: Int = 100

        /** Smallest zoom: 6.25 sp, still legible as an overview. */
        const val MIN_PERCENT: Int = 50

        /** Largest zoom: 37.5 sp, a few words per row on a phone. */
        const val MAX_PERCENT: Int = 300

        /** True when going from [before] to [after] crossed or landed on 100 %: the moment for a haptic tick. */
        fun crossesDefault(
            before: Int,
            after: Int,
        ): Boolean = before != DEFAULT_PERCENT && (before - DEFAULT_PERCENT) * (after - DEFAULT_PERCENT) <= 0
    }
}
