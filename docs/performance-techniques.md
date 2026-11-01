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
| Baseline Profiles (`profileinstaller`), Macrobenchmark on a 10 MB fixture, JankStats, StrictMode, LeakCanary (debug only), Perfetto | Android tooling | CI + `benchmark/` |

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
