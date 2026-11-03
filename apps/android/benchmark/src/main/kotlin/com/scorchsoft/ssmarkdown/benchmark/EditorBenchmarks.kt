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
@Suppress("TooManyFunctions") // One function per benchmark, plus their shared steps.
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
            // Every launch the same: no remembered file (another benchmark's
            // 20 MB file would turn this into an open benchmark), so the app
            // starts into a new, empty draft.
            setupBlock = {
                device.executeShellCommand("pm clear $PACKAGE")
                pressHome()
            },
        ) {
            startActivityAndWait()
            // TTFD is only recorded if the trace is still running when the app reports drawn.
            awaitEditable(UNTITLED)
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
     * Drags the fast-scroll thumb (ux-spec "Fast scroll") down and back up a
     * 20 MB file. A short swipe shows the thumb at the top of the pane; the
     * drag starts on it, at the right edge, and moves slowly so every frame
     * is a jump to a new line. Budget: the fling's, no frame over 32 ms.
     */
    @Test
    fun fastScroll20Mb() =
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
            val w = device.displayWidth
            val h = device.displayHeight
            // A small scroll near the top shows the thumb, close to the top of the pane.
            device.swipe(w / 2, (h * NUDGE_FROM).toInt(), w / 2, (h * NUDGE_TO).toInt(), DRAG_STEPS)
            val density = w.toFloat() / device.displaySizeDp.x
            val x = w - (THUMB_FROM_EDGE_DP * density).toInt()
            device.drag(x, (h * THUMB_START).toInt(), x, (h * FLING_LOW).toInt(), DRAG_STEPS)
            device.drag(x, (h * FLING_LOW).toInt(), x, (h * THUMB_START).toInt(), DRAG_STEPS)
        }

    /**
     * Flings UP a wrapped 1 MB file from its end, where every line above
     * the screen still has an estimated height: each line measured on the
     * way up corrects the scroll, the case that juddered on a phone
     * (edge-cases.md, "Upward fling judders"). Budget: 60 fps, no frame over 32 ms.
     */
    @Test
    fun flingUp1Mb() =
        rule.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.DEFAULT,
            iterations = FLING_ITERATIONS,
            setupBlock = {
                resetApp(CorpusFile.Large)
                disableKeyboards()
                openForEditing(CorpusFile.Large)
                selectMode("Code")
                focusCodeAt(CODE_PANE_Y)
                // Ctrl+End: to the end without laying out what is above it.
                device.pressKeyCode(KeyEvent.KEYCODE_MOVE_END, KeyEvent.META_CTRL_ON)
                device.waitForIdle()
            },
        ) {
            try {
                val x = device.displayWidth / 2
                val h = device.displayHeight
                repeat(FLINGS) {
                    // The finger moves down the screen: the text scrolls towards the start.
                    device.swipe(x, (h * FLING_HIGH).toInt(), x, (h * FLING_LOW).toInt(), FLING_STEPS)
                    Thread.sleep(FLING_GAP_MILLIS)
                }
            } finally {
                restoreKeyboards()
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
        /** A new draft's name in the top bar (app strings.xml `untitled`). */
        const val UNTITLED = "Untitled"
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

        // Fast scroll: a slow nudge (shows the thumb), then drags along the right edge.
        const val NUDGE_FROM = 0.6f
        const val NUDGE_TO = 0.5f
        const val THUMB_FROM_EDGE_DP = 12
        const val THUMB_START = 0.13f
        const val DRAG_STEPS = 120

        const val PREVIEW_LOAD_MILLIS = 3_000L
        const val PREVIEW_KEYSTROKES = 6
        const val PREVIEW_SETTLE_MILLIS = 900L
    }
}
