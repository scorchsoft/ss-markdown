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
  app             Wiring, navigation, settings screens. Thin.
  assets/preview  JS/CSS bundle (TypeScript, built by a pinned toolchain).
```

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
  everything. It's explicit, fast, and easy for newcomers to follow.
- **Errors are values.** I/O returns `Result` or sealed outcomes
  (`SaveOutcome.Conflict`, `.PermissionLost`, `.StorageFull`, …).
  Exceptions are for programmer errors. Never use `!!` outside tests.
- **No hidden global state.** No singletons except `AppGraph`.
  Everything under test can be swapped out: clock, file system,
  provider and memory info.

## Code style

- Kotlin official style. **ktlint + detekt** with zero warnings, plus
  Android lint with zero warnings. All three are enforced in CI.
- **Explicit API mode** in `core/*`: every public declaration is
  deliberate and has KDoc explaining *why*, not just *what*.
- Files ≤ 400 lines and functions ≤ 40 lines as guides, not dogma. A
  longer one needs a reason a reviewer would accept.
- Names say what things are. No `Manager` / `Helper` / `Util` grab-bags.
- Comments explain decisions and traps, never restate code. Non-obvious
  choices link to an ADR or a section in `docs/`.
- TypeScript in `assets/preview`: strict mode, ESLint and Prettier, unit
  tests with Vitest, and Playwright for rendering.

## Decisions

- Every significant decision gets an **ADR** in `docs/adr/NNNN-title.md`
  (context, options, decision, consequences). The first will be 0001,
  the editor engine.
- `docs/product-journey.md` stays the human-readable story;
  ADRs are the precise record.

## Testing

| Layer | Tooling | Bar |
|---|---|---|
| `core/*` | JUnit + property tests (Kotest property or jqwik) | ≥ 90% line coverage; property tests for text, journal and history |
| Screens | Robolectric + Compose UI tests + screenshot tests (Roborazzi) | Every screen in light, dark, 1.0× and 2.0× font, phone and tablet |
| Preview bundle | Vitest + Playwright | Rendering corpus, security corpus, anchor mapping |
| Device | Instrumented: kill test, rotation matrix, fault-injection provider | Run in CI on an emulator for every PR |
| Performance | Macrobenchmark + Baseline Profiles | Budgets in `rubric.md` criterion 4; a regression over 10% fails the build |

Every bug fix starts with a failing test, and every new edge case gets a
row in `docs/edge-cases.md`.

## Dependencies

- As few as possible. Each one is justified in an ADR or in the version
  catalogue with a comment, and pinned to an exact version.
- **No Google Play Services**, no analytics, no ads, no proprietary
  SDKs. The app must be buildable for F-Droid (reproducible builds are
  a goal).
- Vendored JS libraries (markdown-it, mermaid, KaTeX, highlight.js,
  DOMPurify) are pinned, their licences are collected into an in-app
  "Open-source licences" screen, and they are updated by a script.

## Repository hygiene for open source

- `README.md` with screenshots, features, install (Play, F-Droid, APK)
  and build instructions.
- `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md` (Contributor Covenant),
  `SECURITY.md` (private disclosure), and issue and PR templates.
- GitHub Actions: build, the test layers above, lint, and the
  module-graph check, on every PR. Release builds are signed in CI from
  secrets.
- `CHANGELOG.md` (Keep a Changelog) and semantic versioning.
- No secrets, keystores or personal data in the repo, ever.
