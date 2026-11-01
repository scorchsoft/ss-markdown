package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.files.document.killharness.KillRun
import com.scorchsoft.ssmarkdown.files.document.killharness.RunResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

/**
 * The kill test of docs/robustness.md Part 3, on the host: [RUNS]
 * randomised sessions (typing, pastes, undo, autosave, Save, Save as,
 * outside edits, "Don't save" checkpoints in a real History), each killed
 * once at a uniformly random point (between any two main-thread or
 * journal-thread tasks, after any disk hop, or at any byte of a write),
 * sometimes with the last journal write torn and half-written temporary
 * files left behind, then recovered by a fresh store through the real
 * launch scan. See [KillRun] for the model and the invariants:
 *
 * - the recovered text is a text the document really had, at most the
 *   edits of the last group commit behind (≤ [LOSS_BOUND_MILLIS] of typing);
 * - every file on disk holds whole contents, old or new, never a mixture;
 * - no outside edit is ever overwritten without the user choosing to;
 * - every version History kept still reads back verified.
 *
 * Seeded and reproducible. `check` runs [RUNS] seeds (about 30 s); the
 * full thousand runs in CI's kill-test step, or locally with
 * `gradle -p apps/android :feature:files:testDebugUnitTest --tests '*RandomKillTest*' -Pssmarkdown.killRuns=1000`
 * (about 5 min). `-Pssmarkdown.killSeed=<seed>` reruns one seed; the
 * failure message names it.
 */
class RandomKillTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `random kills lose at most the last second and never tear or overwrite a file`() {
        val single = setting("ssm.kill.seed", "SSM_KILL_SEED")?.toLongOrNull()
        val runs = setting("ssm.kill.runs", "SSM_KILL_RUNS")?.toIntOrNull() ?: RUNS
        val seeds = single?.let(::listOf) ?: (0 until runs).map { BASE_SEED + it }
        val results = seeds.map(::killOnce)
        val failures = results.filter { it.failure != null }
        val worst = results.maxBy { it.lossMillis }
        println(
            "Kill test: ${results.size - failures.size}/${results.size} runs passed; " +
                "max loss ${worst.lossMillis} ms of typing (${worst.lostEdits} edits, seed ${worst.seed}); " +
                "${results.count { it.lostEdits == 0 }} lost nothing; " +
                "${results.count {
                    it.tornFile
                }} killed mid-write, ${results.count { it.tornJournal }} with a torn journal, " +
                "${results.count { it.outsideAfterKill }} with an outside edit before relaunch; " +
                "${results.sumOf { it.actions }} actions, ${results.sumOf { it.events }} kill points.",
        )
        assertEquals(
            failures.take(REPORTED).joinToString("\n") { "seed ${it.seed}, killed at ${it.killedAt}: ${it.failure}" },
            0,
            failures.size,
        )
        assertTrue("lost ${worst.lossMillis} ms (seed ${worst.seed})", worst.lossMillis <= LOSS_BOUND_MILLIS)
    }

    private fun setting(
        property: String,
        variable: String,
    ): String? = System.getProperty(property) ?: System.getenv(variable)

    private fun killOnce(seed: Long): RunResult {
        val dry = KillRun(seed, tmp.newFolder("dry-$seed"), killAtEvent = null).execute()
        val at = 1 + Random(seed xor KILL_SALT).nextInt(dry.events)
        val result = KillRun(seed, tmp.newFolder("run-$seed"), killAtEvent = at).execute()
        tmp.root.listFiles()?.forEach { it.deleteRecursively() }
        return result
    }

    private companion object {
        /** The `check` profile; CI's kill-test step and `-Pssmarkdown.killRuns=1000` run the full thousand. */
        const val RUNS = 100
        const val BASE_SEED = 20_261_101L
        const val KILL_SALT = 0x5EED_CAFEL
        const val REPORTED = 10

        /** The group-commit bound: the journal syncs at most a second after the first unsynced edit. */
        const val LOSS_BOUND_MILLIS = 1_000L
    }
}
