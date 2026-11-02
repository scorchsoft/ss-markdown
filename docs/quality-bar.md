# The quality bar — what "exceptional" means, concretely

`rubric.md` scores the product. This file says what each score should
feel like in use: the jobs it must nail, the things it must never do,
the details of how it should feel, and every number in one place.

If a feature can't point to a job on this page, it doesn't ship.

## 1. The ten jobs

Each job has a target, measured from the moment the user decides to act,
on a mid-range phone. **Taps** count deliberate touches; typing a search
counts as one.

| # | Job | Steps (happy path) | Target | Failures we refuse |
|---|---|---|---|---|
| J1 | **Fix a typo in a .md from Files or Drive** | Tap the file in the other app → SS Markdown opens it in Preview at the top → tap the word, fix it → Save | 3 taps, < 10 s; saved in place when the source grants write access | Silently importing a copy; read-only without saying so; "Save" producing a new file. **Expected exception:** Gmail and most email apps hand over read-only copies. Then the Read-only badge shows and Save becomes Save as… (+1 tap). That is honest, not a failure. |
| J2 | **Jump to a section in a 10k-line doc** | Tap the title → type 2–3 letters → tap the heading | 3 taps, < 3 s; lands with the heading at the top | Scrolling by hand; Outline taking > 300 ms to open |
| J3 | **Edit a table on a phone** | Tap a cell in Preview → type → Next cell → … → Save | No raw pipes seen; nothing else in the file changes | Table reformatting elsewhere; keyboard covering the cell; losing the cell on rotation |
| J4 | **Get my work back after a crash or a dead battery** | Reopen the app | 0 taps to see the banner "Your unsaved edits are back"; ≤ 1 s of typing lost | A blank editor; a silent restore over a newer file |
| J5 | **Another app changed the file while I had edits** | Reopen → sheet → Merge my edits → resolve 1 overlap → Save | Never loses either side; 3–4 taps | Our old text overwriting the new file; the old undo stack reaching into new content |
| J6 | **Export every diagram for a slide deck** | ⋮ → Export images… → Export | 3 taps to separate files in Downloads (the default); ZIP adds the folder picker once, then it's remembered | Saving each one by hand; blurry PNGs (2× default) |
| J7 | **Tidy several files in a folder, then save them all** | ≡ → file → edit → ≡ → file → edit → Save all | Save all is 1 tap on tablets (docked panel) and 2 on phones (≡ → Save all). Conflicted files are skipped and listed | Losing which files are unsaved; Save all overwriting an outside change |
| J8 | **Read a long doc in bed, in the dark, at a larger size** | Open → Preview (remembered) → pinch | Pinch reflows text smoothly; no white flash; the size is remembered | Bitmap-scaled blur; a white flash on load; the zoom resetting next time |
| J9 | **Capture a snippet from Chrome into my notes file** | Share → SS Markdown → Append to notes.md | 2–3 taps; I stay in Chrome; the sheet confirms | Opening the full app; racing the editor; appending to a stale copy |
| J10 | **Copy one section into a chat or email** | Long-press the heading → Copy section | 2 taps; exact Markdown, or rich text on request | Copying the whole file; smart quotes inside code |

The rubric's **Power depth** and **Editing ergonomics** criteria are
scored by walking these jobs on a device.

## 2. Anti-goals: what we will never do

- No accounts, sync service, ads, analytics, tracking or "what's new"
  pop-ups.
- No onboarding carousel or tutorial overlay. The first launch is an
  editor with a cursor in it.
- **No modal dialog on the happy path.** Dialogs exist only to protect
  work: unsaved-changes prompts and the conflict sheet.
- Never two toolbar rows. The chrome at rest is two bars.
- Never a spinner on a keystroke. Never block typing on I/O, parsing or
  rendering.
- Never rewrite bytes the user didn't touch.
- **No one-sided accent borders** (a coloured bar on one edge of a card,
  row or callout) as a highlight. It's a generic "AI-made UI" tell.
  Selection and "you are here" use a filled tint plus weight or an
  icon; callouts use a tinted box with an icon and label. Plain
  structural dividers between panes are fine.
- No plugins, scripting, Git, encryption, or a built-in spell checker
  (the keyboard does that).
- No feature that needs the network, apart from fetching a font the
  user explicitly chose.

## 3. The premium-feel spec

**Motion**
- Standard easing (Material emphasised), 150 ms for small changes
  (chips, toggles), 200 ms for sheets and the strip flip, and 250 ms for
  layout changes (Code ↔ Split ↔ Preview: a crossfade plus
  anchor-preserving scroll).
- **No animation tied to typing.** Respect "Remove animations"
  (`ANIMATOR_DURATION_SCALE = 0`).

**Haptics**
- A light tick on a mode switch, on save success, and when a pinch zoom
  crosses 100%.
- Nothing else. Errors use a different pattern (double tick) together
  with a visible message.

**Typography and spacing**
- UI: Inter (bundled) at 11 (captions: line numbers, badges) / 12 / 13 / 15 / 18 / 26 sp.
- Code: JetBrains Mono 12.5 sp, line height 1.6.
- Preview body: 15 px at 1.6 line height, ~75 characters per line
  maximum in Fluid mode on wide screens.
- A 4 dp spacing grid. **Touch targets** (tokens):
  - icon buttons 48×48 dp;
  - pills 40 dp tall with 48 dp hit areas;
  - segmented controls 44 dp;
  - strip and symbol keys 48×48 dp (the strip scrolls; never shrink the
    keys);
  - list rows ≥ 44 dp (48 dp for primary rows);
  - chips 40 dp.

**Colour**
- Neutral surfaces. Scorchsoft red means **"needs you"**: the unsaved
  dot, the Save pill, Save all and errors. It is never decoration.
- Every token pair meets WCAG AA, and the Contrast theme meets AAA.
- **Semantic tokens** (and nothing else carries colour):
  - **Needs you** — red (`#B3191B` / `#C9302F` dark): unsaved, Save,
    Save all, errors.
  - **Info** — blue (`#1D5DBF` on `#EAF1FC`): recovery, notes and alerts.
  - **Caution** — amber (`#5C4300` on `#FFF4DA`): changed in another app,
    Big-file mode, contrast warnings.
  - **Highlight** — yellow (`#FFF1B8`, current `#F5C542` with an ink
    outline): find matches only.
  - **Diff** — removed text is grey with a strike-through, added text is
    info blue. Never red, because red means "needs you".
  - **Code** — syntax colours *inside fenced code blocks* come from the
    preview theme's code palette (highlight.js). Markdown syntax markers
    in the editor stay ink.
  - **Ink** — everything else that needs emphasis: syntax markers,
    checkboxes, toggles, sliders, the caret, the current table cell and
    handles, pins.
  - Selection uses the system colour.
- Gutter text is `#6E6E73`, which passes AA on both the page and the
  current-line tint.

**States.** Every surface specifies all of these:
- **Empty:** an untitled scratch doc with "Start writing…" placeholder
  text in the first line. No art, no tips.
- **Loading** (only when > 300 ms): a thin progress line under the top
  bar. Never a full-screen spinner.
- **Error:** an inline message beside the thing that failed, plus a way
  out (Retry / Save as / Show source). Never a dead-end dialog.
- **Read-only:** a badge in the status line plus "Save as…".
- **Big-file mode:** a quiet chip in the status line that
  explains on tap.

**Sound:** none, ever.

## 4. Budgets (every number in one place)

| Area | Budget |
|---|---|
| Cold start to an editable cursor | < 800 ms (Baseline Profile installed) |
| Keystroke to glyph | < 16 ms at any file size |
| Scroll | 60 fps at any file size; no frame > 32 ms in a 10 s fling on a 20 MB file |
| Main-thread tokenising per frame | ≤ 8 ms |
| Preview refresh after typing stops | < 150 ms for the visible section |
| Open 1 MB / 20 MB | < 1 s / < 3 s |
| Navigator open with the outline of 10k headings | < 300 ms |
| Mode switch (Code ↔ Preview) landing | within ±1 block of the anchor, < 250 ms |
| Work lost on crash or power loss | ≤ 1 s of typing |
| Journal fsync | group commit after about 1 s idle, plus on `onPause`/`onStop` |
| Keyboard text window | 32K chars around the selection |
| Accessibility node text | ≤ 100K chars |
| Heap admission | open normally ≤ 40% of free heap; lean ≤ 70%; else decline before loading |
| History | Default limits 100 MB per file and 1 GB total (adjustable or off); thinning only, never silent; chain ≤ 50; Mercurial 2× rule |
| APK size (minified) | < 15 MB including the preview bundle. **A guideline, not a hard limit** (see below) |

**Where each budget is measured.** The budgets are for a Pixel 6a-class
phone, and only a run on one proves them. CI's `perf` job measures the same
things on an API 34 emulator with a 6a-class heap (cold start to an
editable document, open 1 MB / 20 MB, keystroke to commit and typing frames
on 1 MB / 20 MB, a 10 s fling on 20 MB, Preview refresh at 50 KB). Those
numbers are **advisory against these budgets**: an emulator on a shared
runner is noisy and is neither faster nor slower than a phone in a
predictable way. What the job enforces is the trend, and only as far as
a shared runner can honestly show one. A 25% gate was tried and flaked:
the same commit passed on one runner and failed on another (runs
41364011716 and 41364018233), because start-up and open move up to 1.8x
with the runner, and tail metrics (worst keystroke, p99 frames, janky
frames) up to 2.6x. So the job now fails only when a **central** metric
(start-up, open, mean keystroke, frame p50, mean Preview refresh) passes
**2x** `docs/perf/emulator-baseline.json` (the median of three runs) and
its noise floor. That catches the regressions an emulator can see, the
algorithmic ones (work that grows with the file, a lost cache, a
main-thread read); a 20–50% slip it cannot tell from a noisy neighbour.
Tail metrics are **reported** in the job summary with their change and
never gated. Smaller regressions and every tail are a phone's job. The latest figures are in
`performance-techniques.md` ("Emulator measurements").

**The APK size budget is a guideline.** Andrew set this policy on
2026-11-01. The mission comes first: a fast, calm editor that never
loses work.
- **Shrink where it's free.** Make the APK smaller whenever that costs
  nothing the user would notice: no lost feature, no regression, no
  slower start, no weaker robustness.
- **Never trade the mission for size.** Don't drop or weaken anything
  the mission depends on just to get under 15 MB.
- **Raising the limit is allowed.** If going over is the right
  strategic call, raise it deliberately. Record the reason, the new
  figure and what it buys in `product-journey.md` (Decisions), and
  update this row.
- **Silent creep is the failure.** Going over by accident, with nobody
  noticing, breaks the budget. Going over on purpose, with the reason
  written down, does not.

## 5. How we score

1. **Evidence is required** for each criterion: a test name, a benchmark
   number, a screenshot or a recorded job walk-through. A claim with no
   evidence scores 0.
2. **Two scorers.**
   - An *unanchored* reviewer agent sees only the spec, the build or
     mockups, and this file and the rubric. It never sees our
     reasoning or our own scores.
   - We score separately. Any difference of ≥ 1 point on a criterion is
     listed for Andrew with both arguments.
3. **Andrew walks J1–J10 on his own phone** for every release
   candidate. One failed job blocks the release, whatever the average.
4. Scores, evidence links and disagreements are recorded in the
   scorecard in `rubric.md`.
