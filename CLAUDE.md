# SS Markdown

A calm, fast Markdown editor for Android by Scorchsoft. It opens straight
to an editor and offers one-tap Code / Split / Preview, always-visible
Copy and Save, an unmistakable unsaved state, Mermaid, themes, pinch zoom
and a quiet folder mode.

**Read `docs/product-journey.md` first.** It holds the vision, the
principles ("features may be deep, chrome may not be busy") and the
reasons behind every decision so far. Then:

- `docs/ux-spec.md` — what each control does and when it appears.
- `docs/robustness.md` — the never-lose-work and large-file contract.
  Required reading before touching files, saving or the editor.
- `docs/research-findings.md` — what users hate and love in other editors, and the requirements (R1–R13) we took from it.
- `docs/performance-techniques.md` — the open-source techniques we borrowed, with sources and licences.
- `docs/edge-cases.md` — every known hostile case, how it's handled, and its test. Add a row for every new bug.
- `docs/engineering.md` — open-source code standards (Apache-2.0, showcase quality).
- `docs/quality-bar.md` — the ten jobs, anti-goals, premium-feel spec, every budget, and how we score. If a feature can't point to a job here, it doesn't ship.
- `docs/design-review-1.md`, `docs/design-review-2.md` — independent design reviews and how every finding was triaged.
- `docs/rubric.md` — the definition of good. Score it at the end of
  every iteration.

## Status

Iteration 0 (design) is in progress: the mockups are under review and
there is no app code yet. Do not start the Android app until the mockups
are signed off.

## Planned layout

The module map and code standards are in `docs/engineering.md`. In short:
pure-JVM `core/*` (text, markdown, history, theme), `core/ui`, then
`feature/*` (editor, preview, files), with a thin `app` on top.
Dependencies point inwards only.

## Conventions

- The toolchain mirrors `scorchsoft/measur3d`'s `apps/android`. There is
  no Gradle wrapper. Run `gradle -p apps/android <task>` from the repo
  root.
- The app is offline. `INTERNET` is used only to download a chosen
  Google Font. Never add analytics, ads or accounts.
- Screens are pure `(state, callbacks)` composables, so every screen
  renders under Robolectric.
- Build artefacts never go in git; hand-over APKs go to Drive.
