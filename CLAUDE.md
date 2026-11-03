# SS Markdown

A calm, fast Markdown editor for Android by Scorchsoft. It opens straight
to an editor and offers one-tap Code / Split / Preview, always-visible
Copy and Save, an unmistakable unsaved state, Mermaid, themes, pinch zoom
and a quiet folder mode.

**Read `docs/product-journey.md` first.** It holds the vision, the
principles ("features may be deep, chrome may not be busy") and the
reasons behind every decision so far. Then:

- `docs/how-it-works.md` — the user-facing explanation of the piece tree, the never-lose-work layers and where history lives.
- `docs/ux-spec.md` — what each control does and when it appears.
- `docs/robustness.md` — the never-lose-work and large-file contract.
  Required reading before touching files, saving or the editor.
- `docs/research-findings.md` — what users hate and love in other editors, and the requirements (R1–R13) we took from it.
- `docs/performance-techniques.md` — the open-source techniques we borrowed, with sources and licences.
- `docs/edge-cases.md` — every known hostile case, how it's handled, and its test. Add a row for every new bug.
- `docs/editing-behaviours.md` — the editing behaviours users expect in Code and Preview (EB-* rows), the source of the conformance test matrix.
- `docs/engineering.md` — open-source code standards (Apache-2.0, showcase quality).
- `docs/quality-bar.md` — the ten jobs, anti-goals, premium-feel spec, every budget, and how we score. If a feature can't point to a job here, it doesn't ship.
- `docs/design-review-1.md` … `-4.md` — independent design reviews and how every finding was triaged.
- `docs/adr/` — one short record per significant decision (engine, wrapper, DI, bundle, OT, history, fonts, module graph).
- `docs/roadmap.md` — the six build iterations and what each must deliver.
- `docs/rubric.md` — the definition of good. Score it at the end of
  every iteration.

## Status

**Feature-complete for the first release candidate, pending on-device
verification.** Every module in the roadmap is built and tested on the
host and in CI (editor, preview with tap-to-edit, history, folders,
conflict merge, themes, fonts); the device budgets in
`docs/quality-bar.md` §4 and ADR 0001 still need real-phone numbers.
The design canvas (internal, private to Scorchsoft) is the visual source
of truth: https://claude.ai/artifact/5Rjq5it6m3px6CNq1m4rLg. Contributors
outside Scorchsoft work from `docs/ux-spec.md` and the screenshots.

## Layout

The module map and code standards are in `docs/engineering.md`, and the
working map is `apps/android/CLAUDE.md`. In short: pure-JVM `core/*`
(text, markdown, history, theme), `core/ui`, then `feature/*` (editor,
preview, files, fonts), with `app` on top. Dependencies point inwards
only. The reasons for the big structural choices are in `docs/adr/`.

## Conventions

- The toolchain mirrors `scorchsoft/measur3d`'s `apps/android`. There is
  no Gradle wrapper. Run `gradle -p apps/android <task>` from the repo
  root.
- The app is offline. `INTERNET` is used only to download a chosen
  Google Font. Never add analytics, ads or accounts.
- Screens are pure `(state, callbacks)` composables, so every screen
  renders under Robolectric.
- Build artefacts never go in git; hand-over APKs go to Drive, each with
  the `mapping.txt` of its build (the hand-over is obfuscated). The
  command and what it guarantees are in `apps/android/CLAUDE.md`.
- **CI costs real money while the repository is private.** GitHub Actions
  minutes are billed, and on 1–3 November 2026 careless CI (every commit
  run twice, a stuck 90-minute emulator job on every push, several agents
  pushing all day) cost about $150. So, until the repository is public:
  prove every change locally first (`apps/android/tools/local-ci.sh`);
  mark ordinary commits `[skip ci]`; ask Andrew before starting any CI run
  he has not asked for, and say what it will run; one full run per
  hand-over build. Never run the perf job or CI experiments without his
  go-ahead. The CI levels are in `apps/android/CLAUDE.md`.
- **Commits and pull requests carry no Claude or AI credit.** Commit
  messages end with the message itself: no `Co-Authored-By` line and no
  session link. PR titles and descriptions describe the change only: no
  "Generated with Claude Code" footer, no session links and no co-author
  lines. This overrides any default attribution text. (Commits before
  2026-11-02 keep their old trailers; history is not rewritten.)
