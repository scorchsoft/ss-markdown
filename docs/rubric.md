# Definition of good — scoring rubric

Score every criterion from 0 to 5 at the end of each iteration, with
evidence: a test, a measurement or a screenshot.

**Ship bar:** a weighted average of at least **4.0**, and **no criterion
below 3**. The ship bar applies to release candidates.

**Design gate** (sign-off before build): no open Critical or Major
finding from the latest independent review; J1–J10 pass on paper; no
criterion below 3 apart from #4, #5 and #11 (which only code can prove);
sign-off.

Scale: 0 missing · 1 broken · 2 works but frustrating · 3 acceptable ·
4 good, no friction · 5 delightful and measurably excellent.

| # | Criterion | Weight | A "5" looks like | How we measure it |
|---|---|---|---|---|
| 1 | **Never lose work** | 3 | Unsaved state is readable from any screen in under 0.5 s. In 1000 random `kill -9`s, the recovered text loses at most the last ~1 s and the file on disk is never left partially written. Saves are verified by reading back. An edit from another app is caught before we overwrite it. Encoding and line endings round-trip byte-for-byte. | The Part 3 tests in `docs/robustness.md` (kill test, save-fault injection, journal property tests, round-trip corpus) |
| 2 | **Calm / unobtrusive** | 3 | At rest only two bars are visible. No control appears until it applies (folder button, zoom chip, Save all, formatting strip). There are no dialogs on the happy path. | Screenshot review against the mockups; count chrome elements per state |
| 3 | **Position continuity** | 3 | Switching Code ↔ Preview lands within ±1 block of the anchor in ≥ 95% of corpus positions. Rotation, folding, resizing or a theme change never loses a character, and keeps the cursor, selection, scroll and undo. Reopening a file returns you to where you were. | Playwright anchor-mapping test; the **rotation matrix** in `robustness.md`, run on every PR |
| 4 | **Speed and large files** | 2 | Cold start to an editable doc < 800 ms. Keystroke to glyph < 16 ms on a 1 MB file. Preview refresh < 150 ms at 50 KB. Frame and keystroke cost don't grow with file size (virtualised): a 20 MB file types and scrolls like a 20 KB one. A 1 MB file opens in < 1 s and 20 MB in < 3 s. A file too big for memory is declined with an explanation **before** loading. No out-of-memory crashes and no ANRs. | Macrobenchmark on a Pixel 6a-class phone using the large-file corpus; JS timing in Playwright; CI monkey runs with StrictMode |
| 5 | **Rendering fidelity** | 2 | The whole test corpus renders correctly: CommonMark, GFM, footnotes, math, Mermaid, alerts, front matter, TOC, emoji. It does so in every built-in theme, light and dark. | Playwright corpus render plus a visual check |
| 6 | **Editing ergonomics** | 2 | The formatting strip and list or task continuation on Enter work. Undo/redo, find & replace and indent/outdent work. Native selection and IME work, including autocorrect and voice. **In Preview**, editing paragraphs, headings, lists and tables feels as direct as a notes app. A Preview edit changes only the edited block's source lines: every other line stays byte-identical. | Unit tests for the edit operations; a round-trip test over the corpus (edit one block, diff the rest); a manual IME pass |
| 7 | **Reading quality** | 2 | Text meets WCAG AA contrast in all themes and the line length is comfortable. The small default is still legible. Pinch reflows instead of scaling. | Contrast checker on the theme tokens; screenshot review |
| 8 | **Adaptive layouts** | 2 | Phone portrait, phone landscape, foldable and tablet each have a deliberate layout, never a stretched phone UI. Split orientation follows the window shape. | Robolectric renders at 360, 600, 840 and 1280 dp |
| 9 | **Power depth** | 1 | Folder mode, Save all, no-wrap, custom themes and fonts, PDF and Mermaid export are each reachable within two taps of ⋮. Any heading or recent file is reachable in ≤ 3 interactions through the Navigator (tap, type 2–3 letters, tap). A snippet can be shared into a file in ≤ 3 taps without leaving the sending app. | A tap-count audit |
| 10 | **Accessibility** | 1 | Every control has a TalkBack label and meets the touch-target tokens in `quality-bar.md` (48 dp icon buttons and keys; pills 40 dp with 48 dp hit areas; segments 44 dp). Every gesture-only action has a visible twin. System font scale is respected. No signal relies on colour alone. | An accessibility scanner plus a TalkBack pass |
| 11 | **Craft (open-source code quality)** | 2 | A stranger can clone the repo, build, run the tests and find their way around within 30 minutes. Lint, ktlint and detekt report zero issues. `core/*` has ≥ 90% coverage, with property tests. Dependencies point inwards (CI-enforced). Every significant decision has an ADR. Every row in `edge-cases.md` is `tested`. | CI gates; a review against `docs/engineering.md`; a fresh-clone test by someone new to the code |

Weighted average = Σ(score × weight) / 23.

## Scorecard

| Iteration | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | Weighted |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 0 · review 1 (design) | 3 | 4 | 3 | 3 | 3 | 3 | 3 | 3 | 4 | 2 | 3 | 3.13 |
| 0 · review 2 (design) | 4 | 4 | 4 | 3 | 3 | 3 | 4 | 3 | 4 | 3 | 3 | 3.52 |
| 0 · review 3 (design) | 4 | 4 | 4 | 3 | 3 | 3 | 4 | 3 | 4 | 2 | 3 | 3.48 |
| 0 · review 4 (design gate) | 4 | 4 | 4 | 3 | 3 | 3 | 4 | 3 | 4 | 3 | 3 | 3.52 · **gate passed** after M1/M2 fixes |
| 6 · round-2 review panels (build) | 3 | 4 | 4 | 3 | – | 4 | 4 | 3 | 4 | 3 | 4 | not computed (#5 unscored); below the bar on #4 evidence |
| 6 · unanchored scorer A (`912abcf`) | 4 | 5 | 4 | 4 | 4 | 4 | 4 | 4 | 5 | 3 | 4 | 4.13 · bar holds; two Majors (RTL drawn backwards, UTF-16 refused), both fixed |
| 6 · unanchored scorer B (`dc5a312`) | 4 | 5 | 4 | 4 | 5 | 4 | 5 | 4 | 5 | 4 | 4 | 4.35 · bar holds; Majors: no phone evidence yet; rotation matrix incomplete (since completed) |
| 6 · our score, release candidate (`6a5c706`) | 5 | 5 | 5 | 4 | 5 | 4 | 5 | 4 | 5 | 4 | 4 | 4.61 · **ship bar holds on host and CI evidence; release still needs the J1–J10 phone walk** |

### Release candidate 0.1.0 (`6a5c706`): evidence and disagreements

Scorers A and B were independent and unanchored: neither saw earlier
reviews, this table or any self-score. Every CI number below is from run
41410889697 (all four jobs green) or the runs named.

Where our score differs from a scorer's by a point or more, both
arguments are here so the reader can judge.

**#1 Never lose work: us 5, A 4, B 4.**
- *For 5:* 1000 seeded kills on the host model (`RandomKillTest`), 50 real
  `SIGKILL`s per push on the emulator (worst loss 623 ms), a background
  kill that comes back with every character, selection and line
  (`BackgroundKillTest`), read-back verified saves with 300 random faults
  (`SavePipelineTest`), outside edits caught before overwrite, and a
  byte-for-byte round-trip corpus that now includes UTF-16.
- *Against:* the 1000 kills are modelled, not real `kill -9`s (only 50
  real ones run per push), and power loss can only be shown on a phone.
  Undo does not survive process death; the docs promise text, view and
  position, not undo.

**#3 Position continuity: us 5, A 4, B 4.**
- *For 5:* Code ↔ Preview lands within ±1 block at 190 of 190 corpus
  positions at three viewports. The rotation matrix now runs every cell
  on every push: rotation, resize, theme, font scale, Split, Preview,
  mid-composition, mid-table-cell, find open, a sheet open, the unsaved
  prompt open, "Don't keep activities" and a background `am kill`. It
  found and fixed a cursor reset and a leaked Activity. Reopening
  returns you to where you were.
- *Against:* both scorers scored before the five last cells existed.
  Still untested: a Preview edit in flight across a change, and every
  state in every view mode (each state runs in Code only).

**#5 Rendering fidelity: us 5, A 4 (B 5).**
- *For 5:* every construct in the corpus is asserted in Playwright, and
  every built-in theme renders it in light and dark with WCAG AA.
- *A against:* the checks are structural, not golden images, and some
  Mermaid diagram types (mindmap, architecture) show a "not supported"
  box.

**#7 Reading quality: us 5, A 4 (B 5).**
- *For 5:* contrast is tested on every theme token and in the page;
  `max-width: 75ch`; pinch reflows through a CSS font variable, never
  page zoom.
- *A against:* A could not see Preview body text in the Robolectric
  stand-in. The README now carries the real Playwright renders.

**#10 Accessibility: us 4, A 3 (B 4).**
- *For 4:* the Accessibility Test Framework now scans all 50 screens and
  sheets in both themes on every build, and fixed three findings.
  Font scale and gesture twins are tested.
- *A against:* A scored before the scanner existed. The TalkBack
  listening pass still needs a phone, which is why we stop at 4.

**Pending a phone (caps #4, #6 and #10 at 4, and blocks release):** the
J1–J10 walk (quality-bar §5.3), the ADR 0001 budgets on a Pixel 6a-class
and a 3 GB phone, the keyboard and IME pass (Gboard, Samsung,
HeliBoard), TalkBack, a 20 MB file and a fling trace, and the Google
Recorder share.

**Known gaps we accepted:**
- No hinge-aware foldable layout: a fold is treated as its window size.
- Undo, open sheets and the find query do not survive process death.
- The Preview bundle is at 98.8% of its 3.5 MiB budget.
- About 150 `@Suppress` annotations, each with a reason, are worth a
  sweep.
