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
| `data-line` from markdown-it `token.map` + binary-search scroll sync | VS Code `extensions/markdown-language-features/preview-src/scroll-sync.ts` | `feature/preview` |
| Section-keyed incremental parse with a global references `env`; `content-visibility: auto` | Obsidian (public descriptions only); Chromium | `feature/preview` |
| Log record framing (length + CRC + type), torn-tail tolerance | LevelDB `doc/log_format.md` | `core/history` journal |
| Group-commit backups about 1 s after a change | VS Code `workingCopyBackupTracker.ts` | journal flush policy |
| Atomic write (`.new` → sync → rename) | AOSP `android.util.AtomicFile` | app-private files |
| Snapshot-vs-delta rule (delta > text; chain payload > 2× text; chain cap) | Mercurial `revlogutils/deltas.py` (GPL, the idea only); git pack depth 50 | `core/history` |
| `onTrimMemory` RUNNING_* levels not delivered on API 34+ | AOSP `ComponentCallbacks2.java` | `MemoryGovernor` polls instead |
| Renderer priority + `onRenderProcessGone` recovery that tells a crash from an OOM kill | Android WebView docs | `feature/preview` |
| Baseline Profiles (`profileinstaller`), Macrobenchmark on a 10 MB fixture, JankStats, StrictMode, LeakCanary (debug only), Perfetto | Android tooling | StrictMode (penaltyLog) in debuggable builds today; library baseline profiles ship. Macrobenchmark, JankStats and LeakCanary are planned with the device run (`robustness.md` Part 3) |

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
| PERF-F pieces after 3,000 scattered edits to a 10k-char text | ~6,000, never compacted outside the benchmark | compacted off the main thread to below the 2 × (chars/64K) + 1,000 line | `TreeCompactorTest` |
| PERF-H whole-document definition scans while typing beside a `[^1]` citation | 1 per keystroke (3 regexes a line + a JSON hash) | 0; 1 only when a citation or definition changes | `definitions.test.ts` |
| PERF-I budget for a 12 MB stream of unknown size | Normal (admitted as 0 bytes); an `OutOfMemoryError` mid-decode crashed the app | Lean, from the bytes that arrived; an `OutOfMemoryError` declines the file | `DocumentLoaderTest` |
| CC-F the three wall-clock tests | < 1 s, < 2 s, < 10 s | ≤ 16 char reads a char; ≤ 3 lines tokenised and ≤ 1 outline rebuild per edit | `LineTokenizerTest`, `OutlineTest` |

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
