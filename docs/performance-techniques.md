# Proven techniques we're borrowing

Research done 2026-11-01. A research agent read the source of VS Code,
CodeMirror 6, sora-editor and Mercurial, plus AOSP, LevelDB's docs and
VS Code's engineering blog. Key numbers were spot-checked against the
cloned source: VS Code's `AverageBufferSize = 65535`, sora's
`maxIPCTextLength` cap, Mercurial's `LIMIT_DELTA2TEXT = 2`, and sora's
LGPL licence text.

The design changes are written into `robustness.md`. This file records
where each idea came from, so a future reader can check the source.

| Technique | Source | Where it lives in our app |
|---|---|---|
| Piece tree: one red-black tree whose nodes store `size_left` and `lf_left`; immutable 64K chunks with `lineStarts`; CRLF never split across pieces; append-in-place fast path; snapshots | VS Code `src/vs/editor/common/model/pieceTreeTextBuffer/` (MIT); ["Text Buffer Reimplementation"](https://code.visualstudio.com/blogs/2018/03/23/text-buffer-reimplementation) | `core/text` |
| Persistent summary B-tree (an upgrade path if snapshot copies cost too much) | Zed `crates/sum_tree` (Apache-2.0) | `core/text` (future) |
| Per-line layout cache keyed by line ID, shifted on insert/delete; `RenderNode` per line; 32K IPC window for the keyboard; non-nesting batch edits | sora-editor `RenderCache`, `RenderNodeHolder`, `EditorInputConnection.java` (**LGPL-2.1, studied only, no code copied**) | `feature/editor` |
| Long-line windows: 2,000 chars unwrapped, 10,000 wrapped | CodeMirror `view/src/viewstate.ts` (MIT) | `feature/editor` |
| Height map: measured lines plus estimated gaps; scroll anchoring by `newTop − oldTop`; a 1,000 px viewport margin; a height scaler above 7M px | CodeMirror `view/src/heightmap.ts`, `editorview.ts` | editor view + preview |
| Line end-state equality stops re-tokenising; idle background slices; heuristic tokens for lines not yet reached | VS Code `textModelTokens.ts`; CodeMirror `language.ts` (sync work 20 ms, idle slices 25 ms, at most 1e5 chars past the viewport) | `core/markdown` tokenizer |
| Fenced-code highlighting as more line state: each language's lexer carries its own small state (open comment, string, template) inside the Markdown tokenizer's per-line state, so the same end-state-equality stop, budgets and heuristic guesses apply to code. Own table-driven lexers, no grammar engine; **the token class names mirror highlight.js's** (`keyword`, `title`, `meta`, …) so Code and Preview group colours the same way. No highlight.js, Prism or TextMate grammar was copied | VS Code `textModelTokens.ts` and CodeMirror `language.ts` (the state-per-line model); highlight.js class names (BSD-3, names only) | `core/markdown/code`, `feature/editor` `SyntaxStyles` |
| `data-line` from markdown-it `token.map` + binary-search scroll sync | VS Code `extensions/markdown-language-features/preview-src/scroll-sync.ts` | `feature/preview` |
| Section-keyed incremental parse with a global references `env`; `content-visibility: auto` | Obsidian (public descriptions only); Chromium | `feature/preview` |
| Log record framing (length + CRC + type), torn-tail tolerance | LevelDB `doc/log_format.md` | `core/history` journal |
| Group-commit backups about 1 s after a change | VS Code `workingCopyBackupTracker.ts` | journal flush policy |
| Atomic write (`.new` → sync → rename) | AOSP `android.util.AtomicFile` | app-private files |
| Snapshot-vs-delta rule (delta > text; chain payload > 2× text; chain cap) | Mercurial `revlogutils/deltas.py` (GPL, the idea only); git pack depth 50 | `core/history` |
| `onTrimMemory` RUNNING_* levels not delivered on API 34+ | AOSP `ComponentCallbacks2.java` | `MemoryGovernor` polls instead |
| Renderer priority + `onRenderProcessGone` recovery that tells a crash from an OOM kill | Android WebView docs | `feature/preview` |
| Baseline Profiles (`profileinstaller`, `androidx.baselineprofile`), Macrobenchmark, StrictMode, LeakCanary, Perfetto | Android tooling | The app's profile is generated on CI's emulator (`:benchmark` `BaselineProfileGenerator`) and built into the perf job's hand-over APK; Macrobenchmark measures rubric #4 there (below); StrictMode is enforced by a seeded monkey run; LeakCanary (test APK only) checks every instrumented test. JankStats is not used: Macrobenchmark's `FrameTimingMetric` reads the same frame data from the trace |

## Host measurements (performance review fixes, 2026-11-01)

Host JVM numbers, so a phone is several times slower; the point is the
before/after ratio. Each fix has a count- or allocation-based regression
test, never a wall-clock threshold. The tokenizer rows come from
`TokenizerBenchmarkTest` (opt-in: `-Pssmarkdown.heavyTests=true`) on its
20 MB, 1.42M-line corpus.

| Finding | Before | After | Regression test |
|---|---|---|---|
| PERF-9 tokenizer heap, one fully tokenised cache | 139 MB retained, a `LineResult` per line | 27 MB retained (≈ 10 B a line of states, interned states, outline), 1,024 lines of tokens | `TokenizerMemoryTest` |
| PERF-9 admission estimate for 20 MB | 58 MB (2.5× + 8 MB) | 138 MB (text 2×, index ½×, two tokenizers 2×, one transient copy 2×) | `MemoryGovernorTest` |
| PERF-15 ten frames of 45 lines at line 585k, before the accurate pass | 5.5 ms first frame, 1.6 ms each later frame; ~23,000 line tokenisations | 95 line tokenisations in all; later frames are cache hits | `TokenizerMemoryTest` |
| PERF-8 find per keystroke with the bar open (20 MB, no match) | 184 ms on the main thread, every key (review) | 0 on the main thread; one cancellable background search per 120 ms burst | `FindControllerTest` |
| PERF-13 state emissions scrolling 1,000 lines in one section | 1,000 | ≤ 11 | `ScrollEmissionsTest` |
| PERF-17 `LineVersions` per in-line keystroke at 1.46M lines | 1.46M ids copied (0.25 ms) | 0 | `EditorCacheWorkTest` |
| PERF-17 `LineLayoutCache` per keystroke | 512 Pairs and boxed keys | < 256 bytes allocated | `EditorCacheWorkTest` |
| PERF-18 height query at 1.46M lines | walk of up to 2,850 blocks, one object per call (0.35 ms a frame) | ≤ 12 Fenwick steps, no allocation | `EditorCacheWorkTest` |
| PERF-9/J `retainedBytesEstimate` vs measured, 20 MB corpus | 13 MB estimated, 27 MB measured | 23 MB estimated (now counts headings, labels, interned states), 27 MB measured | `TokenizerMemoryTest` |
| PERF-A Preview-only edit, one char at the end of 100k lines with 50 headings | whole outline dropped; 100,000 lines re-tokenised on the main thread in 4 ms slices (28 slices to recover) | ≤ 2 lines re-tokenised, off the main thread; 50 headings throughout | `OutlineTrackerTest` |
| PERF-B file writes for a 100-step pinch | 100 serialise + write + rename, on the main thread | 0 mid-pinch; 1 when the fingers lift, on the I/O scope (500 ms debounce) | `ViewMemoryTest` |
| PERF-C custom-theme file reads on the main thread | every composition of the theme controls, plus save and delete | 0 (read ahead on I/O; save and delete suspend onto it) | `ThemeLibraryTest` |
| PERF-D main-thread tokenising in one frame, fence opened at line 0 | up to 16 ms: 8 ms pre-draw + 4 ms idle slice + 4 ms outline slice | ≤ 8 ms + one line for the Code pane; 0 for the outline (background snapshot) | `HighlightSchedulerTest`, `OutlineTrackerTest` |
| PERF-E a line break near the top of 1.42M lines, per tokenizer | 0.98 ms median, 4 arrays × 1.42M entries shifted (review probe) | 30 µs, 0 slots moved, after a 3.5 ms first break that brings the gap there (`TokenizerBenchmarkTest`); line ids likewise | `TokenizerMemoryTest`, `EditorCacheWorkTest` |
| PERF-F pieces after 3,000 scattered edits to a 10k-char text | grows by up to two pieces an edit, never compacted outside the benchmark | compacted off the main thread to below the 2 × (chars/64K) + 1,000 line | `TreeCompactorTest` |
| PERF-H whole-document definition scans while typing beside a `[^1]` citation | 1 per keystroke (3 regexes a line + a JSON hash) | 0; 1 only when a citation or definition changes | `definitions.test.ts` |
| PERF-I budget for a 12 MB stream of unknown size | Normal (admitted as 0 bytes); an `OutOfMemoryError` mid-decode crashed the app | Lean, from the bytes that arrived; an `OutOfMemoryError` declines the file | `DocumentLoaderTest` |
| CC-F the three wall-clock tests | < 1 s, < 2 s, < 10 s | ≤ 16 char reads a char; ≤ 3 lines tokenised and ≤ 1 outline rebuild per edit | `LineTokenizerTest`, `OutlineTest` |

## Code highlighting (host, 2026-11-03)

`CodeHighlightBenchmarkTest` (opt-in, `-Pssmarkdown.heavyTests=true`).
Host JVM in a shared container, so absolute times moved up to 2x between
runs; the ranges below are two runs. A phone is several times slower.

| What | Measured | Budget (quality-bar §4) |
|---|---|---|
| Full accurate pass over a 20 MB document of code blocks (1.14M lines, 14 languages) | 0.9–1.5 s plain, 2.1–4.9 s highlighted (+130–225%); 1.8–3.0 µs a line | Never on the main thread in one go: the Code pane's pass stops 100k chars past the viewport, in ≤ 4 ms slices inside the 8 ms frame budget. The outline's full pass runs off the main thread with highlighting off |
| Heap kept by one full tokenise of that document | 14 MB measured either way (10 MB estimated): code state lives in the per-line state's spare bits, tokens stay in the 1,024-line cache | Admission estimate unchanged |
| A keystroke inside a 5,000-line block | 1.0 line re-lexed; 29–98 µs including the idle pass | < 16 ms keystroke to glyph |
| `/*` typed at the top of that block: the frame | 61 lines (the viewport) in 0.14–5.3 ms | ≤ 8 ms main-thread tokenising a frame |
| … idle slices to the 100k-char lookahead | 3,404 lines in 3–5 slices of 4 ms | Bounded by the lookahead: the hard cap |
| … a viewport at the block's end before the pass reaches it | 60 lines guessed from their stored state, 0.2–0.6 ms; exact once the pass or a scroll reaches them | ≤ 8 ms a frame |
| Lines over 2,000 chars | Lexed to 2,000 only | Long lines chunked (robustness.md) |

Big-file mode keeps highlighting on: the work per frame is the lines on
screen, whatever the file size, and the measurements above fit the
budgets with room. Nothing is turned off.

## Emulator measurements (CI, 2026-11-01/02): emulator, not device

**These are emulator numbers, not device numbers.** CI's `perf` job runs
`:benchmark`'s Macrobenchmarks on an API 34 x86_64 emulator (6 GB,
`dalvik.vm.heapgrowthlimit` set to a Pixel 6a's 256m) against the
benchmark build (release code, R8, not debuggable, the generated Baseline
Profile installed). An emulator on a shared runner is noisy, and is
neither faster nor slower than a phone in a predictable way, so the
quality-bar budgets are **advisory** here; the job fails only when a
central metric passes 2x `docs/perf/emulator-baseline.json` (below). The budgets are
proved on a Pixel 6a-class phone (still to do). Medians of each run's
iterations; frames pooled over iterations. Two complete runs, to show the
noise: A = run 41361377483 (a1c059b; its TTFD from 41358849555) and
B = run 41364011716 (6415bf2), the first all-green run of the job. The
committed baseline is the median of A, B and a third run, C (below).

| Measurement | Run A | Run B | Budget (quality-bar §4) | How |
|---|---|---|---|---|
| Cold start, first frame (TTID) | 870 ms | 521 ms | — | `StartupTimingMetric`, 10 cold starts |
| Cold start to an editable document (TTFD) | 878 ms | 592 ms | < 800 ms | `reportFullyDrawn` when a document is bound (`ReportDrawnWhen`) |
| Open 1 MB | 254 ms | 169 ms | < 1 s | `SSM:open`, request to bound buffer, 5 cold opens through `content://` |
| Open 20 MB | 662 ms | 336 ms | < 3 s | the same |
| Keystroke to commit, 1 MB (mean / worst) | 1.97 / 4.48 ms | 1.79 / 4.67 ms | < 16 ms | `SSM:commit` per key, 30 hardware keys × 3 |
| Keystroke to commit, 20 MB (mean / worst) | 2.38 / 8.69 ms | 1.37 / 2.86 ms | < 16 ms, flat with size | the same |
| Typing frames (CPU), 1 MB p50 / p99 | 5.4 / 9.0 ms | 4.0 / 8.0 ms | 60 fps | `FrameTimingMetric` |
| Typing frames (CPU), 20 MB p50 / p99 | 5.1 / 10.8 ms | 4.1 / 9.2 ms | 60 fps | `FrameTimingMetric` |
| Fling 20 MB, janky frames (missed deadline) | 4.9% | 3.6% | 60 fps | `FrameTimingMetric`, 12 flings × 3, ~10 s each |
| Fling 20 MB, frames over 32 ms | 0.23% | 1.32% | none | the same |
| Fling 20 MB, frame CPU p50 / p90 / p99 | 4.6 / 7.0 / 18.8 ms | 3.0 / 4.4 / 18.2 ms | — | the same |
| Preview refresh after typing, 50 KB (mean / worst) | 17.7 / 19.8 ms | 16.9 / 18.1 ms | < 150 ms | `SSM:previewRefresh`, host edit to the page's `rendered`, Split |

Read with care:
- The runner-to-runner noise is large. A third run, C = run 41364018233,
  on the same commit as B, failed the old 25% gate (typing frame p99 at
  20 MB, 15.4 ms against B's 9.2) while B passed. Across A, B and C,
  central metrics moved up to 1.8x (open 20 MB: 336 ms in B, 598 ms in C)
  and tails up to 2.6x (worst keystroke at 20 MB: 2.86 vs 7.42 ms). No
  single-run gate tighter than that spread can be stable, so:
  - **Gated**: start-up, open, mean keystroke, frame p50 and mean Preview
    refresh (each already the median of the benchmark's iterations, or of
    all frames), failing only past **2x** the baseline and the metric's
    noise floor. The baseline is the median of A, B and C, so no one
    runner sets it. This still fails the regressions worth catching on an
    emulator: an algorithmic one costs well over 2x.
  - **Reported, never gated**: worst keystroke, frame p90/p99, janky
    frames, frames over 32 ms and worst refresh. They are in the job
    summary with their change against the baseline, for a human to read.
  - `benchmark/ci/test_perf_report.py` (in the `checks` job) replays A, B
    and C against the committed baseline and against each other (all must
    pass) and checks that a 2.5x open or a keystroke cost that grows with
    the file still fails.
  - Smaller regressions, and every budget, are proved on a phone.
- Keystroke cost does not grow with the file: 1.4–2.4 ms mean at 20 MB
  against 1.8–2.0 ms at 1 MB, and the typing frames are flat.
- Cold start to editable: 878 ms in A (over the 800 ms budget), 592 ms in
  B. Amber until a phone says which.
- The 20 MB fling has 0.2–1.3% of frames over 32 ms on the emulator,
  where the budget allows none; the frame CPU p99 is 18 ms in both runs.
  This is the number most worth a phone trace.
- The Preview refresh is from the moment the host sends the edit (as the
  key lands) to the page's report that it drew it.

## Libraries

| Library | Licence | Verdict |
|---|---|---|
| VS Code piece tree | MIT | Port the algorithm **and its fuzz tests** to Kotlin, with attribution |
| java-diff-utils | Apache-2.0 | Use for Myers, or hand-roll for a tighter memory cap |
| Okio | Apache-2.0 | Use for buffered I/O |
| commonmark-java | BSD-2 | Use only if JVM-side parsing with source spans is needed |
| markdown-it, KaTeX, Mermaid, DOMPurify, morphdom | MIT / MIT / MIT / Apache-2.0 or MPL-2.0 / MIT | Use in the preview bundle (verify each licence file when vendoring) |
| @lezer/markdown | MIT | Study as an incremental CommonMark reference |
| sora-editor | **LGPL-2.1** | Study only |
| android-tree-sitter | LGPL | Avoid |
| Zed `rope` | **GPL-3.0+** | Don't copy |

## Unverified (check before relying on them)

- VS Code's `maxTokenizationLineLength` default.
- Mermaid `maxTextSize` / `maxEdges` defaults.
- The exact overflow point for Blink layout.
- Mercurial's `max_chain_len` default.
- Which Android versions or providers ignore truncation with `"w"`.
- Whether `profileinstaller` keeps F-Droid builds reproducible.
