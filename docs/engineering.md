# Engineering standards

SS Markdown is **open source (Apache-2.0)** and is a public example of
how Scorchsoft builds software. Code should read like it was written by
people who care: clear, small, well named, well tested. The test for any
change: *would we be happy for a hiring manager to read this file
first?*

## Architecture

```
apps/android/
  core/text       PURE JVM. Piece table, line index, graphemes, undo. No Android.
  core/markdown   PURE JVM. Tokenizer, block index, anchor maths, Preview round-trip.
  core/history    PURE JVM. Journal, keyframe+diff history, retention.
  core/theme      PURE JVM. Theme JSON schema, CSS generation, contrast checks.
  core/ui         Design system: tokens, type, components. No app logic.
  feature/editor  MarkdownCodeView, InputConnection, gestures, a11y.
  feature/preview WebView host, bridge, viewer, print/export.
  feature/files   Storage Access Framework, DocumentStore, save pipeline, MemoryGovernor.
  feature/fonts   Bundled fonts, the Google Fonts catalogue and downloader, font import.
  feature/preview/web   The Preview page: TypeScript, built by esbuild into
                  feature/preview/src/main/assets/preview (committed; ADR 0004).
  app             Wiring (AppGraph), navigation, and the screens with their
                  view models and state machines.
  tools/editor-bench   The ADR 0001 benchmark. Never shipped; nothing may depend on it.
```

`app` is not thin: it holds every screen's state machine and view model,
while the features are the engines underneath. That is a known trade-off
(code-craft review CC-7), not the goal; pure reducers belong in a module
when they are next reworked. The module graph and its rules are in
[ADR 0010](adr/0010-module-graph.md).

- **Dependencies point inwards.** `feature/*` depends on `core/*`, never
  the other way round, and features never depend on each other. The
  convention plugins enforce this with a module-graph check in CI.
- **Pure core.** Everything that can be pure Kotlin is, and it runs on
  the host JVM in seconds.
- **Unidirectional data flow.** Each screen is driven by one immutable
  `UiState` plus a sealed `Action` and a reducer. Side effects live in
  view models with injected `CoroutineDispatcher`s. Composables are pure
  `(state, onAction)` functions.
- **Constructor injection, no DI framework.** A small `AppGraph` builds
  everything. It's explicit, fast, and easy for newcomers to follow
  ([ADR 0003](adr/0003-no-di-framework.md)).
- **Errors are values.** I/O returns `Result` or sealed outcomes
  (`SaveOutcome.Conflict`, `.PermissionLost`, `.StorageFull`, …).
  Exceptions are for programmer errors. Never use `!!` outside tests.
- **No hidden global state.** No singletons except `AppGraph`.
  Everything under test can be swapped out: clock, file system,
  provider and memory info.

## Code style

- Kotlin official style. **ktlint + detekt** with zero warnings, plus
  Android lint with zero warnings. All three are enforced in CI.
- **Explicit API mode** in the pure-JVM core modules (text, markdown,
  history, theme): every public declaration is deliberate. `core/ui` is
  an Android module and is not in explicit API mode. Everywhere, public
  API has KDoc explaining *why*, not just *what*.
- Files ≤ 400 lines and functions ≤ 40 lines as guides, not dogma. A
  longer one needs a reason a reviewer would accept. detekt enforces the
  hard limits: its defaults (60-line `LongMethod`, 600-line
  `LargeClass`) plus the overrides in `config/detekt/detekt.yml`.
- Names say what things are. No `Manager` / `Helper` / `Util` grab-bags.
- Comments explain decisions and traps, never restate code. Non-obvious
  choices link to an ADR or a section in `docs/`.
- TypeScript in `feature/preview/web`: strict mode, ESLint and Prettier,
  unit tests with Vitest, and Playwright for rendering.

## Decisions

- Every significant decision gets an **ADR** in `docs/adr/NNNN-title.md`
  (context, options, decision, consequences). They start at 0001, the
  editor engine.
- `docs/product-journey.md` stays the human-readable story;
  ADRs are the precise record.

## Testing

| Layer | Tooling | Bar |
|---|---|---|
| `core/*` | JUnit + property tests (hand-rolled, seeded random loops with the seed in the failure message) | ≥ 90% line coverage per module, measured by Kover and gated in `check` (benchmark harnesses excluded); property tests for text, journal and history |
| `feature/files` | JUnit + Robolectric against the fake provider | ≥ 88% line coverage, gated in `check` (the never-lose-work pipeline; set at what it passed when gated, 88.5%, and only ever raised). `feature/editor` and `app` are not gated yet |
| Screens | Robolectric + Compose UI tests + screenshots (our own harness: every window drawn into a software bitmap, `ScreenshotTest`) | Every screen and sheet in light and dark; 2.0× font for the editor; phone, 600, 840 and 1280 dp layouts |
| Preview bundle | Vitest + Playwright (Chromium), in CI | Rendering corpus, security corpus, edit protocol, anchor mapping; the committed bundle must match a fresh build |
| Device | Instrumented tests on an API 34 emulator | Run in CI on every push: the rotation matrix (`RotationTest`, `RotationMatrixTest`), the 50-kill loop (`KillLoopTest`), `DeviceEdgeCasesTest`, all under LeakCanary. The ×1000 kill test and the fault-injection provider run on the host (`RandomKillTest`, `SavePipelineTest`) |
| Performance | Macrobenchmark, the app Baseline Profile and a monkey + StrictMode run on CI's emulator (`perf` job); host count- and allocation-based tests | Emulator numbers are advisory against `rubric.md` criterion 4; the job gates a central metric only past 2x `docs/perf/emulator-baseline.json` (`performance-techniques.md`). The phone run is still to do |

Every bug fix starts with a failing test, and every new edge case gets a
row in `docs/edge-cases.md`.

## Dependencies

- As few as possible. Each one is justified in an ADR or in the version
  catalogue with a comment, and pinned to an exact version.
- **No Google Play Services**, no analytics, no ads, no proprietary
  SDKs. The app must be buildable for F-Droid (reproducible builds are
  a goal).
- JS libraries (markdown-it, mermaid, KaTeX, highlight.js, DOMPurify)
  are pinned exactly in `feature/preview/web/package-lock.json`. The
  build collects their licences into `LICENSES.txt`, which the in-app
  "Open-source licences" screen shows in full. They are updated by hand
  (`npm install <pkg>@<version>`, rebuild, commit), and CI checks the
  committed bundle matches.

## Repository hygiene for open source

- `README.md` with screenshots, features, install (Play, F-Droid, APK)
  and build instructions.
- `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md` (Contributor Covenant),
  `SECURITY.md` (private disclosure), and issue and PR templates.
- GitHub Actions: build, the test layers above, lint, and the
  module-graph check, on every PR. Release builds will be signed in CI
  from secrets once there is a release key; until then the hand-over is
  debug-signed.
- `CHANGELOG.md` (Keep a Changelog) and semantic versioning.
- No secrets, keystores or personal data in the repo, ever.
