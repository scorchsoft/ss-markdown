# SS Markdown Android

Kotlin + Compose. Pure-JVM core, thin app, no DI framework. Every
module holds real code; the map below says what each one has.

## Layout

```
app/              MainActivity (edge to edge, handles its own config changes) and shell/
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
tools/            bootstrap-android-sdk.sh
```

Convention plugins (`build-logic/convention`):

- `ssmarkdown.jvm.library` — Kotlin JVM, Java 17, **explicit API mode**.
  Every `core:*` module except `core:ui`.
- `ssmarkdown.android.library` — compileSdk 36, minSdk 29, Java 17.
  `core:ui` and `feature:*`.
- `ssmarkdown.static-analysis` — ktlint, detekt and Android lint
  (`warningsAsErrors`), all in `check`. Applied by both of the above and
  by `:app` directly.
- `ssmarkdown.module-graph` — root only. `checkModuleGraph` (in `check`)
  fails if `core:*` depends on `feature:*`/`:app`, or a feature on
  another feature or `:app`.

## Commands

No wrapper. Run from the REPO ROOT.

```sh
export ANDROID_HOME=/root/android-sdk          # bootstrap: apps/android/tools/bootstrap-android-sdk.sh
gradle -p apps/android check                   # graph, ktlint, detekt, lint, all unit tests
gradle -p apps/android :core:text:test         # one pure module, host JVM, seconds
gradle -p apps/android ktlintFormat            # fix formatting before committing
gradle -p apps/android :app:assembleDebug
gradle -p apps/android -Pssmarkdown.minifyDebug=true :app:assembleDebug   # hand-over build, through R8
gradle -p apps/android -Pssmarkdown.screenshots=true :app:testDebugUnitTest --tests '*ScreenshotTest*'
gradle -p apps/android :app:connectedDebugAndroidTest   # needs a device or emulator
```

Screenshots land in `app/build/screenshots/{light,dark}/` (never
committed). CI uploads them as the `screenshots` artifact and runs the
instrumented tests on an API 34 emulator in a separate job.

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
- **Screenshot capture** draws the decor view into a software bitmap.
  compose-test's `captureToImage()` deadlocks under Robolectric's paused
  looper. `ComponentActivity` is registered in `app/src/debug/AndroidManifest.xml`
  because the ui-test-manifest AAR is not merged into unit-test manifests.
- **configChanges** in the manifest is the robustness.md §5a list. Do not
  trim it: losing `uiMode` or `fontScale` recreates the Activity on a
  theme or text-size change.
- **Maven Central rate-limits** cloud containers (HTTP 429). If a build
  fails that way, retry, or point a local init script at Google's Central
  mirror (`https://maven-central.storage-download.googleapis.com/maven2/`)
  and set `robolectric.dependency.repo.url` to the same for Robolectric's
  own downloads. Never commit that workaround.
- **Fonts.** `SSType` uses the system sans and monospace until Inter and
  JetBrains Mono are bundled in iteration 2.

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
- **Bundle budget**: about 3.4 MB; `cytoscape` is stubbed (mindmap and
  architecture diagrams show a named "not supported" box with Show source).
