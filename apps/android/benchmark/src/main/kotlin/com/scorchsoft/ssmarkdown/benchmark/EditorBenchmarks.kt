package com.scorchsoft.ssmarkdown.benchmark

import android.view.KeyEvent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.TraceSectionMetric.Mode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The rubric #4 measurements on the CI emulator (docs/quality-bar.md §4).
 * Every number lands in the benchmark JSON, which `perf_report.py` turns
 * into the job summary and checks against docs/perf/emulator-baseline.json.
 *
 * The app is the benchmark build (release code, not debuggable,
 * profileable), compiled as a user's phone compiles it: with the Baseline
 * Profile when the APK carries one ([CompilationMode.DEFAULT]).
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class EditorBenchmarks {
    @get:Rule val rule = MacrobenchmarkRule()

    /**
     * Budget < 800 ms to an editable cursor: `timeToFullDisplayMs`, as
     * MainActivity reports itself drawn once a document is bound.
     */
    @Test
    fun coldStart() =
        rule.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.DEFAULT,
            startupMode = StartupMode.COLD,
            iterations = STARTUP_ITERATIONS,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
        }

    /** Budget < 1 s: from the open request to the bound, editable buffer (`SSM:open`). */
    @Test
    fun open1Mb() = open(CorpusFile.Large)

    /** Budget < 3 s. */
    @Test
    fun open20Mb() = open(CorpusFile.Huge)

    private fun open(file: CorpusFile) =
        rule.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(TraceSectionMetric(Sections.OPEN, mode = Mode.First, label = "open")),
            compilationMode = CompilationMode.DEFAULT,
            startupMode = StartupMode.COLD,
            iterations = OPEN_ITERATIONS,
            setupBlock = { resetApp(file) },
        ) {
            openForEditing(file)
        }

    /** Keystroke to commit (`SSM:commit`) and the frames typing draws, on 1 MB. Budget: a frame, 16 ms. */
    @Test
    fun typing1Mb() = typing(CorpusFile.Large)

    /** The same on 20 MB: the cost must not grow with the file (virtualised). */
    @Test
    fun typing20Mb() = typing(CorpusFile.Huge)

    private fun typing(file: CorpusFile) =
        rule.measureRepeated(
            packageName = PACKAGE,
            metrics =
                listOf(
                    TraceSectionMetric(Sections.COMMIT, mode = Mode.Sum, label = "commit"),
                    TraceSectionMetric(Sections.COMMIT, mode = Mode.Max, label = "commit"),
                    FrameTimingMetric(),
                ),
            compilationMode = CompilationMode.DEFAULT,
            iterations = TYPING_ITERATIONS,
            setupBlock = {
                resetApp(file)
                disableKeyboards()
                openForEditing(file)
                selectMode("Code")
                focusCodeAt(CODE_PANE_Y)
            },
        ) {
            try {
                repeat(KEYSTROKES) {
                    device.pressKeyCode(KEYS[it % KEYS.size])
                    Thread.sleep(KEYSTROKE_GAP_MILLIS)
                }
            } finally {
                restoreKeyboards()
            }
        }

    /** About 10 s of flings down a 20 MB file. Budget: 60 fps, no frame over 32 ms. */
    @Test
    fun fling20Mb() =
        rule.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.DEFAULT,
            iterations = FLING_ITERATIONS,
            setupBlock = {
                resetApp(CorpusFile.Huge)
                openForEditing(CorpusFile.Huge)
                selectMode("Code")
            },
        ) {
            val x = device.displayWidth / 2
            val h = device.displayHeight
            repeat(FLINGS) {
                // Fast (few steps) swipes are flings; alternate so the view never runs out of file.
                val down = it % FLINGS_PER_DIRECTION != FLINGS_PER_DIRECTION - 1
                val (from, to) = if (down) FLING_LOW to FLING_HIGH else FLING_HIGH to FLING_LOW
                device.swipe(x, (h * from).toInt(), x, (h * to).toInt(), FLING_STEPS)
                Thread.sleep(FLING_GAP_MILLIS)
            }
        }

    /**
     * Preview refresh after typing on a 50 KB document, in Split. Each key
     * is typed alone and given time to settle, so each `SSM:previewRefresh`
     * is "typing stopped → the visible section redrawn". Budget < 150 ms.
     */
    @Test
    fun previewRefresh50Kb() =
        rule.measureRepeated(
            packageName = PACKAGE,
            metrics =
                listOf(
                    TraceSectionMetric(Sections.PREVIEW_REFRESH, mode = Mode.Sum, label = "previewRefresh"),
                    TraceSectionMetric(Sections.PREVIEW_REFRESH, mode = Mode.Max, label = "previewRefresh"),
                ),
            compilationMode = CompilationMode.DEFAULT,
            iterations = PREVIEW_ITERATIONS,
            setupBlock = {
                resetApp(CorpusFile.Small)
                disableKeyboards()
                openForEditing(CorpusFile.Small)
                selectMode("Split")
                // The page's first load and render is not what is measured.
                Thread.sleep(PREVIEW_LOAD_MILLIS)
                focusCodeAt(SPLIT_CODE_PANE_Y)
            },
        ) {
            try {
                repeat(PREVIEW_KEYSTROKES) {
                    device.pressKeyCode(KEYS[it % KEYS.size])
                    Thread.sleep(PREVIEW_SETTLE_MILLIS)
                }
            } finally {
                restoreKeyboards()
            }
        }

    private companion object {
        const val STARTUP_ITERATIONS = 10
        const val OPEN_ITERATIONS = 5
        const val TYPING_ITERATIONS = 3
        const val FLING_ITERATIONS = 3
        const val PREVIEW_ITERATIONS = 3

        /** Below the top bar, in the Code pane, whatever the screen. */
        const val CODE_PANE_Y = 0.4f

        /** Portrait Split: the Code pane is the top half. */
        const val SPLIT_CODE_PANE_Y = 0.3f

        const val KEYSTROKES = 30
        const val KEYSTROKE_GAP_MILLIS = 120L
        val KEYS = intArrayOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_C)

        const val FLINGS = 12
        const val FLINGS_PER_DIRECTION = 4
        const val FLING_LOW = 0.8f
        const val FLING_HIGH = 0.25f
        const val FLING_STEPS = 4
        const val FLING_GAP_MILLIS = 700L

        const val PREVIEW_LOAD_MILLIS = 3_000L
        const val PREVIEW_KEYSTROKES = 6
        const val PREVIEW_SETTLE_MILLIS = 900L
    }
}
