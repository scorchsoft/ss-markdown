# Roadmap

Design was signed off on 2026-11-01. Andrew delegated the decision to
Claude's judgement against the design gate in `rubric.md`. Remaining
polish is settled in build, against real code, not on paper.

Every iteration ends with these things:
- a scored rubric row, with an independent reviewer plus our own score;
- an updated `edge-cases.md` showing status changes;
- **from iteration 2 on,** a minified APK on Drive (see CLAUDE.md
  conventions);
- **from iteration 2 on,** refreshed light and dark screenshots in
  `docs/screenshots/` from the harness, with the README updated to
  show them.

## Iteration 1 — Foundations and the text engine

**Scaffold.** `apps/android` mirrors `scorchsoft/measur3d`:
- version catalogue and `build-logic` convention plugins;
- no Gradle wrapper; Robolectric pinned to sdk 34;
- release unit tests disabled;
- `-Pssmarkdown.minifyDebug`.

It also includes:
- the module graph from `engineering.md`, as empty modules with
  convention plugins;
- the module-graph check;
- ktlint, detekt and Android lint, with zero warnings;
- GitHub Actions CI (build, unit tests, lint, graph check);
- an app shell that launches to an empty Compose screen using the
  design tokens.

**`core/text`** (pure JVM):
- a piece tree ported from VS Code (MIT, attributed);
- size/line-feed augmentation and 64K immutable chunks;
- CRLF and surrogate-safe chunking; EOL detection and re-emit;
- snapshots, compaction, and grapheme helpers;
- undo as piece operations.

Tests for `core/text`:
- a port of VS Code's piece-tree test cases;
- property tests against a `StringBuilder` reference over millions of
  random edits;
- JMH or kotlinx-benchmark microbenchmarks (1 / 20 / 100 MB load, edits,
  line lookup).

**`core/history` journal** (pure JVM):
- LevelDB-style record framing (length + CRC32C + sequence) and a header
  holding the base file's SHA;
- torn-tail tolerance; replay; group-commit policy as an interface;
- property tests (random operations, a torn last record, the wrong
  base).

**ADR 0001, editor engine.** Host-side evidence comes from the piece-tree
benchmarks plus a prototype `MarkdownCodeView` that renders visible lines
from `core/text`. The on-device comparison against `EditText` and Sora
Editor needs a physical phone: Andrew runs a benchmark APK and the ADR
records the numbers. **Decision default if no device data:** our own
view.

## Iteration 2 — Editor view

- `MarkdownCodeView`:
  - virtualised lines and layout cache (line ID + version);
  - `RenderNode`s, long-line windows, no-wrap, gutter;
  - selection handles, magnifier, accessibility tree.
- The lazy, time-budgeted tokenizer (`core/markdown`), including setext
  and reference handling.
- A custom `InputConnection` (32K window, batch rules), plus the
  keyboard matrix.
- The formatting strip with symbols page, undo pinned, and peek.
- The first dogfood APK: open, edit, save one file in Code mode.

## Iteration 3 — Files and "never lose work"

- `DocumentStore`, SAF open/save/tree, the save pipeline (backup → `wt` →
  verify), fingerprints and the outside-change flow, the conflict sheet
  and three-way merge.
- Journal wired in, with the recovery banner.
- `MemoryGovernor`; config-change rules; the kill test, rotation matrix
  and fault-injection provider in CI.

## Iteration 4 — Preview

- The preview bundle (markdown-it + plugins, KaTeX in a worker, Mermaid
  budgeted).
- Sectioned incremental render and DOM virtualisation.
- Anchor sync and the breadcrumb; Split view.
- Tap-to-edit with the inline source map, plus the table editor.
- The diagram viewer.

## Iteration 5 — History, folders, Navigator, share-in, export, themes

- Version history (reverse deltas, thinning, pins).
- Folder mode and Save all.
- Navigator (outline, quick switch, drafts) and the section menu.
- Share-in.
- Export images and PDF/A4.
- Built-in themes, JSON import, then the theme editor and fonts.

## Iteration 6 — Polish and release candidate

- Every edge-case row `tested`.
- Baseline Profiles and the performance budgets met.
- Accessibility pass; the open-source repo hygiene set.
- Andrew walks J1–J10 on his phone; independent review; scorecard
  reaches ≥ 4.0 with nothing below 3.
