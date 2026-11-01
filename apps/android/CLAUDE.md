# SS Markdown Android

Kotlin + Compose. Pure-JVM core, thin app, no DI framework. Every
module holds real code; the map below says what each one has.

## Layout

```
app/              MainActivity, AppGraph, navigation and the screens (editor state machine, history, storage, settings, themes, licences)
core/text         PURE JVM. Piece table, line index, graphemes, undo.        (piece tree, done: see core/text/NOTICE)
core/markdown     PURE JVM. Tokenizer, block index, anchor maths, edit commands.  (done: tokenizer, outline, edit commands with fence-aware line commands and list renumbering)
core/history      PURE JVM. Journal, keyframe+diff history, retention.        (done: journal (FORMAT.md), versions, export/import + export listing (VERSIONS-FORMAT.md))
core/theme        PURE JVM. Theme schema, CSS generation, contrast checks.    (done: schema, codec, CSS, contrast; see THEME-CSS.md)
core/ui           Design system: SS.colors, SSType, SSDimens, SS* components  (formatting strip incl. table mode)
feature/editor    MarkdownCodeView and collaborators: viewport, renderer (per-line RenderNode cache), IME (cursor updates), find highlights, sliced paste, gestures, a11y
feature/preview   WebView host, bridge, edit sync, print/export (see "Preview bundle")
feature/files     SAF, DocumentStore, save pipeline (backup, verify, timeout), three-way merge (merge/), MemoryGovernor, folder actions, document locations
feature/fonts     Google Fonts download and cache
build-logic/      ssmarkdown.* convention plugins (below)
config/detekt/    detekt overrides; ktlint reads ../.editorconfig
tools/            bootstrap-android-sdk.sh, fetch-fonts.sh, editor-bench (ADR 0001 benchmark, never shipped)
```

Convention plugins (`build-logic/convention`):

- `ssmarkdown.jvm.library` — Kotlin JVM, Java 17, **explicit API mode**.
  Every `core:*` module except `core:ui`.
- `ssmarkdown.android.library` — compileSdk 36, minSdk 29, Java 17.
  `core:ui` and `feature:*`.
- `ssmarkdown.static-analysis` — ktlint, detekt and Android lint
  (`warningsAsErrors`), all in `check`. Applied by both of the above and
  by `:app` directly.
- `ssmarkdown.coverage` — Kover on one core module, with `koverVerify`
  (>= 90% lines, docs/engineering.md) in `check`. Applied by
  `ssmarkdown.jvm.library` and by `core:ui`.
- `ssmarkdown.coverage-report` — root only: the merged core report
  (`koverHtmlReport`, `koverXmlReport` → `build/reports/kover/`) and the
  same bar on the total.
- `ssmarkdown.module-graph` — root only. `checkModuleGraph` (in `check`)
  fails if `core:*` depends on `feature:*`/`:app`, or a feature on
  another feature or `:app`.

## Commands

No wrapper. Run from the REPO ROOT.

```sh
export ANDROID_HOME=/root/android-sdk          # bootstrap: apps/android/tools/bootstrap-android-sdk.sh
gradle -p apps/android check                   # graph, ktlint, detekt, lint, all unit tests, core coverage gate
gradle -p apps/android koverHtmlReport koverLog   # merged core coverage, per-module numbers
gradle -p apps/android :core:text:test         # one pure module, host JVM, seconds
gradle -p apps/android ktlintFormat            # fix formatting before committing
gradle -p apps/android :app:assembleDebug
gradle -p apps/android -Pssmarkdown.minifyDebug=true :app:assembleDebug   # hand-over build (below)
gradle -p apps/android -Pssmarkdown.screenshots=true :app:testDebugUnitTest --tests '*ScreenshotTest*'
gradle -p apps/android :app:connectedDebugAndroidTest   # needs a device or emulator
```

Screenshots land in `app/build/screenshots/{light,dark}/` (never
committed; the folder is emptied at the start of each screenshot run, so
nothing stale survives). CI uploads them as the `screenshots` artifact and
runs the instrumented tests on an API 34 emulator in a separate job.

## The hand-over build

`-Pssmarkdown.minifyDebug=true :app:assembleDebug` is the artefact handed
to anyone. It is signed with the debug key but is otherwise a release
build: **not debuggable** (a debuggable variant makes R8 run in debug
mode, with no optimisation or renaming and no baseline profile), English
resources only, and without ui-tooling's exported `PreviewActivity`.
Measured 2026-11-01: **6.15 MB** (classes.dex 3.32 MB), down from 15.12 MB
when it was debuggable. CI fails the build if it becomes debuggable again,
ships `PreviewActivity`, or loses `baseline.prof` or the licence texts.

- **Archive `mapping.txt` with every APK.** The build is obfuscated, so a
  stack trace from it is unreadable without the mapping of that exact
  build: `app/build/outputs/mapping/debug/mapping.txt`. CI uploads it in
  the same artifact as the APK; a Drive hand-over puts it next to the APK.
- **Keep rules.** `app/proguard-rules.pro` is empty on purpose and records
  why (no reflection, no serialization library, no `@JavascriptInterface`).
  Adding any of those means adding a precise keep rule and a test.
- **App baseline profile: deferred to the device run.** Library profiles
  ship today. Generating the app's own (cold start, open 1 MB, fling)
  needs a `:baselineprofile` Macrobenchmark module run on a device or a
  managed emulator, which this container cannot do; add it with the
  device measurements for ADR 0001 (performance review PERF-20).
- **APK size** is a guideline, not a hard limit (`docs/quality-bar.md` §4):
  shrink only where nothing regresses, and record any deliberate rise.

Tests run on debug only (root `build.gradle.kts` removes the release
unit-test component). Robolectric is pinned to `sdk=34` in
`app/src/test/resources/robolectric.properties`; a different `@Config`
sdk buys a second sandbox and doubles the run time.

## Traps

- **Gradle from the wrong directory** fails in one second with a path
  like `apps/android/apps/android`. Run from the repo root with `-p`.
- **Formatting is ktlint_official**, not IntelliJ's default. Run
  `ktlintFormat` rather than reformatting in the IDE, or `check` fails.
- **detekt runs on Kotlin 2.0.21**, not the project's Kotlin. The
  static-analysis plugin pins detekt's classpath; do not remove that
  block when bumping Kotlin or detekt refuses to start.
- **A pure-JVM core module cannot even resolve an Android feature**, so
  a `core:text -> feature:*` edge fails at dependency resolution before
  `checkModuleGraph` runs. Android-to-Android violations
  (`feature:editor -> feature:preview`) reach the graph check and get its
  message. `gradle -p apps/android checkModuleGraph` alone always gives
  the graph message.
- **Screenshot capture** draws every open window's root view into a
  software bitmap, so modal sheets (their own window) are in the shot.
  compose-test's `captureToImage()` deadlocks under Robolectric's paused
  looper. Sheet shots use `sheet(...)`, which fails if only one window was
  drawn. `ComponentActivity` is registered in `app/src/debug/AndroidManifest.xml`
  because the ui-test-manifest AAR is not merged into unit-test manifests.
- **configChanges** in the manifest is the robustness.md §5a list. Do not
  trim it: losing `uiMode` or `fontScale` recreates the Activity on a
  theme or text-size change.
- **Maven Central rate-limits** cloud containers (HTTP 429). If a build
  fails that way, retry, or point a local init script at Google's Central
  mirror (`https://maven-central.storage-download.googleapis.com/maven2/`)
  and set `robolectric.dependency.repo.url` to the same for Robolectric's
  own downloads. Never commit that workaround.
- **Fonts.** Inter, JetBrains Mono and four more families are bundled in
  `feature/fonts` (OFL, subset to Latin; see NOTICE). The app chrome is set
  in Inter and the Code pane in JetBrains Mono, read from those same files
  (no extra bytes). core/ui owns no font files and must not depend on a
  feature, so `app/ui/ChromeFonts.kt` builds the families and passes them
  to `SSMarkdownTheme(fonts = …)`, and hands the code face to
  `MarkdownCodeView.codeTypeface`. `SSType.*` are `@Composable` getters
  that read the theme's scale: use them inside composition only. A test
  that themes without `fonts` gets the system families. Never drop a
  bundled family to save size.

## Preview bundle

`feature/preview/web` is the page Preview runs: TypeScript built with esbuild
into `feature/preview/src/main/assets/preview/`, which is committed so Gradle
never needs Node. Its README is the map.

```sh
cd apps/android/feature/preview/web
npm ci                          # exact versions from package-lock.json
npm run build                   # rebuild the committed assets; commit them with src/
npm test                        # tsc, ESLint + Prettier, Vitest unit, Playwright e2e
npx vitest run --project unit   # pure logic only, seconds
npx playwright install chromium # once per machine, for the e2e tests
gradle -p apps/android :feature:preview:check   # Kotlin bridge, editing sync, formatter
```

Traps:

- **The e2e tests drive the BUILT bundle.** Edit `src/`, then `npm run build`,
  then test; otherwise you are testing yesterday's assets. Commit the rebuilt
  assets in the same commit as the source.
- **The edit protocol has two halves that must change together**:
  `web/src/core/ot.ts` + `core/editSync.ts` and Kotlin's
  `editing/TextTransform.kt` + `PreviewEditSync.kt`. Same algorithm, same tie
  rule (host inserts first). Both have property tests; run both.
- **Every revision reaches the page exactly once**, as `edit` or `ack`. A host
  that applies a page edit must send the `ack` from `PreviewEditor`, and must
  never apply `sourceEdit`s directly: they may predate a Code-pane change.
  Use `PreviewEditor` for all of it.
- **The e2e harness plays Kotlin** and auto-acks page edits. To test
  concurrency, set `window.__autoAck = false` and send acks by hand.
- **Never make a rendered character editable unless it is an exact run.** The
  unit test `editSafety.test.ts` fails on any visible text in an editable
  block that is neither in a `[data-s]` run nor `contenteditable="false"`.
- **CI runs all of it** (`preview-web` job): typecheck, lint, unit, a
  fresh build that must match the committed assets byte for byte, the
  size budget, then Playwright e2e on Chromium.
- **Bundle budget**: 3.5 MiB for the whole `assets/preview` folder, as
  `build.mjs` reports it (3.43 MiB on 2026-11-01), enforced in CI; `cytoscape` is stubbed (mindmap and
  architecture diagrams show a named "not supported" box with Show source).
