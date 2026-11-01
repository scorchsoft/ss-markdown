package com.scorchsoft.ssmarkdown.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the app's Baseline Profile: the code a user runs first, so the
 * phone compiles it ahead of time instead of interpreting it (rubric #4,
 * cold start < 800 ms). The journey is the jobs that must be fast from the
 * first launch: start to an editable cursor, open a 1 MB file, type, fling,
 * and switch to Split and Preview.
 *
 * Run with `gradle -p apps/android :app:generateBaselineProfile` on a
 * device or emulator (API 33+, or rooted). The result is written to
 * app/src/main/generated/baselineProfiles/ and committed.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun generate() =
        rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
            pressHome()
            startActivityAndWait()
            resetApp(CorpusFile.Large)
            disableKeyboards()
            try {
                openForEditing(CorpusFile.Large)
                selectMode("Code")
                focusCodeAt(CODE_PANE_Y)
                repeat(KEYSTROKES) { device.pressKeyCode(android.view.KeyEvent.KEYCODE_A) }
                val x = device.displayWidth / 2
                val h = device.displayHeight
                repeat(FLINGS) {
                    device.swipe(x, (h * FLING_LOW).toInt(), x, (h * FLING_HIGH).toInt(), FLING_STEPS)
                    device.waitForIdle()
                }
                listOf("Split", "Preview", "Code").forEach { mode ->
                    device
                        .findObject(
                            androidx.test.uiautomator.By
                                .text(mode),
                        )?.click()
                    device.waitForIdle()
                    Thread.sleep(MODE_SETTLE_MILLIS)
                }
            } finally {
                restoreKeyboards()
            }
        }

    private companion object {
        const val CODE_PANE_Y = 0.4f
        const val KEYSTROKES = 10
        const val FLINGS = 4
        const val FLING_LOW = 0.8f
        const val FLING_HIGH = 0.25f
        const val FLING_STEPS = 4
        const val MODE_SETTLE_MILLIS = 1_000L
    }
}
