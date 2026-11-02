# ADR 0001: The editor engine

- **Status:** Proposed. The decision is provisional until the device numbers below are in.
- **Date:** 2026-11-01
- **Deciders:** Andrew (Scorchsoft), with the editor-engine spike as evidence
- **Gate:** `docs/robustness.md`, Part 2, "Gate — the editor-engine spike"

## Context

The Code pane is where people spend most of their time, and it is the part
of the app most likely to fail on the files our research says other editors
fail on: a 20 MB export, a 100 MB log pasted into a `.md`, a 5 MB base64 image
on one line (`docs/research-findings.md`, `docs/edge-cases.md`).

`docs/robustness.md` Part 2 sets the contract: a file's size decides how much
*memory* we need, never how much *work* each frame or keystroke costs. That
means the view draws only the visible lines, never lays out a whole long line,
and hands the keyboard at most a 32K-char window.

The text model is already built and tested on the host: `core/text`, a piece
tree after VS Code's design. The open question is the view on top of it.

## Options

### A. Platform `EditText`

- **For:** free; every keyboard, accessibility service, selection handle and
  text-action toolbar already works; no code to maintain.
- **Against:** it needs the whole document as one `Editable` (a second full
  copy of the text in UTF-16) and lays out the whole document with
  `DynamicLayout`. `setText` must run on the main thread, so opening a large
  file freezes the UI for as long as layout takes. No line-number gutter,
  no long-line windowing, no control over the IME window, so select-all on a
  large file is an ANR. Wrapping it in a `ScrollView` (the usual way to get a
  full-screen editor) makes it measure its full height up front.
- **Expectation:** fine at 1 MB, unusable at 20 MB, out of memory or an ANR at
  100 MB.

### B. Sora Editor (`io.github.Rosemoe.sora-editor:editor`)

- **For:** a mature, virtualised Android code editor: line cache, render nodes,
  IME window limit, selection handles, gutter. Much of our design studies it.
- **Against:** **LGPL-2.1**. Shipping it in an Apache-2.0 app is possible but
  brings relinking obligations and a licence story we would have to explain
  on F-Droid and in every fork. Its text model (`Content`, an array of
  `ContentLine` char arrays) is its own, so we would hold the document twice
  or give up our piece tree, our undo, our journal integration and our
  line-ending round trip. Its Markdown support is through TextMate/tree-sitter
  grammars (tree-sitter on Android is LGPL too). We would be extending
  someone else's large view rather than owning the one screen that matters.
- **Use here:** a benchmark baseline only, in `:tools:editor-bench`, which is
  never shipped and which `checkModuleGraph` forbids any module to depend on.

### C. Our own `MarkdownCodeView` on `core/text`

- **For:** draws straight from the piece tree, so no second copy of the text;
  every robustness.md rule (visible lines only, long-line windows, 32K IME
  window, cache by line identity + version) is ours to guarantee and test;
  Apache-2.0 throughout; the undo, journal and line-ending model are used as
  designed.
- **Against:** we must build what `EditText` gives for free: selection handles,
  magnifier, text-action toolbar, accessibility node tree, and a keyboard
  connection that survives Gboard, Samsung Keyboard and HeliBoard. The
  `InputConnection` is the highest-risk component and carries its own test
  matrix.

## The spike

Built to produce the evidence for this decision (iteration 1):

- `feature/editor`: `MarkdownCodeView`, a prototype of option C. It draws only
  the visible lines (laying out a few margin lines per frame, biased to the
  scroll direction), with a fixed line height, a line-number gutter, vertical
  and horizontal fling through `OverScroller`, long-line windows of 2,000
  chars, a per-line layout cache keyed by line index (shifted on edits) plus
  document version, a caret with tap-to-place, and a `BaseInputConnection`
  adapter over an Android-free `EditingSession` (commit, composing, surrounding
  deletes, 32K-capped reads, extracted-text windows, Enter and Backspace key
  events). 63 host tests, including Robolectric tests of the layout maths, the
  caret mapping on a 1 MB line and the keyboard verbs applied to the document.
- `tools/editor-bench`: one APK (`com.scorchsoft.ssmarkdown.bench`) that opens
  the same generated Markdown in `EditText` (wrap and no-wrap), the spike and
  Sora, and measures the four numbers below on a 1 MB, 20 MB and 100 MB
  corpus and a 1 MB and 5 MB single-line corpus. Failures (OOM, a main-thread
  block over 10 s, a process kill) are recorded as results, not crashes.

## Decision

**Build our own `MarkdownCodeView` (option C), pending the device numbers.**

The host evidence already shows the text model is not the bottleneck at any
size we promise. The device run must confirm that the view keeps that
property, and that `EditText` and Sora do not offer enough to outweigh the
licence and the second copy of the text. If the device numbers show our view
missing a budget that Sora meets, we revisit this ADR before building on it.

## Evidence

### Host (JVM, `core/text`, measured when the piece tree was finished)

| Operation | Result |
|---|---|
| Load 100 MB into the piece tree | ~0.5 s |
| 10,000 random edits | 17 ms |
| 100,000 random offset↔line lookups | 121 ms |
| Snapshot for a background reader | 1–2 ms |

So an edit or a line lookup costs microseconds at 100 MB, and the view can
afford to ask the tree for every visible line on every frame.

### Device (to be filled in from `:tools:editor-bench`, "Run all" → "Copy results")

Budgets come from `docs/quality-bar.md`. **open** is read + build + first
frame; **main** is the part of it that blocks the UI thread; **jank** is the
share of frames over 16 ms / 32 ms during a 5 s scripted fling; **key** is
`commitText` to the next frame drawn, averaged over 200 inserts mid-document.

Pixel 6a-class phone (model: ____, Android ____, heap limit ____ MB, ____ Hz):

| Engine | Corpus | open ms | main ms | heap MB | jank >16 % | jank >32 % | key avg ms | key p95 ms | note |
|---|---|---|---|---|---|---|---|---|---|
| EditText wrap | 1 MB | | | | | | | | |
| EditText nowrap | 1 MB | | | | | | | | |
| MarkdownCodeView | 1 MB | | | | | | | | |
| Sora | 1 MB | | | | | | | | |
| EditText wrap | 20 MB | | | | | | | | |
| EditText nowrap | 20 MB | | | | | | | | |
| MarkdownCodeView | 20 MB | | | | | | | | |
| Sora | 20 MB | | | | | | | | |
| EditText wrap | 100 MB | | | | | | | | |
| EditText nowrap | 100 MB | | | | | | | | |
| MarkdownCodeView | 100 MB | | | | | | | | |
| Sora | 100 MB | | | | | | | | |
| EditText wrap | 1 MB one line | | | | | | | | |
| EditText nowrap | 1 MB one line | | | | | | | | |
| MarkdownCodeView | 1 MB one line | | | | | | | | |
| Sora | 1 MB one line | | | | | | | | |
| EditText wrap | 5 MB one line | | | | | | | | |
| EditText nowrap | 5 MB one line | | | | | | | | |
| MarkdownCodeView | 5 MB one line | | | | | | | | |
| Sora | 5 MB one line | | | | | | | | |

3 GB low-end phone (model: ____, Android ____, heap limit ____ MB, ____ Hz):

| Engine | Corpus | open ms | main ms | heap MB | jank >16 % | jank >32 % | key avg ms | key p95 ms | note |
|---|---|---|---|---|---|---|---|---|---|
| EditText wrap | 1 MB | | | | | | | | |
| MarkdownCodeView | 1 MB | | | | | | | | |
| Sora | 1 MB | | | | | | | | |
| MarkdownCodeView | 20 MB | | | | | | | | |
| Sora | 20 MB | | | | | | | | |
| MarkdownCodeView | 100 MB | | | | | | | | |
| Sora | 100 MB | | | | | | | | |
| MarkdownCodeView | 5 MB one line | | | | | | | | |
| Sora | 5 MB one line | | | | | | | | |

Keyboard matrix (type a paragraph with autocorrect, then Backspace through it):

| Keyboard | MarkdownCodeView | Notes |
|---|---|---|
| Gboard | | |
| Samsung Keyboard | | |
| HeliBoard | | |
| Hardware keyboard | | |

### Reading the numbers

- The bench is a release build (not debuggable) signed with a debug key, not
  minified. Run it with the phone cool and charged, nothing else open.
- "main ms" over 10,000 is reported as a timeout; the main thread cannot be
  interrupted, so the run waits for it. If the system kills the app, the next
  launch records "killed while …" for that row.
- The keystroke time ends when the UI thread finishes the frame; the GPU part
  is in the jank numbers instead.
- Synthetic touches drive each engine's own fling code, so the jank numbers
  include each engine's fling physics as well as its drawing.

## Consequences

- `feature/editor` owns the view, its `InputConnection` and its gestures. The
  next iterations add, in order of risk: the keyboard matrix on real devices,
  selection with native handles and the text-action toolbar, the accessibility
  node tree, Wrap mode (`MeasuredText` + `LineBreaker` off the main thread),
  `RenderNode` per line, the tokenizer from `core/markdown`, and scrollbars with
  fast scroll.
- Known spike limits to remove before shipping: tabs are a fixed 4 cells with
  no tab stops; columns outside a long line's window are estimated as
  monospace cells, so CJK or emoji outside the window drift until scrolled
  into it; the gutter widens as digits are added; extracted-text monitoring
  and cursor-anchor updates are not implemented.
- The layout cache's "stable line identity" is the line index, shifted on
  every reported edit, plus the document version. That is O(cached lines) per
  edit and never wrong (an unreported edit just misses). If profiling shows
  it, the upgrade is per-line IDs in the piece tree.
- Sora Editor stays a benchmark baseline. It lives only in
  `:tools:editor-bench`, its version pin says so, and `checkModuleGraph`
  fails the build if any module depends on `:tools:*`.
- We take on the maintenance of an editor view. The trade is deliberate: the
  editor is the product, and its robustness promises are only as good as our
  control of it.
