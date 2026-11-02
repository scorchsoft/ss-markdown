package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.files.fakes.FakeDocumentProvider
import com.scorchsoft.ssmarkdown.files.fakes.FakeMemorySource
import com.scorchsoft.ssmarkdown.files.fakes.MB
import com.scorchsoft.ssmarkdown.files.fakes.StoreFixture
import com.scorchsoft.ssmarkdown.history.journal.BaseFingerprint
import com.scorchsoft.ssmarkdown.text.UndoStack
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/**
 * What keeping the undo history costs the open (ADR 0016; PERF-22, the
 * open budget of docs/quality-bar.md §4: 20 MB in under 3 s on a phone).
 *
 * The measured case is the worst the format allows: a 20 MB document
 * whose journal holds a full 8 MB undo tail, reopened after a death. Three
 * journals: a clean one (a save, then the death: History only), a dirty
 * one after a compaction or a save typed through (Snapshot + History), and
 * one with the tail as plain ops after the base. Each is timed against a
 * plain open of the same file with no journal.
 *
 * Opt-in (`-Pssmarkdown.heavyTests=true`, about a minute and 2 GB of heap
 * on the host); `check` runs the same measurement at a tenth of the size,
 * with only the result checked. To run it:
 * `gradle -p apps/android :feature:files:testDebugUnitTest -Pssmarkdown.heavyTests=true`
 * `--tests '*UndoReplayBenchmark*' -i`
 */
class UndoReplayBenchmarkTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val heavy = System.getProperty("ssmarkdown.heavyTests") == "true"

    @Test
    fun `a 20 MB document with a full 8 MB undo tail reopens within the open budget`() {
        assumeTrue("heavy benchmark; pass -Pssmarkdown.heavyTests=true", heavy)
        measure(textChars = 20_000_000, tailBytes = UndoStack.HISTORY_TAIL_MAX_BYTES, report = true)
    }

    @Test
    fun `a 2 MB document with an 800 KB undo tail reopens with its history`() {
        measure(textChars = 2_000_000, tailBytes = UndoStack.HISTORY_TAIL_MAX_BYTES / 10, report = false)
    }

    private fun measure(
        textChars: Int,
        tailBytes: Long,
        report: Boolean,
    ) = runTest {
        val random = Random(SEED)
        val line = "A line of a long Markdown file, for the undo benchmark.\n"
        val text = line.repeat(textChars / line.length)
        // Each step replaces a run of chars somewhere in the text: 2 x 2 x STEP_CHARS bytes of tail.
        val steps = (tailBytes / (4L * STEP_CHARS + 12)).toInt()
        val results = LinkedHashMap<String, Long>()

        for (kind in Kind.entries) {
            val provider = FakeDocumentProvider()
            val uri = provider.put("content://docs/big.md", text)
            val root = tmp.newFolder(kind.name)
            val first = fixture(root, provider)
            val doc = (first.store.open(uri) as OpenOutcome.Opened).document
            prepare(kind, doc, provider, random, steps)
            first.store.flushJournals()
            val expected = doc.document.text.toString()
            val expectedSteps =
                doc.document
                    .exportHistory()
                    .undo.size
            val journalBytes =
                first.journals
                    .fileFor(uri.value)
                    .takeIf(File::exists)
                    ?.length() ?: 0

            val after = fixture(root, provider)
            after.store.scanForRecovery()
            val started = System.nanoTime()
            val reopened = (after.store.open(uri) as OpenOutcome.Opened).document
            val millis = (System.nanoTime() - started) / NANOS_PER_MILLI
            results["${kind.label} (journal ${journalBytes / KIB} KiB)"] = millis

            assertEquals(expected, reopened.document.text.toString())
            assertEquals(
                expectedSteps,
                reopened.document
                    .exportHistory()
                    .undo.size,
            )
            if (kind != Kind.Plain) assertTrue(reopened.document.canUndo)
        }
        if (report) {
            println(
                "Undo tail replay, ${textChars / 1_000_000} MB text, $steps steps (${tailBytes / KIB} KiB of tail):",
            )
            results.forEach { (what, millis) -> println("  $what: $millis ms") }
        }
    }

    /** Makes [steps] undo steps in [doc] and leaves its journal as [kind] says. */
    private fun prepare(
        kind: Kind,
        doc: OpenDocument,
        provider: FakeDocumentProvider,
        random: Random,
        steps: Int,
    ) {
        if (kind != Kind.Plain) {
            repeat(steps) {
                val at = random.nextInt(doc.document.text.length - STEP_CHARS)
                doc.document.beginEditGroup()
                doc.replace(at, STEP_CHARS, "#".repeat(STEP_CHARS))
                doc.document.endEditGroup()
            }
        }
        when (kind) {
            // A save, without the fake provider's byte-by-byte write: the file becomes the text and
            // the journal turns clean on it, exactly as SaveCoordinator.applySaved does.
            Kind.Clean -> {
                val bytes =
                    doc.document.text
                        .toString()
                        .toByteArray()
                provider.put(doc.uri, bytes)
                doc.journal.resumeClean(BaseFingerprint.of(bytes, 0))
            }

            Kind.Snapshot -> {
                doc.journal.resume(doc.base, doc.document.snapshot())
            }

            else -> {
                Unit
            }
        }
    }

    private fun TestScope.fixture(
        root: File,
        provider: FakeDocumentProvider,
    ) = StoreFixture(
        root,
        provider,
        UnconfinedTestDispatcher(testScheduler),
        FakeMemorySource(maxHeap = HEAP_MB * MB, memoryClassMegabytes = HEAP_MB.toInt()),
    )

    private enum class Kind(
        val label: String,
    ) {
        Plain("plain open, no journal"),
        Clean("clean journal (saved, then killed): History only"),
        Snapshot("dirty journal after compaction: Snapshot + History"),
        Ops("dirty journal: the tail as ops after the base"),
    }

    private companion object {
        const val SEED = 20_261_102
        const val STEP_CHARS = 2_000
        const val NANOS_PER_MILLI = 1_000_000L
        const val KIB = 1024
        const val HEAP_MB = 4_096L
    }
}
