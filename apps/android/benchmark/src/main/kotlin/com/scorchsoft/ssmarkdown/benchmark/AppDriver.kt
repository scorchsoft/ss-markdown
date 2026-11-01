package com.scorchsoft.ssmarkdown.benchmark

import android.content.Intent
import android.net.Uri
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

/** The app under test. Its benchmark build types keep the release package name. */
internal const val PACKAGE = "com.scorchsoft.ssmarkdown"

/**
 * The trace sections the app emits (core/ui's `PerfTrace`). Copied, not
 * imported: this module must not depend on the app's code, only drive it.
 */
internal object Sections {
    const val COMMIT = "SSM:commit"
    const val OPEN = "SSM:open"
    const val PREVIEW_REFRESH = "SSM:previewRefresh"
}

/** The corpus `PerfDocumentProvider` serves (app/src/perf). */
internal enum class CorpusFile(
    val fileName: String,
) {
    Small("perf-50kb.md"),
    Large("perf-1mb.md"),
    Huge("perf-20mb.md"),
}

private const val OPEN_TIMEOUT_MILLIS = 60_000L
private const val SETTLE_MILLIS = 1_500L

/**
 * A clean app with [file] pristine: no journal, no recovery offer, no
 * remembered file, so every iteration opens the same bytes the same way.
 * `pm clear` keeps the compiled code and the installed profile.
 */
internal fun MacrobenchmarkScope.resetApp(file: CorpusFile) {
    device.executeShellCommand("pm clear $PACKAGE")
    val reply =
        device.executeShellCommand(
            "content call --uri content://$PACKAGE.perf --method reset --arg ${file.fileName}",
        )
    check("bytes=" in reply) { "the perf provider did not reset ${file.fileName}: $reply" }
    // The provider woke the process to answer; a cold start must start from nothing.
    killProcess()
}

/** Opens [file] for editing, as Files' "Open with" does, and waits for it to be on screen. */
internal fun MacrobenchmarkScope.openForEditing(file: CorpusFile) {
    val intent =
        Intent(Intent.ACTION_EDIT)
            .setDataAndType(Uri.parse("content://$PACKAGE.perf/${file.fileName}"), "text/markdown")
            .setClassName(PACKAGE, "$PACKAGE.MainActivity")
    startActivityAndWait(intent)
    awaitDocument(file)
}

/** Waits until [file]'s name is in the top bar, which the editor shows once the buffer is bound. */
internal fun MacrobenchmarkScope.awaitDocument(file: CorpusFile) {
    val shown = device.wait(Until.hasObject(By.textContains(file.fileName.removeSuffix(".md"))), OPEN_TIMEOUT_MILLIS)
    check(shown == true) { "${file.fileName} never appeared. On screen: ${screenTexts()}" }
    device.waitForIdle()
}

/** Every text on screen, so a failure says what the app showed instead (a "too large" notice, an error). */
private fun MacrobenchmarkScope.screenTexts(): String =
    device
        .findObjects(By.textContains(""))
        .mapNotNull { runCatching { it.text }.getOrNull() }
        .filter(String::isNotBlank)
        .joinToString(" | ")
        .take(SCREEN_TEXT_LIMIT)

private const val SCREEN_TEXT_LIMIT = 1_500

/**
 * Keys reach the editor as hardware key events, with no soft keyboard:
 * the keyboard's own latency is not ours to measure, and with it up
 * portrait Split folds the Preview to a strip (ux-spec "Layouts").
 * [restoreKeyboards] puts the device's keyboards back.
 */
internal fun MacrobenchmarkScope.disableKeyboards() {
    device
        .executeShellCommand("ime list -s")
        .lines()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .forEach { device.executeShellCommand("ime disable $it") }
}

/** Undoes [disableKeyboards]. */
internal fun MacrobenchmarkScope.restoreKeyboards() {
    device.executeShellCommand("ime reset")
}

/**
 * Shows [mode] ("Code", "Split" or "Preview") with the bar's own control.
 * A fresh install does not start in Code on every screen size, and a key
 * typed while Preview shows never reaches the Code pane.
 */
internal fun MacrobenchmarkScope.selectMode(mode: String) {
    val control = device.findObject(By.text(mode)) ?: error("no $mode control on screen")
    control.click()
    device.waitForIdle()
}

/** Taps the page area at [fractionY] of the screen height, which focuses the Code pane there. */
internal fun MacrobenchmarkScope.focusCodeAt(fractionY: Float) {
    device.click(device.displayWidth / 2, (device.displayHeight * fractionY).toInt())
    device.waitForIdle()
    Thread.sleep(SETTLE_MILLIS)
}
