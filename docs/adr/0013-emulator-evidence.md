# ADR 0013: Performance evidence comes from CI's emulator, gated only where it is stable

- **Status:** Accepted
- **Date:** 2026-11-02
- **Deciders:** Scorchsoft

## Context

Rubric #4 sets budgets for a Pixel 6a-class phone (cold start, open
1 / 20 MB, keystroke to glyph, frames, Preview refresh). There is no phone
in CI, and none in the cloud containers the app is built in. Host tests
can prove costs by count (no per-keystroke work that grows with the file)
but not in milliseconds or frames.

## Options

1. **Wait for a phone** and measure by hand before each release.
2. **Gate the budgets on an emulator.** An emulator is neither faster nor
   slower than a phone in a predictable way.
3. **Measure on CI's emulator as a trend line**, keep the budgets
   advisory there, and keep counts on the host.

## Decision

Option 3. CI's `perf` job runs `:benchmark`'s Macrobenchmarks on an API 34
x86_64 emulator with a 6a-class heap, against the release build with the
generated Baseline Profile, then a seeded monkey with StrictMode
(`benchmark/ci/emulator-evidence.sh`). `perf_report.py` writes every
number to the job summary. It fails the job only when a measurement is
missing, or when a *central* metric (start-up, open, mean keystroke,
frame p50, mean refresh) passes 2x `docs/perf/emulator-baseline.json`
and its noise floor. Tail metrics are reported, never gated.

A first 25% gate flaked: the same commit passed and failed on two runners
(start-up and open move up to 1.8x with the runner, tails up to 2.6x).
2x is outside that spread and still catches algorithmic regressions;
`test_perf_report.py` replays the observed runs to keep it so.

## Consequences

- Every PR carries real frame and timing numbers, and a regression of the
  kind an emulator can see fails CI.
- A 20–50% slip, and every tail metric, is a human's call from the
  summary, and the budgets are proved only by a phone run (still to do;
  `docs/quality-bar.md` §4).
- Host count- and allocation-based tests stay the first line of defence
  (`docs/performance-techniques.md`).
