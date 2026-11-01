#!/usr/bin/env python3
"""Turns the Macrobenchmark JSON from the CI emulator into rubric #4 numbers.

Usage:
    perf_report.py RESULTS_DIR BASELINE_JSON [--summary FILE] [--out FILE] [--write-baseline]

Reads every *benchmarkData.json under RESULTS_DIR (the benchmark module's
connected_android_test_additional_output), derives one number per metric
(medians over iterations), prints a Markdown table, appends it to
--summary (the job summary), writes the flat numbers to --out, and compares
them with BASELINE_JSON (docs/perf/emulator-baseline.json).

The gate is deliberately loose, because an emulator on a shared CI runner
is noisy: it fails only on a REGRESSION of more than 25% against the
committed baseline, and only when the change is also bigger than the
metric's noise floor. The quality-bar budgets are printed as ADVISORY: an
emulator is neither a Pixel 6a nor slower in a predictable way, so a
budget is proved on a phone, not here (docs/quality-bar.md §4).

It also fails when a measurement is missing, because that means the
benchmark no longer measures what it says (no keystroke reached the
editor, the Preview never reported a render).
"""

import argparse
import glob
import json
import os
import statistics
import sys

REGRESSION = 0.25

# name -> (unit, advisory budget or None, noise floor in the metric's unit, description)
METRICS = {
    "coldStart.timeToInitialDisplayMs": ("ms", None, 50, "Cold start, first frame"),
    "coldStart.timeToFullDisplayMs": ("ms", 800, 50, "Cold start to an editable document"),
    "open1Mb.openMs": ("ms", 1000, 50, "Open 1 MB"),
    "open20Mb.openMs": ("ms", 3000, 200, "Open 20 MB"),
    "typing1Mb.commitMeanMs": ("ms", 16, 1, "Keystroke to commit, mean, 1 MB"),
    "typing1Mb.commitMaxMs": ("ms", 16, 4, "Keystroke to commit, worst, 1 MB"),
    "typing1Mb.frameCpuP50Ms": ("ms", None, 2, "Typing frames, CPU p50, 1 MB"),
    "typing1Mb.frameCpuP99Ms": ("ms", 16, 4, "Typing frames, CPU p99, 1 MB"),
    "typing20Mb.commitMeanMs": ("ms", 16, 1, "Keystroke to commit, mean, 20 MB"),
    "typing20Mb.commitMaxMs": ("ms", 16, 4, "Keystroke to commit, worst, 20 MB"),
    "typing20Mb.frameCpuP50Ms": ("ms", None, 2, "Typing frames, CPU p50, 20 MB"),
    "typing20Mb.frameCpuP99Ms": ("ms", 16, 4, "Typing frames, CPU p99, 20 MB"),
    "fling20Mb.jankyPercent": ("%", 5, 3, "Fling 20 MB, janky frames"),
    "fling20Mb.frameCpuP50Ms": ("ms", None, 2, "Fling 20 MB, frame CPU p50"),
    "fling20Mb.frameCpuP90Ms": ("ms", None, 2, "Fling 20 MB, frame CPU p90"),
    "fling20Mb.frameCpuP99Ms": ("ms", 16, 4, "Fling 20 MB, frame CPU p99"),
    "fling20Mb.framesOver32MsPercent": ("%", 0, 2, "Fling 20 MB, frames over 32 ms"),
    "previewRefresh50Kb.meanMs": ("ms", 150, 20, "Preview refresh after typing, mean, 50 KB"),
    "previewRefresh50Kb.maxMs": ("ms", 150, 30, "Preview refresh after typing, worst, 50 KB"),
}

# Counts that must be non-zero, or the benchmark measured nothing.
REQUIRED_COUNTS = ["typing1Mb.commitCount", "typing20Mb.commitCount", "previewRefresh50Kb.count"]

FRAME_MS = 1000.0 / 60.0


def load_benchmarks(results_dir):
    found = {}
    for path in glob.glob(os.path.join(results_dir, "**", "*benchmarkData.json"), recursive=True):
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        for bench in data.get("benchmarks", []):
            found[bench["name"]] = bench
    return found


def metric_runs(bench, name):
    return bench.get("metrics", {}).get(name, {}).get("runs", [])


def median_of(bench, name):
    runs = metric_runs(bench, name)
    return statistics.median(runs) if runs else None


def sampled(bench, name):
    return bench.get("sampledMetrics", {}).get(name, {})


def percentile(values, p):
    if not values:
        return None
    ordered = sorted(values)
    k = (len(ordered) - 1) * p / 100.0
    lo = int(k)
    hi = min(lo + 1, len(ordered) - 1)
    return ordered[lo] + (ordered[hi] - ordered[lo]) * (k - lo)


def frames(bench, name):
    runs = sampled(bench, name).get("runs", [])
    return [v for run in runs for v in run]


def mean_per_section(bench, label):
    sums, counts = metric_runs(bench, label + "SumMs"), metric_runs(bench, label + "Count")
    means = [s / c for s, c in zip(sums, counts) if c]
    return (statistics.median(means) if means else None), sum(counts)


def derive(benchmarks):
    out = {}

    def put(key, value):
        if value is not None:
            out[key] = round(float(value), 2)

    b = benchmarks.get("coldStart")
    if b:
        put("coldStart.timeToInitialDisplayMs", median_of(b, "timeToInitialDisplayMs"))
        put("coldStart.timeToFullDisplayMs", median_of(b, "timeToFullDisplayMs"))
    for name in ("open1Mb", "open20Mb"):
        b = benchmarks.get(name)
        if b:
            put(name + ".openMs", median_of(b, "openFirstMs"))
    for name in ("typing1Mb", "typing20Mb"):
        b = benchmarks.get(name)
        if b:
            mean, count = mean_per_section(b, "commit")
            put(name + ".commitMeanMs", mean)
            put(name + ".commitMaxMs", median_of(b, "commitMaxMs"))
            put(name + ".commitCount", count)
            cpu = frames(b, "frameDurationCpuMs")
            put(name + ".frameCpuP50Ms", percentile(cpu, 50))
            put(name + ".frameCpuP99Ms", percentile(cpu, 99))
    b = benchmarks.get("fling20Mb")
    if b:
        cpu = frames(b, "frameDurationCpuMs")
        overrun = frames(b, "frameOverrunMs")
        put("fling20Mb.frameCpuP50Ms", percentile(cpu, 50))
        put("fling20Mb.frameCpuP90Ms", percentile(cpu, 90))
        put("fling20Mb.frameCpuP99Ms", percentile(cpu, 99))
        put("fling20Mb.frameCount", len(cpu))
        if overrun:
            # A frame is janky when it missed its deadline (overrun > 0); it
            # took over 32 ms when it overran by more than one more frame.
            put("fling20Mb.jankyPercent", 100.0 * sum(1 for v in overrun if v > 0) / len(overrun))
            put("fling20Mb.framesOver32MsPercent", 100.0 * sum(1 for v in overrun if v > FRAME_MS) / len(overrun))
    b = benchmarks.get("previewRefresh50Kb")
    if b:
        mean, count = mean_per_section(b, "previewRefresh")
        put("previewRefresh50Kb.meanMs", mean)
        put("previewRefresh50Kb.maxMs", median_of(b, "previewRefreshMaxMs"))
        put("previewRefresh50Kb.count", count)
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("results_dir")
    parser.add_argument("baseline")
    parser.add_argument("--summary")
    parser.add_argument("--out")
    parser.add_argument("--write-baseline", action="store_true")
    args = parser.parse_args()

    benchmarks = load_benchmarks(args.results_dir)
    numbers = derive(benchmarks)
    baseline = {}
    if os.path.exists(args.baseline):
        with open(args.baseline, encoding="utf-8") as f:
            baseline = json.load(f).get("metrics", {})

    failures = []
    lines = [
        "### Emulator performance (API 34, not a device)",
        "",
        "Absolute budgets are **advisory** on an emulator; the gate is a >25% regression",
        "against `docs/perf/emulator-baseline.json` (beyond each metric's noise floor).",
        "",
        "| Metric | Emulator | Baseline | Change | Budget (advisory) |",
        "|---|---|---|---|---|",
    ]
    for key, (unit, budget, floor, label) in METRICS.items():
        value = numbers.get(key)
        if value is None:
            failures.append(f"{key}: not measured")
            lines.append(f"| {label} | **missing** | | | |")
            continue
        base = baseline.get(key)
        change = ""
        if base is not None:
            delta = value - base
            change = f"{delta:+.1f} {unit}" + (f" ({delta / base:+.0%})" if base else "")
            if delta > floor and (base == 0 or delta / base > REGRESSION):
                failures.append(f"{key}: {value} {unit} vs baseline {base} {unit}")
                change = "**" + change + " regression**"
        advisory = ""
        if budget is not None:
            advisory = f"{'within' if value <= budget else 'OVER'} {budget} {unit}"
        shown_base = "" if base is None else f"{base} {unit}"
        lines.append(f"| {label} | {value} {unit} | {shown_base} | {change} | {advisory} |")
    for key in REQUIRED_COUNTS:
        if not numbers.get(key):
            failures.append(f"{key}: zero, so the benchmark measured nothing")
    lines.append("")
    lines.append(
        "Counts: "
        + ", ".join(f"{k} = {numbers.get(k, 0):g}" for k in REQUIRED_COUNTS + ["fling20Mb.frameCount"])
    )
    report = "\n".join(lines) + "\n"
    print(report)
    if args.summary:
        with open(args.summary, "a", encoding="utf-8") as f:
            f.write(report)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump({"metrics": numbers}, f, indent=2, sort_keys=True)
            f.write("\n")
    if args.write_baseline:
        about = {}
        if os.path.exists(args.baseline):
            with open(args.baseline, encoding="utf-8") as f:
                about = {k: v for k, v in json.load(f).items() if k.startswith("_")}
        with open(args.baseline, "w", encoding="utf-8") as f:
            json.dump({**about, "metrics": numbers}, f, indent=2, sort_keys=True)
            f.write("\n")
    if failures:
        for failure in failures:
            print(f"::error::{failure}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
