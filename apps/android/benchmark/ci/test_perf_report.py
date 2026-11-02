"""Tests for perf_report's gate, replaying the runs CI actually produced.

Run: python3 -m unittest discover -s apps/android/benchmark/ci -p 'test_*.py'

The fixtures are the medians the perf job printed for three runs of
near-identical code on shared runners. The gate must pass every one of them
against the committed baseline (it did not: run C failed a 25% gate on a
p99 that the same code had measured 40% lower in run B), and must still
fail a regression an emulator can see.
"""

import json
import os
import unittest

import perf_report

KEYS = list(perf_report.METRICS)

# Same order as METRICS. A = a1c059b (run 41361377483), B = 6415bf2 (run
# 41364011716, passed), C = 6415bf2 (run 41364018233, failed the old gate).
RUN_A = [870.06, 878.18, 254.24, 662.24, 1.97, 4.48, 5.41, 9.04, 2.38, 8.69, 5.07, 10.75,
         4.88, 4.63, 6.99, 18.75, 0.23, 17.66, 19.75]
RUN_B = [521.36, 591.73, 169.35, 336.34, 1.79, 4.67, 4.03, 7.97, 1.37, 2.86, 4.13, 9.17,
         3.64, 2.96, 4.41, 18.15, 1.32, 16.91, 18.1]
RUN_C = [728.1, 852.15, 267.53, 598.03, 2.24, 5.68, 6.35, 11.29, 2.29, 7.42, 6.01, 15.38,
         2.95, 4.14, 6.53, 21.21, 0.69, 19.07, 24.42]
COUNTS = {"typing1Mb.commitCount": 90, "typing20Mb.commitCount": 90, "previewRefresh50Kb.count": 18}


# The fling-up metrics were added after these runs, so the replays carry none;
# they take the fling-down run's values, which keeps them measured and changes no verdict.
FLING_UP = {key: key.replace("flingUp1Mb", "fling20Mb") for key in KEYS if key.startswith("flingUp1Mb.")}


def run(values):
    measured = dict(zip(KEYS, values))
    return {**measured, **{up: measured[down] for up, down in FLING_UP.items()}, **COUNTS}


def committed_baseline():
    path = os.path.join(os.path.dirname(__file__), "..", "..", "..", "..", "docs", "perf", "emulator-baseline.json")
    with open(path, encoding="utf-8") as f:
        return json.load(f)["metrics"]


class GateTest(unittest.TestCase):
    def test_every_observed_run_passes_against_the_committed_baseline(self):
        baseline = committed_baseline()
        for name, values in (("A", RUN_A), ("B", RUN_B), ("C", RUN_C)):
            failures, _ = perf_report.compare(run(values), baseline)
            self.assertEqual([], failures, f"run {name} of unchanged code failed the gate")

    def test_every_observed_run_passes_against_every_other_as_baseline(self):
        # The worst case: a baseline recorded on the fastest runner, judged on the slowest.
        for base in (RUN_A, RUN_B, RUN_C):
            for values in (RUN_A, RUN_B, RUN_C):
                failures, _ = perf_report.compare(run(values), run(base))
                self.assertEqual([], failures)

    def test_an_algorithmic_regression_on_a_gated_metric_fails(self):
        # Open 20 MB going from O(n) to work per line would cost far more than this.
        slow = run(RUN_C)
        slow["open20Mb.openMs"] = committed_baseline()["open20Mb.openMs"] * 2.5
        failures, lines = perf_report.compare(slow, committed_baseline())
        self.assertEqual(1, len(failures))
        self.assertIn("open20Mb.openMs", failures[0])
        self.assertTrue(any("regression" in line for line in lines))

    def test_keystroke_cost_that_grows_with_the_file_fails(self):
        slow = run(RUN_A)
        slow["typing20Mb.commitMeanMs"] = 12.0
        failures, _ = perf_report.compare(slow, committed_baseline())
        self.assertTrue(any("typing20Mb.commitMeanMs" in f for f in failures))

    def test_a_change_under_the_noise_floor_never_fails(self):
        baseline = {"typing1Mb.commitMeanMs": 0.2}
        self.assertFalse(perf_report.regressed(1.1, 0.2, 1))
        failures, _ = perf_report.compare({**run(RUN_A), "typing1Mb.commitMeanMs": 1.1}, baseline)
        self.assertEqual([], failures)

    def test_tail_metrics_are_reported_not_gated(self):
        noisy = run(RUN_A)
        noisy["fling20Mb.frameCpuP99Ms"] = 60.0
        failures, lines = perf_report.compare(noisy, committed_baseline())
        self.assertEqual([], failures)
        row = next(line for line in lines if line.startswith("| Fling 20 MB, frame CPU p99"))
        self.assertIn("60.0 ms", row)
        self.assertIn("reported", row)
        self.assertIn("OVER 16 ms", row)

    def test_a_missing_measurement_or_an_empty_benchmark_fails(self):
        partial = run(RUN_A)
        del partial["open1Mb.openMs"]
        partial["typing1Mb.commitCount"] = 0
        failures, _ = perf_report.compare(partial, committed_baseline())
        self.assertEqual(2, len(failures))

    def test_every_metric_is_gated_or_reported(self):
        for key, spec in perf_report.METRICS.items():
            self.assertIn(spec[3], (perf_report.GATED, perf_report.REPORTED), key)


if __name__ == "__main__":
    unittest.main()
