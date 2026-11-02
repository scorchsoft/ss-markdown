package com.scorchsoft.ssmarkdown

import com.scorchsoft.ssmarkdown.fixtures.AccessibilityScanner
import org.junit.Assert.assertTrue
import org.robolectric.ParameterizedRobolectricTestRunner

/**
 * The accessibility scanner over every screen and sheet (rubric #10): each
 * surface [ScreenshotTest] renders, in light and dark, at its width and font
 * scale, is checked with Google's Accessibility Test Framework, the engine
 * inside the Accessibility Scanner app. Any ERROR fails: an unlabelled
 * control, a touch target under 48 dp, text or icon contrast under the
 * WCAG ratio, two clickables on the same bounds, and the rest of ATF's
 * latest preset. Known false positives are in [AccessibilityScanner.SUPPRESSIONS],
 * each with its reason.
 *
 * It renders exactly what the screenshots render, so a new screenshot is a
 * new scan, and it runs in the normal unit suite (`check`), not only when
 * screenshots are asked for. What a scanner cannot judge (what TalkBack
 * says, in what order, and whether it makes sense) is the TalkBack pass on
 * a phone.
 */
class AccessibilityScanTest(
    dark: Boolean,
) : ScreenshotTest(dark) {
    override fun capture(name: String): Int {
        val windows = openWindows()
        val errors = AccessibilityScanner.errors(windows)
        val theme = if (dark) "dark" else "light"
        assertTrue(
            "$name ($theme): ${errors.size} accessibility error(s)\n" + errors.joinToString("\n"),
            errors.isEmpty(),
        )
        return windows.size
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun themes(): List<Boolean> = listOf(false, true)
    }
}
